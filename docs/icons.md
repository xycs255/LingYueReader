# 灵阅图标系统

启动图标采用暖纸、朱砂书签与两列竖排阅读痕迹。纸纹作为满版背景，书签和阅读痕迹独立放在自适应图标安全区内；圆形、圆角方形裁切使用同一套图形。Android 主题图标只保留书签与阅读痕迹的透明单色轮廓。

![启动图标的三种呈现](assets/launcher-preview.png)

界面图标均为项目内绘制的矢量图形，共 49 种。它们使用 24 dp 画布、1.85 dp 圆端笔画与一致的留白；书架、生词本和阅读统计分别以开卷、字卡、日期刻度表达。图标颜色由界面主题实时着色，适配动态配色及阅读页的明暗主题。

编辑器写作工具里每个工具一个图标，不重复：AI 写整章草稿用铅笔、润色用毛笔（`Polish`）、快捷标点用直角引号加句号（`Punctuation`）、章节状态用燕尾小旗（`Flag`）、自动排版用段首空两格的文字行（`Format`）、预览本章用眼睛（`Preview`）。编辑器底栏四个标签展开的大图标里新补了两个：灵感用一只灯泡（`Bulb`，底下两道灯座），改章名用一道底线上竖着的文字光标（`Rename`）；问题用记号笔（`Highlight`），这章写完了用对勾（`Check`）。田字四格（`Tools`）是以前底栏“工具”入口的图标，现在编辑器不用了。

![界面图标总览](assets/ui-icons-preview.png)

界面图标源文件为 `app/src/main/java/com/xycs/lingyuereader/ui/LingYueIcons.kt`。启动图标的图形位于 `app/src/main/res/drawable/ic_launcher_*.xml`，纸纹位于 `app/src/main/res/drawable-nodpi/ic_launcher_paper.webp`；听书与阅读提醒使用书签标志的透明单色 `ic_notification.xml`，确保通知栏在各系统主题下清晰显示。
