package com.xycs.lingyuereader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
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
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
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
                        footer = "开启后，划选文字、查词和阅读菜单里会出现 AI 功能：解释选文、AI 释义、前情提要、问这本书。" +
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
                                    subtitle = if (p.id == AiPresets.CUSTOM) "任何兼容 OpenAI 或 Anthropic 接口的服务" else p.model,
                                ) {
                                    update(config.withPreset(p))
                                    result = null
                                }
                            }
                        }
                    }
                    val custom = config.preset == AiPresets.CUSTOM
                    val preset = AiPresets.of(config.preset)
                    GroupedSection(
                        header = "接口",
                        footer = when {
                            config.baseUrl.trim().startsWith("http://") -> "只支持 https:// 开头的地址：密钥不能明文传输。"
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
                                enabled = custom,
                                colors = fieldColors,
                                textStyle = mono.copy(color = if (custom) c.label else c.secondary),
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
                                label = "API 密钥",
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
                    if (config.enabled && !config.complete) {
                        Text(
                            "填好接口地址、模型和密钥后，AI 功能才会出现在阅读界面里。",
                            color = c.destructive,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 28.dp),
                        )
                    }
                }
            }
        }
    }
}
