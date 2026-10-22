# 灵阅 LingYue Reader

一个开源、离线、无广告的 Android 阅读器，专注中文长篇阅读。

## 功能

- **多种格式**：TXT（自动识别 UTF-8 / GBK 编码与“第X章”“楔子”“番外”等章节）、EPUB（按书脊与目录分章）、
  Markdown（标题、引用、列表、表格都能排版）。
- **合集导入**：一次选多个文件可以合成一本书。文件按名字自然排序（第2卷在第10卷前）；
  若每章都有“第X章”序号，就按章号排序，同一章以后面的文件为准——适合作者分批修订的文稿。
- **从原文件刷新**：记住每份文件的来源（手机存储、Google 云盘等），原文件改过后长按书籍即可刷新。
- **阅读体验**：宋体/黑体、字号、行距、段距可调；纸白、宣黄、护眼、夜间四种背景；
  点左右两侧翻页、中间呼出菜单；自动记住每本书读到哪一段；书内全文搜索。
- **隐私**：不联网，不收集任何数据；书籍副本只存在本机应用目录。

## 使用

1. 从 [Actions](../../actions) 下载最新构建的 `LingYueReader-apk`，或自行构建。
2. 点右上角“＋”，选择一个或多个 `.txt` / `.epub` / `.md` 文件。
3. 长按书架上的书可以刷新、重命名或移除。

## 构建

需要 JDK 21 和 Android SDK（compileSdk 37）。界面基于 miuix（HyperOS 风格的 Compose 组件库，`top.yukonga.miuix.kmp:miuix-ui`）。

```
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

代码结构：

| 路径 | 作用 |
| --- | --- |
| `data/TxtParser.kt`、`ChapterTitles.kt` | TXT 分章与中文数字 |
| `data/EpubParser.kt`、`HtmlText.kt` | EPUB 解析（不依赖第三方库） |
| `data/MarkdownParser.kt` | Markdown 文稿与文档两种读法 |
| `data/BookParser.kt` | 格式分派、合集合并、书内搜索 |
| `data/Library.kt` | 书架存储与刷新 |
| `ui/` | Jetpack Compose + miuix 界面 |

## 计划

- EPUB 插图与封面
- 书签与划线笔记
- 左右翻页（仿真/覆盖）模式、音量键翻页
- 朗读

欢迎提 Issue 和 PR。

## 许可证

[Apache License 2.0](LICENSE)
