package com.xycs.lingyuereader.remote

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 书源的种类：电子书目录的开放标准 OPDS，或放着书的 WebDAV 文件夹。 */
enum class CatalogKind(val label: String) {
    OPDS("OPDS 书目"),
    WEBDAV("WebDAV 文件夹"),
}

/** 一个书源：地址，需要时带用户名和密码（HTTP Basic 认证）。 */
data class Catalog(
    val id: String = UUID.randomUUID().toString(),
    val kind: CatalogKind,
    val name: String,
    val url: String,
    val username: String = "",
    val password: String = "",
) {
    /** 地址以 http:// 开头又填了密码：密码会明文传输。 */
    val insecure: Boolean get() = url.trim().startsWith("http://", ignoreCase = true) && (username.isNotEmpty() || password.isNotEmpty())

    /** 打日志、出错时不会把密码带出去。 */
    override fun toString(): String = "Catalog(id=$id, kind=$kind, name=$name, url=$url, username=$username)"

    internal fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind.name).put("name", name).put("url", url)
        .put("username", username).put("password", password)

    internal companion object {
        fun fromJson(o: JSONObject): Catalog? = runCatching {
            Catalog(
                id = o.getString("id"),
                kind = CatalogKind.valueOf(o.getString("kind")),
                name = o.getString("name"),
                url = o.getString("url"),
                username = o.optString("username"),
                password = o.optString("password"),
            )
        }.getOrNull()
    }
}

/** 添加书源时可以一键填上的：只预置公版书的书目，自建书库给出地址的样子。 */
object CatalogPresets {
    val GUTENBERG = Catalog(id = "", kind = CatalogKind.OPDS, name = "古登堡计划", url = "https://www.gutenberg.org/ebooks.opds/")

    /** 地址框下的提示：常见书库的 OPDS / WebDAV 地址长什么样。 */
    fun hint(kind: CatalogKind): String = when (kind) {
        CatalogKind.OPDS -> "Calibre-web：http://地址:8083/opds\nKomga：http://地址:25600/opds/v1.2/catalog\nKavita：在 Kavita 的用户设置里复制 OPDS 地址（带密钥，不用填密码）"
        CatalogKind.WEBDAV -> "Nextcloud：https://地址/remote.php/dav/files/用户名/\n坚果云：https://dav.jianguoyun.com/dav/（密码填“应用密码”）\nAlist：http://地址:5244/dav/"
    }
}

/**
 * 书源单独存一个文件（catalogs.xml）：里面有密码，备份规则把它排除在系统备份之外，灵阅自己的备份也不含它。
 */
class CatalogStore(context: Context) {
    private val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): List<Catalog> {
        val raw = sp.getString("list", null) ?: return emptyList()
        val a = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Catalog::fromJson) }
    }

    fun save(list: List<Catalog>) {
        sp.edit { putString("list", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()) }
    }

    companion object {
        /** 备份规则（res/xml/backup_rules.xml、data_extraction_rules.xml）按这个文件名排除。 */
        const val FILE = "catalogs"
    }
}
