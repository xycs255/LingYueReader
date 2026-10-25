package com.xycs.lingyuereader.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 给读者看的出错说明（中文、不含密码）。 */
class RemoteException(message: String) : IOException(message)

/** OPDS 的封面可能指向任意第三方；只自动加载读者已配置书源的同源图片。 */
fun sameCatalogOrigin(catalogUrl: String, imageUrl: String): Boolean {
    val catalog = catalogUrl.trim().toHttpUrlOrNull() ?: return false
    val image = imageUrl.trim().toHttpUrlOrNull() ?: return false
    return catalog.scheme == image.scheme && catalog.host == image.host && catalog.port == image.port
}

/**
 * 连书源。密码只加在和书源同一主机、同一端口的请求上：网络拦截器逐跳检查，重定向到别的主机时不带。
 * 只在读者浏览、搜索、下载、刷新时调用。
 */
class RemoteClient(private val catalogs: () -> List<Catalog>) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            val auth = credentialsFor(req.url)
            chain.proceed(if (auth != null && req.header("Authorization") == null) req.newBuilder().header("Authorization", auth).build() else req)
        }
        .build()
    /** 封面不跟随跳转，避免同源 URL 在后台把请求转给别的网站。 */
    private val imageHttp = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** 同一主机上有几个书源时，取地址最长、最贴近的那个的账号。 */
    private fun credentialsFor(url: HttpUrl): String? = catalogs()
        .filter { it.username.isNotEmpty() || it.password.isNotEmpty() }
        .mapNotNull { c -> c.url.trim().toHttpUrlOrNull()?.let { c to it } }
        .filter { (_, u) -> u.host.equals(url.host, ignoreCase = true) && u.port == url.port && u.scheme == url.scheme }
        .maxByOrNull { (_, u) -> if (url.encodedPath.startsWith(u.encodedPath)) u.encodedPath.length else 0 }
        ?.first?.let { Credentials.basic(it.username, it.password, Charsets.UTF_8) }

    /** 打开书源里的一页：OPDS 取 Atom，WebDAV 列文件夹。 */
    suspend fun page(catalog: Catalog, url: String): RemotePage = when (catalog.kind) {
        CatalogKind.OPDS -> Opds.parse(get(url, "application/atom+xml, application/xml;q=0.9, */*;q=0.8"), url)
        CatalogKind.WEBDAV -> {
            val folder = if (url.endsWith("/")) url else "$url/"
            val items = WebDav.parse(propfind(folder), folder)
            val name = java.net.URLDecoder.decode(folder.trimEnd('/').substringAfterLast('/'), "UTF-8")
            RemotePage(if (folder.trimEnd('/') == catalog.url.trim().trimEnd('/')) catalog.name else name, items)
        }
    }

    /** OPDS 搜索：书目给的是描述文件时先取它，找出模板。 */
    suspend fun search(page: RemotePage, query: String): RemotePage {
        val template = page.searchTemplate
            ?: page.searchDescription?.let { Opds.searchTemplate(get(it, "application/opensearchdescription+xml, */*"), it) }
            ?: throw RemoteException("这个书目不支持搜索")
        val url = Opds.searchUrl(template, query)
        return Opds.parse(get(url, "application/atom+xml, */*"), url)
    }

    /** 封面缩略图，最多 2 MB。 */
    suspend fun image(url: String): ByteArray = withContext(Dispatchers.IO) {
        call(Request.Builder().url(url).build(), imageHttp).use { r ->
            check(r, url)
            val body = r.body ?: throw RemoteException("没有内容")
            if (body.contentLength() > MAX_IMAGE) throw RemoteException("图片太大")
            body.byteStream().use { it.readNBytesCompat(MAX_IMAGE, "图片太大") }
        }
    }

    /** 下载一本书（给书架导入、刷新用，在 IO 线程里调用）：返回的流关闭时连接一起关。 */
    fun open(url: String): InputStream {
        val r = execute(Request.Builder().url(url).build())
        try {
            check(r, url)
        } catch (e: IOException) {
            r.close()
            throw e
        }
        val body = r.body ?: run { r.close(); throw RemoteException("没有内容") }
        return object : FilterInputStream(body.byteStream()) {
            override fun close() {
                super.close()
                r.close()
            }
        }
    }

    private suspend fun get(url: String, accept: String): ByteArray = withContext(Dispatchers.IO) {
        call(Request.Builder().url(url).header("Accept", accept).build()).use { r ->
            check(r, url)
            readPage(r)
        }
    }

    /**
     * 读一页书目、一个文件夹列表：限制大小（异常大的回应不能把内存撑爆），读的过程中协程取消就关掉连接，
     * 不再在后台接着下完（读者已经离开这一页了）。
     */
    private suspend fun readPage(r: Response): ByteArray = coroutineScope {
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                r.close()
            }
        }
        try {
            val body = r.body ?: return@coroutineScope ByteArray(0)
            if (body.contentLength() > MAX_PAGE) throw RemoteException("书源返回的内容太大")
            try {
                body.byteStream().use { it.readNBytesCompat(MAX_PAGE, "书源返回的内容太大") }
            } catch (e: IOException) {
                ensureActive()
                throw e
            }
        } finally {
            watcher.cancel()
        }
    }

    private suspend fun propfind(url: String): ByteArray = withContext(Dispatchers.IO) {
        val body = WebDav.PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        call(Request.Builder().url(url).method("PROPFIND", body).header("Depth", "1").build()).use { r ->
            if (r.code == 405 || r.code == 501) throw RemoteException("这个地址不支持 WebDAV（${r.code}）")
            check(r, url)
            readPage(r)
        }
    }

    private fun request(url: String): Request.Builder =
        Request.Builder().url(url.toHttpUrlOrNull()?.toString() ?: throw RemoteException("地址不对：要以 http:// 或 https:// 开头"))

    /** 可取消的请求：协程取消时连接一起断。 */
    private suspend fun call(req: Request, client: OkHttpClient = http): Response {
        val validated = request(req.url.toString()).headers(req.headers).method(req.method, req.body).build()
        val c = client.newCall(validated)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { c.cancel() }
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(friendly(e, validated.url.host))
                override fun onResponse(call: Call, response: Response) = cont.resume(response) { response.close() }
            })
        }
    }

    private fun execute(req: Request): Response = try {
        http.newCall(request(req.url.toString()).build()).execute()
    } catch (e: IOException) {
        throw friendly(e, req.url.host)
    }

    private fun check(r: Response, url: String) {
        if (r.isSuccessful) return
        val digest = r.header("WWW-Authenticate")?.startsWith("Digest", ignoreCase = true) == true
        throw RemoteException(
            when (r.code) {
                401 -> if (digest) "这个服务器要求摘要认证，灵阅暂只支持普通的用户名密码认证" else "用户名或密码不对"
                403 -> "没有权限访问"
                404 -> "找不到这个地址（404）"
                in 500..599 -> "服务器出错（${r.code}），稍后再试"
                else -> "请求失败（${r.code}）"
            },
        )
    }

    private fun friendly(e: IOException, host: String): IOException = when (e) {
        is RemoteException -> e
        is UnknownHostException -> RemoteException("找不到 $host：检查网络或地址")
        is SocketTimeoutException -> RemoteException("等太久没有回应，稍后再试")
        is SSLException -> RemoteException("安全连接失败：证书无效或不受信任")
        else -> if (e.message == "Canceled") e else RemoteException("网络出错：${e.message ?: e.javaClass.simpleName}")
    }

    private fun InputStream.readNBytesCompat(limit: Int, tooLarge: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw RemoteException(tooLarge)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_IMAGE = 2 * 1024 * 1024
        /** 一页书目、一个文件夹列表最多多大。 */
        const val MAX_PAGE = 16 * 1024 * 1024
    }
}
