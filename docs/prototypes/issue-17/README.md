# 写作模块图形化交互样稿

[直接打开可交互样稿](https://chatgpt.com/space/page_304f140f8fb48191b9f9046d13715bbd)，或在浏览器中打开同目录的 `index.html`。这是 [Issue #17](https://github.com/xycs255/LingYueReader/issues/17) 的设计验证稿；所有生成、采纳和保存都是本地模拟，不调用 AI，也不修改应用数据或 Android 正式代码。

样稿覆盖手机编辑器、AI 写作工具、总纲到任务卡的章节规划树、故事账本。编辑器可切换“四组工具”和“按上下文推荐的 ✦”两种方案；均需展示键盘展开、选区与候选稿。故事账本的状态修改、伏笔和知情操作都应有可读文字与撤销反馈。

旧版点按基线沿用 [功能盘点的口径](https://github.com/xycs255/LingYueReader/issues/17#issuecomment-6400219484)：从作品页开始，长按算一次，纯确认框、键入、等待和滚动不计。以下路径基于当前 `main` 的现有入口；不包括细纲缺失、审读未通过、提议冲突或重新挑选范本时的额外操作。

| 场景 | 旧版路径 | 点按 |
| --- | --- | ---: |
| 本次输入构思后写完一章并整理进账本 | 章 → 更多 → AI 写一版草稿 → 构思输入入口 → 写本章 → 放进正文 → 更多 → 整理本章 → 写进账本 | 9 |
| 构思已经在细纲里 | 同上，省去构思输入入口 | 8 |
| 修改已有的人物状态 | 故事账本 → 状态条目 → 内容字段 → 保存 | 4 |

同一起点走查原型后的对比：

| 场景 | 旧版 | 四组工具 | 智能 ✦ |
| --- | ---: | ---: | ---: |
| 细纲已含构思，写章并整理入账 | 8 | 6 | 6 |
| 本次输入构思，写章并整理入账 | 9 | 7 | 7 |
| 修改已有的人物状态 | 4 | 3 | 3 |

两种方案在这两条任务中的点击数相同。390px 模拟键盘展开时，四组工具占 115px，智能工具条占 63px；智能方案给正文多留 52px，四组方案则同时显示四个相关动作。两种方案的“全部工具”都列出旧编辑器菜单的 17 个动作；本轮四屏之外的动作仅展示位置，不假装功能已经在原型中实现。

可直接查看的截图位于 [`previews`](previews)：[四组工具和键盘](previews/01-editor-groups-keyboard.png)、[智能按钮和选区](previews/02-editor-smart-selection.png)、[AI 构思与任务](previews/03-ai-author-idea.png)、[AI 候选稿](previews/03-ai-candidate.png)、[作者采纳](previews/04-author-adoption.png)、[逐条整理](previews/05-ledger-review.png)、[规划树](previews/06-planning-tree.png)、[人物状态与撤销](previews/07-ledger-person-edit.png)、[夜间知情网格](previews/08-night-knowledge.png)、[墨水屏规划树](previews/09-ink-planning.png)、[伏笔计划收章](previews/10-clue-timeline.png)。截图之外，HTML 中可操作章节重排、伏笔滑条与撤销。

Chromium 浏览器在 320、360、390px 宽度下检查了横向溢出、键盘态、选区态与可操作按钮的 48px 命中区；自动点击验证了四组和智能方案的两类章稿路径、人物状态、撤销、章节规划、17 项完整工具清单、夜间及墨水屏/减少动效状态。未在 Android 真机上验证；这份 HTML 是设计原型，不是应用实现。

AI 候选稿只有作者明确采纳才进入正文；采纳不使章节自动定稿。整理提议须逐条审核，冲突默认不写入。范本写法图仅供参考，不能自动成为故事事实。这些边界与已合并的 [PR #18](https://github.com/xycs255/LingYueReader/pull/18) 保持一致。

视觉取值参考 [`docs/design.md`](../../design.md) 与 [`docs/motion.md`](../../motion.md)：暖纸与暖墨配色，交互目标至少 48dp；夜间、墨水屏和减少动效都能查看。样稿的演示数据与正式功能实现是两回事，最终交互方向需在设计确认后才进入 Android 代码。
