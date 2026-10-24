# 本地 AI（计划中）

> 状态：待做。本文记录已经讨论定下的方案，动工时以此为准，按实际情况再调整。
>
> 联网版的 AI 助手已经做了（`ai/` 目录，用读者自己的服务商和密钥），四个功能和界面都在；
> 本地版只需把 `AiClient` 换成本机推理，提示词（`AiPrompts`）和检索（`BookIndex`）可以沿用。

## 目标

在手机上离线运行一个小型语言模型，帮助理解正在读的书。本地版不联网，模型不随安装包分发。

## 技术方案

- **推理引擎**：[llama.cpp](https://github.com/ggml-org/llama.cpp)（MIT 许可），通过 JNI 调用，只编译 arm64-v8a，安装包约增加 5 MB；CI 需要 NDK 与 CMake。
- **模型**：用户自行导入 GGUF 文件（例如在魔搭 ModelScope 下载 Qwen3 的 GGUF）。通过系统文件选择器取得长期读取授权，按文件描述符
  （`/proc/self/fd/N`）直接以 mmap 读取，不复制进应用目录，省下 1–3 GB 空间。
- **推荐模型**（4-bit 量化；速度为骁龙 8 Gen 3 上的公开实测，仅供参考）：

  | 模型 | 大小 | 速度 | 说明 |
  | --- | --- | --- | --- |
  | Qwen3-1.7B | 约 1.1 GB | 约 31 tok/s | 默认推荐，8 GB 内存可用 |
  | Qwen3-4B | 约 2.5 GB | 约 10 tok/s | 12 GB 内存旗舰机，质量更好 |
  | Qwen3-0.6B | 约 0.4 GB | 约 73 tok/s | 低内存机型，只适合简单解释 |

- **未采用的方案**：Google LiteRT-LM（接入简单、有 GPU 加速，但可选中文模型少、国内下载不便）；
  ML Kit 的 Gemini Nano（依赖 Google 服务，国行手机基本不可用）。

## 功能（只用读者已经读到的内容，避免剧透）

1. **问 AI**：划选后操作条上的“问 AI”，解释文言、典故、方言、网络用语，或翻译外文。
2. **查词补中文释义**：内置 CC-CEDICT 只有英文释义，查词时可让 AI 结合原句给出中文解释。
3. **前情提要**：隔几天再打开时，概括最近读过的几章；后台生成并缓存。
4. **问这本书**：先在已读部分做关键词检索（BM25，按段落切块），再把相关段落交给模型作答，并注明出处章节。
5. **人物表**：从已读章节逐步整理人物与关系。

## 已知局限

- 小模型会出错：每个回答都附上依据的原文段落，方便核对。
- 概括整章较慢（中端机几十秒）且发热：放到后台、结果缓存。
- 6 GB 以下内存的手机只建议用 0.6B 模型。

## 参考

- pocket-llama：llama.cpp 在 Android 上的实测 <https://github.com/RomanQuintero/pocket-llama>
- LiteRT-LM <https://ai.google.dev/edge/litert-lm/overview>
- ML Kit GenAI Prompt API <https://developer.android.com/blog/posts/ml-kit-s-prompt-api-unlock-custom-on-device-gemini-nano-experiences>
