# 灵阅 LingYue Reader

一个开源、离线、无广告的 Android 阅读器，专注中文长篇阅读。

## 功能

- **多种格式**：TXT（自动识别 UTF-8 / GBK 编码与“第X章”“楔子”“番外”等章节）、EPUB（按书脊与目录分章，
  显示插图，书架上用书的封面）、Markdown（标题、引用、列表、表格都能排版）。
- **大部头也快**：导入时解析一次并写缓存，之后打开只读目录，章节读到才加载。
- **合集导入**：一次选多个文件可以合成一本书。文件按名字自然排序（第2卷在第10卷前）；
  若每章都有“第X章”序号，就按章号排序，同一章以后面的文件为准——适合作者分批修订的文稿。
- **从原文件刷新**：记住每份文件的来源（手机存储、Google 云盘等），原文件改过后长按书籍即可刷新。
- **阅读体验**：上下滚动或左右翻页（平移 / 覆盖），点两侧或按音量键翻页，跨章节连续翻；
  宋体/黑体、字号、行距、段距、亮度可调；纸白、宣黄、护眼、夜间四种背景，系统深色时可自动切夜间；
  可隐藏状态栏全屏阅读；菜单里有全书进度条，拖动即跳转；自动记住每本书读到哪里；书内全文搜索。
- **字典与词典**：长按正文里的字，自动选出它所在的词（可左右调整选区）并查词：
  - 内置离线的基础字典（Unihan：读音、部首、笔画、繁简）和词典（CC-CEDICT：拼音、英文释义、相关词语）；
  - 导入 MDict 词典（`.mdx`，连同 `.mdd` 图片资源和 `.css` 样式一起选），查词时依次列出各本词典的释义，
    可在“书架 ⋯ → 词典…”里启用、排序、删除；
  - 一键到汉典、百度汉语在线查询（打开浏览器），或交给手机上已装的其他词典、翻译应用。
- **书签与批注**：顶栏一键加书签；长按选词后点“划线·批注”，可选中一部分划线（四种颜色）、写批注、复制；
  目录面板里集中查看书签和批注，批注可导出为 Markdown。原文件改稿后，批注会按原文重新找回位置。
- **听书**：用系统的文字转语音逐句朗读，正在读的句子高亮并自动翻页、换章；锁屏继续，通知栏可暂停；
  语速可调，可定时关闭（15 / 30 / 60 分钟或本章结束）。
- **书架**：网格或列表显示，按最近阅读、书名、添加时间排序，可搜索书名和作者；书籍详情页显示简介、篇幅、
  累计阅读时长、来源文件等。阅读页底部显示时间和电量。
- **备份与恢复**：书架、书籍文件、进度、书签批注和设置打包成一个 zip，换手机时恢复。
- **用灵阅打开**：在文件管理器、聊天软件里打开或分享 TXT / EPUB / Markdown 文件，直接导入并开始阅读；
  同一个文件重复打开不会重复入库。
- **隐私**：应用本身不联网（没有网络权限），不收集任何数据；书籍副本只存在本机应用目录。

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
| `data/Marks.kt` | 书签、批注、改稿后重新定位与导出 |
| `data/BookCache.kt` | 解析结果缓存、章节按需加载 |
| `tts/` | 听书：按句朗读与前台服务 |
| `dict/Builtin.kt` | 内置字典词典查询、长按选词的分词 |
| `dict/Mdict.kt`、`MdictDictionary.kt` | MDict（.mdx/.mdd）读取，含 LZO、zlib 与加密索引 |
| `dict/Dictionaries.kt` | 词典导入、设置与汇总查询 |
| `tools/build_dict.py` | 由 Unihan 与 CC-CEDICT 原始数据生成内置词库 |
| `ui/Paginator.kt`、`ui/PagedReader.kt` | 按实际排版分页、左右翻页 |
| `ui/` | Jetpack Compose + miuix 界面 |

## 计划

- 仿真翻页动画
- 书架分组、WebDAV 同步
- 繁简转换、自定义字体
- PDF 等更多格式

欢迎提 Issue 和 PR。

## 许可证

代码采用 [Apache License 2.0](LICENSE)。

内置词库数据来自第三方，按各自许可证分发：

- `app/src/main/assets/dict/unihan.tsv.gz`：由 Unicode 字符数据库的 [Unihan](https://www.unicode.org/charts/unihan.html)
  与 CJKRadicals.txt 生成，© Unicode, Inc.，[Unicode License v3](https://www.unicode.org/license.txt)。
- `app/src/main/assets/dict/cedict.tsv.gz`：由 [CC-CEDICT](https://cc-cedict.org/wiki/) 生成，
  © MDBG，[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)；这份派生数据同样以 CC BY-SA 4.0 提供。

导入的 MDict 词典归其原作者所有，灵阅只在本机读取，不做分发。
