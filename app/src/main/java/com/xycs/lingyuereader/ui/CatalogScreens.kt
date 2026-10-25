package com.xycs.lingyuereader.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.remote.Catalog
import com.xycs.lingyuereader.remote.CatalogKind
import com.xycs.lingyuereader.remote.CatalogPresets
import com.xycs.lingyuereader.remote.RemoteFile
import com.xycs.lingyuereader.remote.RemoteItem
import com.xycs.lingyuereader.remote.RemotePage
import com.xycs.lingyuereader.remote.sameCatalogOrigin
import com.xycs.lingyuereader.ui.glass.GlassDialog
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.GlassSheet
import com.xycs.lingyuereader.ui.glass.glassBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.utils.overScrollVertical

private val OpdsColor = Color(0xFFFF9500)
private val DavColor = Color(0xFF007AFF)

private fun CatalogKind.icon() = if (this == CatalogKind.OPDS) LingYueIcons.Book else LingYueIcons.Folder
private fun CatalogKind.color() = if (this == CatalogKind.OPDS) OpdsColor else DavColor

/** 设置页共用的顶栏：返回、标题、右边一个文字按钮。 */
@Composable
private fun PageBar(title: String, onBack: () -> Unit, action: Pair<String, () -> Unit>? = null, actionEnabled: Boolean = true, icon: (@Composable () -> Unit)? = null) {
    val c = LocalGroupedColors.current
    SmallTopAppBar(
        color = Color.Transparent,
        modifier = Modifier.glassBar(),
        title = title,
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.padding(start = 12.dp)) {
                Icon(LingYueIcons.Back, contentDescription = "返回")
            }
        },
        actions = {
            icon?.invoke()
            if (action != null) {
                Text(
                    action.first,
                    color = if (actionEnabled) c.tint else c.tertiary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = actionEnabled, role = Role.Button, onClick = action.second)
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                )
            }
        },
    )
}

/**
 * 书源列表：每个书源一行，点进去浏览；右上角“+”添加。只连读者自己添加的地址。
 */
@Composable
fun CatalogsScreen(vm: AppViewModel) {
    val catalogs by vm.catalogs.collectAsStateWithLifecycle()
    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                PageBar("书源", onBack = { vm.back() }, icon = {
                    IconButton(onClick = { vm.open(Screen.CatalogEdit(null)) }, modifier = Modifier.padding(end = 12.dp)) {
                        Icon(LingYueIcons.Add, contentDescription = "添加书源")
                    }
                })
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                overscrollEffect = null,
            ) {
                item {
                    if (catalogs.isEmpty()) {
                        EmptyState(
                            LingYueIcons.Folder,
                            "还没有书源",
                            "添加 OPDS 书目（Calibre-web、Komga、Kavita 等书库）或 WebDAV 文件夹（NAS、Nextcloud、坚果云等），在灵阅里浏览、下载书。",
                            action = "添加书源" to { vm.open(Screen.CatalogEdit(null)) },
                        )
                    } else {
                        GroupedSection(
                            footer = "点书源浏览，点“编辑”改地址和账号。来自书源的书，在书架上长按选“刷新”可以检查更新。",
                        ) {
                            catalogs.forEach { cat ->
                                iconRow {
                                    CatalogRow(cat, onEdit = { vm.open(Screen.CatalogEdit(cat.id)) }) { vm.open(Screen.CatalogBrowse(cat.id)) }
                                }
                            }
                        }
                    }
                    if (catalogs.none { it.url.trim() == CatalogPresets.GUTENBERG.url }) {
                        GroupedSection(header = "公版书", footer = "古登堡计划：七万多本版权已过期的书，以英文为主，可以直接下载 EPUB。") {
                            iconRow {
                                NavRow("添加古登堡计划", icon = LingYueIcons.Add, iconBackground = Color(0xFF34C759)) {
                                    vm.saveCatalog(CatalogPresets.GUTENBERG.copy(id = java.util.UUID.randomUUID().toString()))
                                }
                            }
                        }
                    }
                    GroupedSection(footer = "灵阅只连你添加的地址；账号密码只存在这台手机上，不进备份。不提供、也不推荐任何书的来源。") {}
                }
            }
        }
    }
}

/** 一个书源：图标、名字、地址；右边“编辑”。 */
@Composable
private fun CatalogRow(cat: Catalog, onEdit: () -> Unit, onOpen: () -> Unit) {
    val c = LocalGroupedColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onOpen).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(cat.kind.icon(), cat.kind.color())
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(cat.name, color = c.label, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${if (cat.kind == CatalogKind.OPDS) "OPDS" else "WebDAV"} · ${cat.url.trim().removePrefix("https://").removePrefix("http://")}",
                color = c.secondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "编辑",
            color = c.tint,
            fontSize = 15.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onEdit).padding(horizontal = 12.dp, vertical = 14.dp),
        )
    }
}

/** 添加或编辑书源：类型、名称、地址、账号；可以先测试连接再保存。 */
@Composable
fun CatalogEditScreen(vm: AppViewModel, catalogId: String?) {
    val catalogs by vm.catalogs.collectAsStateWithLifecycle()
    val existing = remember(catalogId) { catalogs.firstOrNull { it.id == catalogId } }
    // 没保存的输入旋转屏幕后还在。
    var kind by rememberSaveable { mutableStateOf(existing?.kind ?: CatalogKind.OPDS) }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var url by rememberSaveable { mutableStateOf(existing?.url.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(existing?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(existing?.password.orEmpty()) }
    var showPassword by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val draft = Catalog(
        id = existing?.id ?: rememberSaveable { java.util.UUID.randomUUID().toString() },
        kind = kind,
        name = name.trim().ifEmpty { url.trim().removePrefix("https://").removePrefix("http://").substringBefore('/') },
        url = url.trim(),
        username = username.trim(),
        password = password,
    )
    val valid = Regex("^https?://[^/\\s]+.*", RegexOption.IGNORE_CASE).matches(draft.url)

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                PageBar(if (existing == null) "添加书源" else "编辑书源", onBack = { vm.back() }, action = "保存" to {
                    vm.saveCatalog(draft)
                    vm.back()
                }, actionEnabled = valid)
            },
        ) { padding ->
            val fieldColors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent, labelColor = c.secondary, borderColor = Color.Transparent)
            val mono = androidx.compose.ui.text.TextStyle(color = c.label, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            ) {
                GroupedSection(header = "类型") {
                    row { SegmentedRow(CatalogKind.entries.map { it to it.label }, kind) { kind = it; result = null } }
                }
                GroupedSection(
                    header = "地址",
                    footer = if (draft.insecure) "这是 http:// 地址，用户名和密码会明文传输，只建议在自己家的局域网里用。" else CatalogPresets.hint(kind),
                ) {
                    row {
                        TextField(value = name, onValueChange = { name = it }, label = "名称（可不填）", singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth())
                    }
                    row {
                        TextField(
                            value = url,
                            onValueChange = { url = it; result = null },
                            label = if (kind == CatalogKind.OPDS) "OPDS 地址" else "WebDAV 地址",
                            singleLine = true,
                            colors = fieldColors,
                            textStyle = mono,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                GroupedSection(header = "账号", footer = "不需要登录的书目留空。密码只存在这台手机上，不进备份。") {
                    row {
                        TextField(value = username, onValueChange = { username = it; result = null }, label = "用户名", singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth())
                    }
                    row {
                        TextField(
                            value = password,
                            onValueChange = { password = it; result = null },
                            label = "密码",
                            singleLine = true,
                            colors = fieldColors,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            trailingIcon = {
                                if (password.isNotEmpty()) {
                                    Text(
                                        if (showPassword) "隐藏" else "显示",
                                        color = c.tint,
                                        fontSize = 14.sp,
                                        modifier = Modifier.padding(end = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { showPassword = !showPassword }.padding(horizontal = 8.dp, vertical = 6.dp),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                GroupedSection(footer = result) {
                    row {
                        ActionRow(if (testing != null) "正在连接……" else "测试连接", enabled = valid && testing == null) {
                            result = null
                            testing = scope.launch {
                                result = try {
                                    vm.testCatalog(draft)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    "连接失败：${e.message ?: e.javaClass.simpleName}"
                                } finally {
                                    testing = null
                                }
                            }
                        }
                    }
                }
                if (existing != null) {
                    GroupedSection {
                        row { ActionRow("删除书源", destructive = true) { confirmDelete = true } }
                    }
                }
            }
            if (confirmDelete && existing != null) {
                GlassDialog(show = true, title = "删除书源", summary = "删除“${existing.name}”？已经下载到书架的书不受影响。", onDismissRequest = { confirmDelete = false }) {
                    DialogButtons(confirm = "删除", onDismiss = { confirmDelete = false }) {
                        confirmDelete = false
                        vm.removeCatalog(existing.id)
                        vm.back()
                    }
                }
            }
        }
    }
}

/**
 * 浏览书源的一页。OPDS：分类和书，能搜索的书目顶上有搜索框，滚到底加载下一页；
 * WebDAV：子文件夹和书，顶上的框筛选当前文件夹。点书看简介和可下载的格式。
 */
@Composable
fun CatalogBrowseScreen(vm: AppViewModel, screen: Screen.CatalogBrowse) {
    val catalogs by vm.catalogs.collectAsStateWithLifecycle()
    val catalog = catalogs.firstOrNull { it.id == screen.catalogId }
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val downloading by vm.downloading.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val url = screen.url.ifEmpty { catalog?.url?.trim().orEmpty() }
    var page by remember(url) { mutableStateOf(catalog?.let { vm.cachedRemotePage(it.id, url) }) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<RemotePage?>(null) }
    var searching by remember { mutableStateOf(false) }
    // 翻页失败的原因：底部不再转圈，改成“点此重试”。
    var moreError by remember { mutableStateOf<String?>(null) }
    var moreRetry by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<RemoteItem.Book?>(null) }
    val scope = rememberCoroutineScope()

    // 进页面时先用缓存；点“刷新”（reload 加一）时不用缓存，重新从书源取。
    LaunchedEffect(url, reload) {
        if (catalog == null || (page != null && reload == 0)) return@LaunchedEffect
        error = null
        refreshing = true
        val failed = try {
            page = vm.remotePage(catalog, url, fresh = reload > 0)
            moreError = null
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "打不开"
        } finally {
            refreshing = false
        }
        // 已经显示着内容时刷新失败：留着原来的内容，提示一下。
        if (failed != null) {
            if (page != null) snackbar.showSnackbar("刷新失败：$failed") else error = failed
        }
    }
    LaunchedEffect(message) {
        message?.let {
            vm.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                PageBar(
                    screen.title.ifEmpty { page?.title ?: catalog?.name ?: "书源" },
                    onBack = { vm.back() },
                    action = ("刷新" to { reload += 1 }).takeIf { catalog != null },
                    actionEnabled = !refreshing,
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            val current = page
            when {
                catalog == null -> EmptyState(LingYueIcons.Folder, "书源已删除", null, Modifier.padding(padding))
                current == null && error != null -> EmptyState(
                    LingYueIcons.Info,
                    "打不开这个${if (catalog.kind == CatalogKind.OPDS) "书目" else "文件夹"}",
                    error,
                    Modifier.padding(top = padding.calculateTopPadding()),
                    action = "重试" to { reload++ },
                )
                current == null -> Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding() + 64.dp), contentAlignment = Alignment.TopCenter) {
                    InfiniteProgressIndicator(color = c.tint)
                }
                else -> {
                    val dav = catalog.kind == CatalogKind.WEBDAV
                    val canSearch = dav || current.searchTemplate != null || current.searchDescription != null
                    val shown = results ?: current
                    val items = if (dav && query.isNotBlank()) shown.items.filter { it.title.contains(query.trim(), ignoreCase = true) } else shown.items
                    val listState = rememberLazyListState()
                    // 滚到底加载 OPDS 的下一页。
                    val atEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
                    // 同一组参数只取一次。不另记“正在取”：这一段离开组合再回来时（比如换页动画），
                    // 旧的那次被取消，“正在取”却留着，新的一次就不再取，底部一直转圈。
                    LaunchedEffect(atEnd, shown.next, moreRetry) {
                        if (!atEnd || shown.next == null || moreError != null || results != null) return@LaunchedEffect
                        try {
                            val more = vm.remoteMore(catalog, url, current)
                            // 这期间点了刷新：不拿旧的一页接在新内容上。
                            if (page === current) page = more
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            moreError = e.message ?: "打不开"
                        }
                    }
                    LazyColumn(
                        Modifier.fillMaxSize().overScrollVertical(),
                        state = listState,
                        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                        overscrollEffect = null,
                    ) {
                        if (canSearch) {
                            item {
                                SearchField(
                                    query = query,
                                    hint = if (dav) "筛选这个文件夹" else "搜索书目",
                                    busy = searching,
                                    onChange = {
                                        query = it
                                        if (it.isBlank()) results = null
                                    },
                                    onSubmit = {
                                        if (!dav && query.isNotBlank()) {
                                            searching = true
                                            scope.launch {
                                                try {
                                                    results = vm.remoteSearch(current, query)
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    snackbar.showSnackbar("搜索失败：${e.message}")
                                                } finally {
                                                    searching = false
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        if (items.isEmpty()) {
                            item {
                                EmptyState(
                                    if (dav) LingYueIcons.Folder else LingYueIcons.Book,
                                    when {
                                        results != null -> "没有搜到“${query.trim()}”"
                                        dav && query.isNotBlank() -> "这个文件夹里没有“${query.trim()}”"
                                        dav -> "这个文件夹里没有灵阅能读的书"
                                        else -> "这里还没有内容"
                                    },
                                    if (dav && query.isBlank()) "灵阅能读 TXT、EPUB、MOBI / AZW3、FB2、PDF、CBZ 和 Markdown。" else null,
                                )
                            }
                        }
                        // 书源返回的条目可能重复（同一个链接出现两次、翻页时目录有变动）：重复的 key 会让列表崩溃，只留第一条。
                        items(items.distinctBy { it.key }, key = { it.key }) { item ->
                            when (item) {
                                is RemoteItem.Folder -> FolderRow(item, dav) {
                                    vm.open(Screen.CatalogBrowse(catalog.id, item.url, item.title))
                                }
                                is RemoteItem.Book -> {
                                    val file = item.files.first()
                                    BookRow(
                                        item,
                                        vm,
                                        catalog.url,
                                        inShelf = item.files.any { f -> shelf.any { e -> e.sources.any { it.uri == f.url } } },
                                        downloading = item.files.any { it.url == downloading },
                                        onOpen = { detail = item },
                                        onDownload = { vm.downloadRemote(file, item.title.takeIf { !dav }) },
                                    )
                                }
                            }
                        }
                        if (shown.next != null && results == null) {
                            item {
                                if (moreError != null) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable(role = Role.Button) {
                                                moreError = null
                                                moreRetry++
                                            }
                                            .padding(20.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("加载失败，点此重试", color = c.tint, fontSize = 15.sp)
                                    }
                                } else {
                                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { InfiniteProgressIndicator(color = c.tint) }
                                }
                            }
                        }
                    }
                }
            }
            // 书的详情：封面、作者、简介、各个格式的下载按钮。
            var last by remember { mutableStateOf<RemoteItem.Book?>(null) }
            detail?.let { last = it }
            GlassSheet(show = detail != null, title = null, onDismissRequest = { detail = null }) {
                last?.let { book ->
                    BookDetail(
                        book,
                        vm,
                        catalog?.url.orEmpty(),
                        shelfUrls = shelf.flatMap { e -> e.sources.mapNotNull { it.uri } }.toSet(),
                        downloading = downloading,
                        onDownload = { f -> vm.downloadRemote(f, book.title.takeIf { catalog?.kind == CatalogKind.OPDS }) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, hint: String, busy: Boolean, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    val c = LocalGroupedColors.current
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.cell).padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LingYueIcons.Search, contentDescription = null, tint = c.secondary, modifier = Modifier.size(18.dp))
        TextField(
            value = query,
            onValueChange = onChange,
            label = hint,
            useLabelAsPlaceholder = true,
            singleLine = true,
            colors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent, labelColor = c.secondary, borderColor = Color.Transparent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier.weight(1f),
        )
        if (busy) InfiniteProgressIndicator(size = 16.dp, color = c.tint)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun FolderRow(item: RemoteItem.Folder, dav: Boolean, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.cell)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (dav) LingYueIcons.Folder else LingYueIcons.ListView, contentDescription = null, tint = c.tint, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.title, color = c.label, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.subtitle?.let { Text(it, color = c.secondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.size(16.dp))
    }
}

/** 一本书：封面、书名、作者、简介或大小；右边下载钮。 */
@Composable
private fun BookRow(item: RemoteItem.Book, vm: AppViewModel, catalogUrl: String, inShelf: Boolean, downloading: Boolean, onOpen: () -> Unit, onDownload: () -> Unit) {
    val c = LocalGroupedColors.current
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.cell)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(item, vm, catalogUrl, Modifier.width(48.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, color = c.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.author?.let { Text(it, color = c.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val meta = details(item)
            if (meta.isNotEmpty()) Text(meta, color = c.tertiary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DownloadPill(inShelf, downloading, onClick = onDownload)
    }
}

/** 格式、大小、修改时间。 */
private fun details(item: RemoteItem.Book): String = listOfNotNull(
    item.files.joinToString(" / ") { it.label },
    item.size?.let(::sizeLabel),
    item.modified?.let { java.text.SimpleDateFormat("yyyy-M-d", java.util.Locale.CHINA).format(java.util.Date(it)) },
).joinToString(" · ")

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private enum class DownloadStage { READY, BUSY, SHELVED }

/** 下载箭头落成等待环、再变成勾；状态靠图形变化读懂，读屏仍能获得完整说明。 */
@Composable
internal fun DownloadPill(inShelf: Boolean, downloading: Boolean, label: String = "下载", onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    val stage = when {
        inShelf -> DownloadStage.SHELVED
        downloading -> DownloadStage.BUSY
        else -> DownloadStage.READY
    }
    val background by animateColorAsState(
        if (stage == DownloadStage.SHELVED) c.secondary.copy(alpha = 0.09f) else c.tint.copy(alpha = 0.12f),
        Motion.standard(Motion.SHORT), label = "downloadBackground",
    )
    Box(
        Modifier
            .widthIn(min = 96.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(background)
            .clickable(enabled = stage == DownloadStage.READY, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = when (stage) {
                    DownloadStage.READY -> label
                    DownloadStage.BUSY -> "正在下载"
                    DownloadStage.SHELVED -> "已在书架"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(stage, transitionSpec = {
            if (Effects.noMotion) (EnterTransition.None togetherWith ExitTransition.None).using(null)
            else (fadeIn(Motion.standard(Motion.SHORT)) + slideInVertically(
                animationSpec = Motion.standard(Motion.SHORT), initialOffsetY = { -it / 3 },
            )) togetherWith fadeOut(Motion.exit(Motion.SHORT))
        }, label = "downloadStage") { shown ->
            val tint = if (shown == DownloadStage.SHELVED) c.secondary else c.tint
            Row(
                Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                when (shown) {
                    DownloadStage.READY -> Icon(LingYueIcons.Download, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                    DownloadStage.BUSY -> if (Effects.noMotion) {
                        Icon(LingYueIcons.Timer, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                    } else InfiniteProgressIndicator(size = 18.dp, color = tint)
                    DownloadStage.SHELVED -> Icon(LingYueIcons.Check, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                }
                Text(
                    when (shown) {
                        DownloadStage.READY -> label
                        DownloadStage.BUSY -> "下载中"
                        DownloadStage.SHELVED -> "已入书架"
                    },
                    color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** 封面：有缩略图地址就取，没有或取不到用生成的书皮。 */
@Composable
private fun Cover(item: RemoteItem.Book, vm: AppViewModel, catalogUrl: String, modifier: Modifier) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item.cover, catalogUrl) {
        value = item.cover?.takeIf { sameCatalogOrigin(catalogUrl, it) }?.let { vm.remoteCover(it) }
    }
    Box(modifier) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height((48 / 0.7f).dp).clip(RoundedCornerShape(4.dp)),
            )
        } else {
            BookCover(item.title, item.author.orEmpty(), compact = true)
        }
    }
}

/** 书的详情面板：封面、书名、作者、简介，每个格式一个下载按钮。 */
@Composable
private fun BookDetail(book: RemoteItem.Book, vm: AppViewModel, catalogUrl: String, shelfUrls: Set<String>, downloading: String?, onDownload: (RemoteFile) -> Unit) {
    val c = LocalGroupedColors.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Cover(book, vm, catalogUrl, Modifier.width(76.dp))
            Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(book.title, color = c.label, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp)
                book.author?.let { Text(it, color = c.secondary, fontSize = 14.sp) }
                val meta = details(book)
                if (meta.isNotEmpty()) Text(meta, color = c.tertiary, fontSize = 12.sp)
            }
        }
        if (book.cover != null && !sameCatalogOrigin(catalogUrl, book.cover)) {
            Text("这本书的封面来自其他网站，已默认隐藏。", color = c.secondary, fontSize = 12.sp)
        }
        book.summary?.let { Text(it, color = c.label, fontSize = 15.sp, lineHeight = 23.sp) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            book.files.forEachIndexed { i, f ->
                val inShelf = f.url in shelfUrls
                val busy = f.url == downloading
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (i == 0 && !inShelf) c.tint else c.cell)
                        .clickable(enabled = !inShelf && !busy, role = Role.Button) { onDownload(f) }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (busy) InfiniteProgressIndicator(size = 16.dp, color = if (i == 0) Color.White else c.tint, modifier = Modifier.padding(end = 8.dp))
                    Text(
                        when {
                            inShelf -> "${f.label} 已在书架"
                            busy -> "正在下载 ${f.label}"
                            else -> "下载 ${f.label}"
                        },
                        color = if (i == 0 && !inShelf) Color.White else if (inShelf) c.secondary else c.tint,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
