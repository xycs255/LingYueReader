# 阅读研究与灵阅的取舍

灵阅的阅读动画和几项阅读功能，按下面这些研究来定。每条先写研究说了什么、证据有多硬，再写灵阅怎么做。
证据弱或互相矛盾的，不拿来当卖点，只做成可选项。

## 1. 屏幕和纸，差在哪

**研究**

- 多项元分析的结论一致：读说明文，屏幕比纸的理解成绩略差，效应量小（Delgado 等 2018；Salmerón 等 2024）；
  阅读速度没有显著差别。
- 在手机、平板、阅读器这类手持设备上读，差距比电脑屏幕小，更接近纸（Salmerón 等 2024，*Journal of Educational Psychology*；
  2025 年设备间网络元分析）。
- 差距在**限时**阅读时最明显：限时研究里纸的优势更大（Delgado 2018，g = −0.26），不限时时基本没有。
- 也有系统综述据报道认为最常见的结果是“没有差别”（Hare 等 2024，88 篇；只见到二手转述，未核对原文）。

**灵阅的取舍**

- 不制造时间压力：不做倒计时、不做“读得快才算数”的设计。
  “本章约剩几分钟”只是信息，按个人读速估算，可以不看。
- 自动滚动、听书的速度都由读者自己定，随时暂停、回退一句。

## 2. 滚动还是翻页

**研究**

- 早年的研究多偏向翻页：翻页读得略快、理解略好，工作记忆小的人被滚动拖累更多（Sanchez 与 Wiley 2009）。
- 但大多只是“趋势”，不显著。2025 年 CHI 上 Joshi、Casiez、Vogel 在手机上重做（100 人读短篇小说）：
  理解、用时、主观负荷都**没有显著差别**（理解 14.8 vs 14.7 / 20）。
- 同一研究的开放回答里，翻页组说“更专注”“有进度感”“不那么吓人”，滚动组有人抱怨“滚完要重新找刚才读到哪”。
  作者建议：让读者自己选。
- 手持设备上滚动与否不再是显著的调节因素（Salmerón 2024）。

**灵阅的取舍**

- 滚动、平移、覆盖、仿真、竖排都保留，默认仍是滚动；不因“翻页更好”的旧结论改默认值。
- 针对“滚完要重新找位置”：点一下往下翻时，按**能看清的那一段**的高度翻（不算被状态栏、状态行挡住的部分），
  再少翻一行半，刚读完的最后一行还留在顶上；翻完在原来的底边（现在到了顶上）的左边距划一道短横线，
  “线上读过、线下接着读”，约 2 秒褪掉。手指自己滚时立刻消失。

## 3. 翻页、跳转要不要动画

**研究**

- Klein 与 Bederson（CHI 2005）比较了瞬间跳转和不同时长的滚动动画：读文章的任务里，
  动画让**出错率最多降低 54%**，300 毫秒左右最好，用户也更满意。眼睛能跟住文字移到了哪里，不必重新定位。

**灵阅的取舍**

- 点按、音量键翻页和滚一屏，统一用 **300 毫秒**、起步快收尾柔的标准缓动（`Motion.TURN`）。
  手指拖动时跟手，松手按速度归位。
- 跳转（书签、批注、搜索结果、回到刚才）会换一整屏，眼睛没法跟。所以落点那一段铺一层淡淡的强调色，
  约 0.7 秒后用 1.6 秒褪掉。墨水屏上不渐变，亮 2 秒后直接消失。

## 4. 知道自己读到哪

**研究**

- Mangen、Olivier、Velay（*Frontiers in Psychology* 2019）：50 人读同一篇 28 页的小说，一半用纸书，一半用 Kindle。
  大部分理解指标没有差别，但**事件先后**题纸书组更好（57.1% vs 44.0%），重排 14 个情节也更准（Kendall 距离 4.8 vs 7.8）。
- 作者的解释：纸书左右两边一薄一厚，手指能感到读到哪，这些“实物占位”帮读者把情节挂到位置上；
  Kindle 只剩视觉线索（该实验里连进度条都隐藏了）。他们建议后续研究检验页码、进度提示能否弥补。
- Joshi 等 2025 的翻页界面顶上有一根 5 像素的进度条，参与者提到“有进度感”。

**灵阅的取舍**

- 状态行顶上一根细线，表示**全书**读到哪，读到新位置时顺滑地走过去。
- 翻页模式下书页夹在两摞纸中间（“书页厚度”）：左边那摞是读过的，右边是剩下的（竖排左右对调），按全书读到哪连续变化；书越长两摞越厚（4–14dp）。
  可以在阅读设置里关掉。
- “回到刚才”方便往回查，弥补电子书往回翻比纸书麻烦这一点。

## 5. 中文排版

**研究**（多为小样本，结论只作参考）

- 字距加大可以缓解“拥挤效应”（字挤在一起难以分辨），这是《心理科学》2020 年一项比较青年与老年读者的眼动研究的出发点，
  它的具体结果我们没有核对到原文。天津师大对阅读障碍儿童的研究发现：笔画简单的字加大字距有帮助，但并非越大越好
  （+6pt 反而更差，+12pt 不差）。
- 行距：一项 35 人的研究中，最紧的行距眼跳更短、回视更少，总阅读时间各行距差别不显著；
  VR 中行距越大反应越慢。

**灵阅的取舍**

- 默认排版不变（行距 1.8、字距 0.03em）。
- 增加一键排版：紧凑、标准、宽松。宽松是 22 号字、行距 2.0、字距 0.1em，给年长读者和读字吃力的人用。

## 6. 深浅色和护眼

**研究**

- 浅底深字（正极性）多数研究里读得更快、更准；深色模式在昏暗环境下可能更不容易疲劳，但结果不一致。
- 滤蓝光镜片不能减轻短期眼疲劳，对睡眠的影响也不清楚（Cochrane 系统综述 2023，17 项随机试验）。
- 读的同时听（逐句高亮）总体帮助很小（g = 0.18），只在节奏由外部决定时明显，自己掌握节奏时几乎没有差别
  （Clinton-Lisell 2023，30 项研究）。

**灵阅的取舍**

- 平时用浅色书页，环境很暗才换夜间（已有的环境光规则）。
- “日落后逐渐变暖”只为观感柔和，不宣称护眼或助眠。
- 听书的逐句高亮保留，但不宣称能提高理解。

## 7. 动效本身

**研究 / 规范**

- WCAG 2.2 的 2.3.3（动画来自交互）：由操作触发的非必要动效应能关掉。滚动时额外移动的元素可能让前庭敏感的人头晕、恶心。

**灵阅的取舍**

- 系统里关掉动画（无障碍“移除动画”，或开发者选项里动画时长缩放为 0）时，灵阅的非必要动效全部不做。
  这包括打开书的放大、主题扩散、卡片翻面、数字滚动、翻页动画等；只关动效，玻璃和配色照旧。
- 墨水屏 / 省电模式同样不做动画。
- 阅读时不加装饰性动画：没有视差，没有会自己动的背景。翻页只做必要的移动。

## 参考

- Delgado, P., Vargas, C., Ackerman, R., & Salmerón, L. (2018). Don't throw away your printed books: A meta-analysis on the effects of reading media on reading comprehension. *Educational Research Review*.
- Salmerón, L., Altamura, L., Delgado, P., Karagiorgi, A., & Vargas, C. (2024). Reading comprehension on handheld devices versus on paper: A narrative review and meta-analysis of the medium effect and its moderators. *Journal of Educational Psychology*, 116, 153–172.
- Decoding digital reading: a network meta-analysis of comprehension across devices (2025). *Education and Information Technologies*. https://link.springer.com/article/10.1007/s10639-025-13843-8
- Joshi, N., Casiez, G., & Vogel, D. (2025). Is Pagination Better than Scrolling when Reading on a Phone? *CHI EA '25*. https://dl.acm.org/doi/full/10.1145/3706599.3720178
- Klein, C., & Bederson, B. B. (2005). Benefits of animated scrolling. *CHI EA '05*, 1965–1968.
- Mangen, A., Olivier, G., & Velay, J.-L. (2019). Comparing comprehension of a long text read in print book and on Kindle. *Frontiers in Psychology*, 10:38. https://www.frontiersin.org/journals/psychology/articles/10.3389/fpsyg.2019.00038/full
- Singh, S., et al. (2023). Blue-light filtering spectacles for computer eye strain, sleep, and macular health. *Cochrane Database of Systematic Reviews*, CD013244. https://www.cochrane.org/evidence/CD013244
- Clinton-Lisell, V. (2023). Reading while listening: A meta-analysis.
- W3C (2023). Understanding Success Criterion 2.3.3: Animation from Interactions. https://www.w3.org/WAI/WCAG22/Understanding/animation-from-interactions
- 字距与老年读者：Effects of Inter-character Spacing on the Reading of Chinese Young and Older Adults (2020). https://jps.ecnu.edu.cn/EN/abstract/abstract10487.shtml
- 字距与阅读障碍儿童：https://psybeh.tjnu.edu.cn/EN/abstract/abstract1836.shtml
- 行距（简体中文眼动）：http://lbms03.cityu.edu.hk/oaps/lt2017-6580-hd897.pdf
