# 界面截图

以下图片由 Robolectric SDK 36 的 `NightReaderSheetUiTest` 从测试书渲染生成，展示夜间目录和外观面板；它们是自动化界面截图，不代表真机实拍。

- [夜间目录](night-catalog.png)
- [夜间外观设置](night-typography.png)

CI 同时把两张原始 PNG 上传为 `LingYueReader-night-ui` 工件。

导入方式与翻页设置的静态预览由 Robolectric SDK 36 渲染；应用内选择方式时会播放一次纸页动效，减少动态效果时直接显示终态。静态图不能验证动画帧率或真机手势。

- [导入方式](import-modes.png)
- [翻页方式](page-mode-preview.png)
