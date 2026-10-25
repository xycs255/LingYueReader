package com.xycs.lingyuereader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.DeviceModelState
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiException
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.ai.AiProtocol
import com.xycs.lingyuereader.ai.AnswerLength
import com.xycs.lingyuereader.ai.DeviceModels
import com.xycs.lingyuereader.ai.QuickAsk
import com.xycs.lingyuereader.ai.SpeechConfig
import com.xycs.lingyuereader.ai.SpeechPresets
import com.xycs.lingyuereader.ui.glass.GlassDialog
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * AI 助手设置：总开关、服务商（选了自动填地址和模型）、密钥、测试连接。
 * 关着时灵阅不联网；开了也只在读者点 AI 功能时才发请求。
 */
@Composable
fun AiScreen(vm: AppViewModel) {
    val saved by vm.aiConfig.collectAsStateWithLifecycle()
    // 边改边存：离开页面不用点保存。
    val config = saved
    val update: (AiConfig) -> Unit = vm::saveAiConfig
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var showKey by remember { mutableStateOf(false) }
    // 正在编辑第几个快捷问题；等于列表长度时是新加的。
    var editing by remember { mutableStateOf<Int?>(null) }
    val device by vm.deviceModels.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshDeviceModels() }
    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importDeviceModel(it) } }

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                SmallTopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = "AI 助手",
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 12.dp)) {
                            Icon(LingYueIcons.Back, contentDescription = "返回")
                        }
                    },
                )
            },
        ) { padding ->
            val fieldColors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent, labelColor = c.secondary, borderColor = Color.Transparent)
            val mono = androidx.compose.ui.text.TextStyle(color = c.label, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                overscrollEffect = null,
            ) {
                item {
                    GroupedSection(
                        footer = "开启后，划选文字、查词、生词本和阅读菜单里会出现 AI 功能：解释选文、AI 释义、前情提要、问这本书、人物表、读书笔记、整章翻译。" +
                            "只有你点这些功能时才联网，把选中的文字（前情提要和问这本书是读过部分里的相关原文）发给下面的服务商，不发送后面没读的内容。关闭时灵阅不联网。",
                    ) {
                        row {
                            ToggleRow("启用 AI 助手", config.enabled, icon = LingYueIcons.Sparkle, iconBackground = RowIconColors.Ai) { on ->
                                update(config.copy(enabled = on))
                            }
                        }
                    }
                    GroupedSection(header = "服务商", footer = "用你自己的账号和密钥，费用按服务商的价格计。模型名可以改成同一家的其他模型。") {
                        AiPresets.ALL.forEach { p ->
                            row {
                                CheckRow(
                                    p.label,
                                    config.preset == p.id,
                                    subtitle = when (p.id) {
                                        AiPresets.CUSTOM -> "任何兼容 OpenAI 或 Anthropic 接口的服务"
                                        AiPresets.LOCAL -> "家里电脑上的 Ollama、LM Studio 等"
                                        AiPresets.DEVICE -> if (DeviceModels.supported) "在这台手机上算，不联网" else "这台手机用不了（要 64 位的手机）"
                                        else -> p.model
                                    },
                                    enabled = p.id != AiPresets.DEVICE || DeviceModels.supported,
                                ) {
                                    update(config.withPreset(p))
                                    result = null
                                }
                            }
                        }
                    }
                    val custom = config.preset == AiPresets.CUSTOM
                    val editableUrl = custom || config.preset == AiPresets.LOCAL
                    val preset = AiPresets.of(config.preset)
                    // 选不选本机模型换的是一整组（本机模型 / 接口）：一组收起、一组展开，下面的卡片平滑挪过去，不一下跳开。
                    AnimatedVisibility(config.onDevice, enter = sectionEnter(), exit = sectionExit()) {
                        Column { DeviceModelSection(config, device, vm, importModel = { importModel.launch(arrayOf("*/*")) }) { update(config.copy(model = it)) } }
                    }
                    AnimatedVisibility(!config.onDevice, enter = sectionEnter(), exit = sectionExit()) {
                        GroupedSection(
                            header = "接口",
                            footer = when {
                                config.baseUrl.trim().startsWith("http://") && !config.lan -> "公网地址只支持 https://：密钥不能明文传输。局域网地址（192.168.x.x、10.x.x.x、xxx.local 等）可以用 http://。"
                                config.preset == AiPresets.LOCAL -> "填电脑在局域网里的地址：Ollama 默认 http://电脑IP:11434/v1，LM Studio 默认 http://电脑IP:1234/v1。电脑上要让服务监听局域网（Ollama 设 OLLAMA_HOST=0.0.0.0）。密钥可不填。"
                                config.lan -> "局域网地址，密钥可不填。"
                                preset.keySite.isNotEmpty() -> "密钥在 ${preset.keySite} 申请。密钥只存在这台手机上，不进备份、不写日志。"
                                else -> "密钥只存在这台手机上，不进备份、不写日志。"
                            },
                        ) {
                            if (custom) {
                                row { SegmentedRow(AiProtocol.entries.filter { it != AiProtocol.DEVICE }.map { it to it.label }, config.protocol) { update(config.copy(protocol = it)) } }
                            }
                            row {
                                TextField(
                                    value = config.baseUrl,
                                    onValueChange = { update(config.copy(baseUrl = it.trim())) },
                                    label = "接口地址",
                                    singleLine = true,
                                    enabled = editableUrl,
                                    colors = fieldColors,
                                    textStyle = mono.copy(color = if (editableUrl) c.label else c.secondary),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            row {
                                TextField(
                                    value = config.model,
                                    onValueChange = { update(config.copy(model = it.trim())) },
                                    label = "模型",
                                    singleLine = true,
                                    colors = fieldColors,
                                    textStyle = mono,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            row {
                                TextField(
                                    value = config.apiKey,
                                    onValueChange = { update(config.withKey(it.trim())) },
                                    label = if (config.lan) "API 密钥（可不填）" else "API 密钥",
                                    singleLine = true,
                                    colors = fieldColors,
                                    textStyle = mono,
                                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                                    trailingIcon = {
                                        if (config.apiKey.isNotEmpty()) {
                                            Text(
                                                if (showKey) "隐藏" else "显示",
                                                color = c.tint,
                                                fontSize = 14.sp,
                                                modifier = Modifier.padding(end = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { showKey = !showKey }.padding(horizontal = 8.dp, vertical = 6.dp),
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    // 连接成功的说明写在卡片下面；失败单独一行红字写在卡片里。
                    GroupedSection(footer = result?.takeIf { it.first }?.second) {
                        row {
                            ActionRow(if (testing != null) "正在测试……" else "测试连接", enabled = config.complete && testing == null) {
                                result = null
                                testing = scope.launch {
                                    result = try {
                                        true to vm.testAi(config)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: AiException) {
                                        false to (e.message ?: "连接失败")
                                    } catch (e: Exception) {
                                        false to "连接失败：${e.message ?: e.javaClass.simpleName}"
                                    } finally {
                                        testing = null
                                    }
                                }
                            }
                        }
                        result?.takeIf { !it.first }?.let { (_, e) -> row { ErrorText(e, Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) } }
                    }
                    PriceSection(vm, config)
                    GroupedSection(header = "回答", footer = "所有 AI 回答的长短；查词的 AI 释义总是简短的。") {
                        row { SegmentedRow(AnswerLength.entries.map { it to it.label }, config.length) { update(config.copy(length = it)) } }
                    }
                    GroupedSection(
                        header = "划选后的快捷问题",
                        footer = "显示在“问 AI”面板上的按钮。点一个修改名字和要求，最多 ${QuickAsk.MAX} 个。",
                    ) {
                        config.asks.forEachIndexed { i, q ->
                            row { NavRow(q.label, subtitle = q.instruction) { editing = i } }
                        }
                        if (config.asks.size < QuickAsk.MAX) row { ActionRow("添加快捷问题") { editing = config.asks.size } }
                        if (config.asks != QuickAsk.DEFAULTS) row { ActionRow("恢复默认") { update(config.copy(asks = QuickAsk.DEFAULTS)) } }
                    }
                    GroupedSection(
                        header = "问这本书",
                        footer = "填写后，每次提问最多为关键词前 30 段和最近 10 段生成向量；首次最多发送 40 段（分 4 批）和 1 次问题向量请求，超出的段落不参与本次向量检索。" +
                            "服务商可能按请求或文字量收费；新段落按书缓存，后续只补缺失的向量。需要 embeddings 接口，例如通义千问 text-embedding-v4、智谱 embedding-3、Ollama 的 nomic-embed-text。留空仅按关键词找。",
                    ) {
                        row {
                            TextField(
                                value = config.embeddingModel,
                                onValueChange = { update(config.copy(embeddingModel = it.trim())) },
                                label = "向量模型（可不填）",
                                singleLine = true,
                                colors = fieldColors,
                                textStyle = mono,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    SpeechSection(config, update, fieldColors, mono, vm)
                    AnimatedVisibility(config.enabled && !config.complete, enter = sectionEnter(), exit = sectionExit()) {
                        Text(
                            "填好接口地址、模型和密钥（局域网模型可不填密钥）后，AI 功能才会出现在阅读界面里。",
                            color = c.destructive,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 28.dp),
                        )
                    }
                }
            }
            editing?.let { i ->
                val q = config.asks.getOrNull(i)
                var label by remember(i) { mutableStateOf(q?.label.orEmpty()) }
                var instruction by remember(i) { mutableStateOf(q?.instruction.orEmpty()) }
                GlassDialog(show = true, title = if (q == null) "添加快捷问题" else "修改快捷问题", onDismissRequest = { editing = null }) {
                    TextField(value = label, onValueChange = { label = it.take(8) }, label = "按钮上的字（如“讲人物”）", singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextField(
                        value = instruction,
                        onValueChange = { instruction = it },
                        label = "对 AI 的要求（如“讲讲这段里的人物关系”）",
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(
                            text = if (q == null) "取消" else "删除",
                            onClick = {
                                if (q != null) update(config.copy(asks = config.asks.filterIndexed { j, _ -> j != i }))
                                editing = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = "保存",
                            enabled = label.isNotBlank() && instruction.isNotBlank(),
                            onClick = {
                                val next = QuickAsk(label.trim(), instruction.trim())
                                update(config.copy(asks = if (q == null) config.asks + next else config.asks.mapIndexed { j, old -> if (j == i) next else old }))
                                editing = null
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
        }
    }
}

/** 开关、选项带出的一整组：高度从 0 长出来并淡入（标准缓动 [Motion.MEDIUM]），收起反过来；减少动效时直接出现、消失。 */
private fun sectionEnter() = Effects.enter(expandVertically(Motion.standard(), expandFrom = Alignment.Top) + fadeIn(Motion.standard(Motion.SHORT)))
private fun sectionExit() = Effects.exit(shrinkVertically(Motion.standard(), shrinkTowards = Alignment.Top) + fadeOut(Motion.exit()))

/**
 * AI 朗读：听书、讲书用的语音合成服务商（OpenAI 兼容的 /audio/speech）。
 * 选服务商、填密钥、选声音，可以试听；打开后听书默认用 AI 语音（听书面板里也能切回系统语音）。
 */
@Composable
private fun SpeechSection(
    config: AiConfig,
    update: (AiConfig) -> Unit,
    fieldColors: top.yukonga.miuix.kmp.basic.TextFieldColors,
    mono: androidx.compose.ui.text.TextStyle,
    vm: AppViewModel,
) {
    val c = LocalGroupedColors.current
    val sp = config.speech
    val preset = SpeechPresets.of(sp.preset)
    val editableUrl = sp.preset == SpeechPresets.CUSTOM || sp.preset == SpeechPresets.LOCAL
    var previewError by remember { mutableStateOf<String?>(null) }
    var showKey by remember { mutableStateOf(false) }
    val setSpeech: (SpeechConfig) -> Unit = { update(config.copy(speech = it)) }
    GroupedSection(
        header = "AI 朗读",
        footer = when {
            sp.baseUrl.trim().startsWith("http://") && !sp.lan -> "公网地址只支持 https://（局域网地址可以用 http://）。"
            sp.preset == SpeechPresets.LOCAL -> "局域网里的 Kokoro-FastAPI 等兼容 OpenAI 语音接口的服务，默认端口 8880。密钥可不填。"
            preset.keySite.isNotEmpty() -> "听书、讲书用的语音。密钥在 ${preset.keySite} 申请，按字数计费；读过的句子缓存在本机。"
            else -> "听书、讲书用的语音：任何兼容 OpenAI /audio/speech 接口的服务。"
        },
    ) {
        // 这张卡片里其余几行（服务商、地址、密钥）都没有图标，开关这行也不带，同一张卡片要么都有要么都没有。
        row {
            ToggleRow("听书用 AI 语音", sp.enabled) {
                setSpeech(sp.copy(enabled = it))
                // 和离线语音只能用一样。
                if (it) vm.saveOfflineConfig(vm.offlineVoiceState.value.config.copy(enabled = false))
            }
        }
        row { PickerRow("服务商", SpeechPresets.ALL.map { it.id to it.label }, sp.preset) { id -> setSpeech(sp.withPreset(SpeechPresets.of(id))) } }
        row {
            TextField(
                value = sp.baseUrl,
                onValueChange = { setSpeech(sp.copy(baseUrl = it.trim())) },
                label = "接口地址",
                singleLine = true,
                enabled = editableUrl,
                colors = fieldColors,
                textStyle = mono.copy(color = if (editableUrl) c.label else c.secondary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        row { TextField(value = sp.model, onValueChange = { setSpeech(sp.copy(model = it.trim())) }, label = "模型", singleLine = true, colors = fieldColors, textStyle = mono, modifier = Modifier.fillMaxWidth()) }
        row {
            TextField(
                value = sp.apiKey,
                onValueChange = { setSpeech(sp.withKey(it.trim())) },
                label = if (sp.lan) "API 密钥（可不填）" else "API 密钥",
                singleLine = true,
                colors = fieldColors,
                textStyle = mono,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    if (sp.apiKey.isNotEmpty()) {
                        Text(
                            if (showKey) "隐藏" else "显示",
                            color = c.tint,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(end = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { showKey = !showKey }.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (preset.voices.isNotEmpty()) {
            row { PickerRow("声音", preset.voices, sp.voice) { setSpeech(sp.copy(voice = it)) } }
        } else {
            row { TextField(value = sp.voice, onValueChange = { setSpeech(sp.copy(voice = it.trim())) }, label = "声音", singleLine = true, colors = fieldColors, textStyle = mono, modifier = Modifier.fillMaxWidth()) }
        }
        if (preset.instructions) {
            row {
                TextField(
                    value = sp.instructions,
                    onValueChange = { setSpeech(sp.copy(instructions = it)) },
                    label = "朗读风格（可不填，如“像讲故事一样，语气温和”）",
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        row {
            ActionRow("试听", enabled = sp.complete) {
                previewError = null
                vm.previewSpeech { previewError = "试听失败：$it" }
            }
        }
        // 出错单独一行写在卡片里，不顶掉下面的说明。
        previewError?.let { e -> row { ErrorText(e, Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) } }
    }
}

/**
 * 本机模型：可下载的模型（大小、说明、下载进度）、导入的模型、选用哪个。只在读者点了下载时联网。
 */
@Composable
private fun DeviceModelSection(config: AiConfig, state: DeviceModelState, vm: AppViewModel, importModel: () -> Unit, select: (String) -> Unit) {
    val c = LocalGroupedColors.current
    val task = state.task
    // 要删的模型：id 和名字。
    var deleting by remember { mutableStateOf<Pair<String, String>?>(null) }
    GroupedSection(
        header = "本机模型",
        footer = ("在这台手机上算，不联网（下载模型时除外），也不花钱。比联网的大模型慢、写得差一些，手机会发热；" +
            "上下文短，写作 AI 会少带一些设定资料。模型是 Qwen2.5（Apache-2.0 许可），从 Hugging Face 下载；也可以用电脑下好 .task 文件再导入。"),
    ) {
        DeviceModels.ALL.forEach { m ->
            val installed = m.id in state.installed
            val busy = task?.model == m.id
            row {
                CheckRow(
                    m.label,
                    selected = installed && config.model == m.id,
                    subtitle = when {
                        busy -> "${task!!.phase.label} " + if (task.total > 0) "${task.done * 100 / task.total}%" else ""
                        installed -> "已下载 · " + m.note
                        else -> m.note
                    },
                    enabled = installed,
                ) { select(m.id) }
            }
            row {
                when {
                    busy -> ActionRow("取消下载", destructive = true) { vm.cancelDeviceTask() }
                    installed -> ActionRow("删除「${m.label}」", destructive = true) { deleting = m.id to m.label }
                    else -> ActionRow("下载「${m.label}」（约 ${m.bytes / 1_000_000} MB）", enabled = task == null) { vm.downloadDeviceModel(m) }
                }
            }
        }
        state.installed.filter { it.startsWith(DeviceModels.FILE) }.forEach { id ->
            row {
                CheckRow(DeviceModels.label(id), selected = config.model == id, subtitle = "导入的模型 · ${(state.sizes[id] ?: 0) / 1_000_000} MB") { select(id) }
            }
            row { ActionRow("删除「${DeviceModels.label(id)}」", destructive = true) { deleting = id to DeviceModels.label(id) } }
        }
        row {
            if (task != null && task.model == null) ActionRow("取消导入（${task.done / 1_000_000} MB）", destructive = true) { vm.cancelDeviceTask() }
            else ActionRow("从文件导入 .task 模型", enabled = task == null) { importModel() }
        }
        // 出错单独一行写在卡片里，不替换下面的说明。
        state.error?.let { e -> row { ErrorText(e, Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) } }
    }
    // 删掉的模型要重新下载（几百 MB 到 1 GB 多）或重新导入：先确认，确认键用红色。
    deleting?.let { (id, label) ->
        GlassDialog(
            show = true,
            title = "删除模型",
            summary = if (id.startsWith(DeviceModels.FILE)) "删除「$label」？要用时得重新从文件导入。" else "删除「$label」？要用时得重新下载。",
            onDismissRequest = { deleting = null },
        ) {
            DialogButtons(
                onDismiss = { deleting = null },
                onConfirm = {
                    vm.deleteDeviceModel(id)
                    deleting = null
                },
                confirmText = "删除",
                destructive = true,
            )
        }
    }
    if (config.model.isBlank()) Text("先下载或导入一个模型。", color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(start = 28.dp, bottom = 12.dp))
}
