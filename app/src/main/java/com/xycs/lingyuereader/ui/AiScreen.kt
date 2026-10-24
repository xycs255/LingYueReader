package com.xycs.lingyuereader.ui

import androidx.compose.foundation.clickable
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
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiException
import com.xycs.lingyuereader.ai.AiPresets
import com.xycs.lingyuereader.ai.AiProtocol
import com.xycs.lingyuereader.ai.AnswerLength
import com.xycs.lingyuereader.ai.QuickAsk
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
                            ToggleRow("启用 AI 助手", config.enabled, icon = LingYueIcons.Sparkle, iconBackground = Color(0xFFAF52DE)) { on ->
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
                                        else -> p.model
                                    },
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
                            row { SegmentedRow(AiProtocol.entries.map { it to it.label }, config.protocol) { update(config.copy(protocol = it)) } }
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
                    GroupedSection(footer = result?.second) {
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
                    }
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
                        footer = "填了向量模型，问这本书时会先用它给读过的部分建索引（按书存在本机，读到新内容再补），找原文更准；" +
                            "要服务商有 embeddings 接口，例如通义千问 text-embedding-v4、智谱 embedding-3、Ollama 的 nomic-embed-text。留空只按关键词找。",
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
                    if (config.enabled && !config.complete) {
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
