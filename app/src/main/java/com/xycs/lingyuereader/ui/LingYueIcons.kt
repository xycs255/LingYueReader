package com.xycs.lingyuereader.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 灵阅的单色图标。所有图形共用 24 × 24 画布、圆端笔画和留白比例；
 * 显示颜色由调用处的 Icon tint 决定，以适应动态取色和阅读页主题。
 */
object LingYueIcons {
    private fun glyph(name: String, autoMirror: Boolean = false, draw: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = "LingYue.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = autoMirror,
        ).apply(draw).build()

    private fun ImageVector.Builder.trace(block: PathBuilder.() -> Unit) {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.85f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    private fun ImageVector.Builder.solid(block: PathBuilder.() -> Unit) {
        path(fill = SolidColor(Color.Black), pathBuilder = block)
    }

    val Back by lazy {
        glyph("Back", autoMirror = true) {
            trace { moveTo(19f, 12f); lineTo(5f, 12f); moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f) }
        }
    }

    val Add by lazy {
        glyph("Add") {
            trace { moveTo(12f, 4.5f); lineTo(12f, 19.5f); moveTo(4.5f, 12f); lineTo(19.5f, 12f) }
        }
    }

    val ChevronBackward by lazy {
        glyph("ChevronBackward", autoMirror = true) {
            trace { moveTo(15.5f, 5f); lineTo(8.5f, 12f); lineTo(15.5f, 19f) }
        }
    }

    val ChevronForward by lazy {
        glyph("ChevronForward", autoMirror = true) {
            trace { moveTo(8.5f, 5f); lineTo(15.5f, 12f); lineTo(8.5f, 19f) }
        }
    }

    val ChevronDown by lazy {
        glyph("ChevronDown") {
            trace { moveTo(5f, 9f); lineTo(12f, 16f); lineTo(19f, 9f) }
        }
    }

    val Check by lazy {
        glyph("Check") {
            trace { moveTo(5f, 12.4f); lineTo(10f, 17f); lineTo(19f, 7f) }
        }
    }

    val Close by lazy {
        glyph("Close") {
            trace { moveTo(5.5f, 5.5f); lineTo(18.5f, 18.5f); moveTo(18.5f, 5.5f); lineTo(5.5f, 18.5f) }
        }
    }

    /** 展开的两页以同一条书脊相连，是应用标志的缩小版。 */
    val Notes by lazy {
        glyph("Notes") {
            trace {
                moveTo(12f, 6.2f)
                curveTo(9.8f, 4.8f, 6.7f, 4.6f, 3.5f, 5.5f)
                lineTo(3.5f, 17.6f)
                curveTo(6.7f, 16.8f, 9.8f, 17f, 12f, 18.5f)
                curveTo(14.2f, 17f, 17.3f, 16.8f, 20.5f, 17.6f)
                lineTo(20.5f, 5.5f)
                curveTo(17.3f, 4.6f, 14.2f, 4.8f, 12f, 6.2f)
                lineTo(12f, 18.5f)
            }
            trace { moveTo(6.7f, 9f); curveTo(7.8f, 8.8f, 8.8f, 8.9f, 9.7f, 9.3f) }
        }
    }

    /** 两张错位的字卡代表生词本，前卡保留三道书写线。 */
    val Translate by lazy {
        glyph("Translate") {
            trace {
                moveTo(8f, 5.2f)
                lineTo(18.3f, 5.2f)
                curveTo(19.4f, 5.2f, 20f, 5.9f, 20f, 7f)
                lineTo(20f, 15f)
            }
            trace {
                moveTo(5.7f, 8.2f)
                lineTo(15.4f, 8.2f)
                curveTo(16.4f, 8.2f, 17f, 8.8f, 17f, 9.8f)
                lineTo(17f, 18.3f)
                curveTo(17f, 19.3f, 16.4f, 19.9f, 15.4f, 19.9f)
                lineTo(5.7f, 19.9f)
                curveTo(4.7f, 19.9f, 4f, 19.3f, 4f, 18.3f)
                lineTo(4f, 9.8f)
                curveTo(4f, 8.8f, 4.7f, 8.2f, 5.7f, 8.2f)
                close()
            }
            trace {
                moveTo(7.5f, 12f); lineTo(13.6f, 12f)
                moveTo(7.5f, 15.7f); lineTo(11.6f, 15.7f)
            }
        }
    }

    /** 日历里连续三天的刻度暗示阅读习惯统计。 */
    val Months by lazy {
        glyph("Months") {
            trace {
                moveTo(5.2f, 6.3f); lineTo(18.8f, 6.3f)
                curveTo(19.8f, 6.3f, 20.4f, 7f, 20.4f, 8f)
                lineTo(20.4f, 18.5f)
                curveTo(20.4f, 19.5f, 19.8f, 20.2f, 18.8f, 20.2f)
                lineTo(5.2f, 20.2f)
                curveTo(4.2f, 20.2f, 3.6f, 19.5f, 3.6f, 18.5f)
                lineTo(3.6f, 8f)
                curveTo(3.6f, 7f, 4.2f, 6.3f, 5.2f, 6.3f)
                close()
                moveTo(3.8f, 10.5f); lineTo(20.2f, 10.5f)
                moveTo(8f, 3.8f); lineTo(8f, 8.1f)
                moveTo(16f, 3.8f); lineTo(16f, 8.1f)
            }
            trace {
                moveTo(7.9f, 15.1f); lineTo(7.9f, 17f)
                moveTo(12f, 13.6f); lineTo(12f, 17f)
                moveTo(16.1f, 14.4f); lineTo(16.1f, 17f)
            }
        }
    }

    val ContactsCircle by lazy {
        glyph("ContactsCircle") {
            trace {
                moveTo(20.2f, 12f)
                curveTo(20.2f, 16.5f, 16.5f, 20.2f, 12f, 20.2f)
                curveTo(7.5f, 20.2f, 3.8f, 16.5f, 3.8f, 12f)
                curveTo(3.8f, 7.5f, 7.5f, 3.8f, 12f, 3.8f)
                curveTo(16.5f, 3.8f, 20.2f, 7.5f, 20.2f, 12f)
                close()
                moveTo(12f, 8f)
                curveTo(10.5f, 8f, 9.5f, 9.1f, 9.5f, 10.6f)
                curveTo(9.5f, 12.1f, 10.5f, 13.2f, 12f, 13.2f)
                curveTo(13.5f, 13.2f, 14.5f, 12.1f, 14.5f, 10.6f)
                curveTo(14.5f, 9.1f, 13.5f, 8f, 12f, 8f)
                close()
                moveTo(6.7f, 18.2f)
                curveTo(7.3f, 15.8f, 9.2f, 14.9f, 12f, 14.9f)
                curveTo(14.8f, 14.9f, 16.7f, 15.8f, 17.3f, 18.2f)
            }
        }
    }

    val Delete by lazy {
        glyph("Delete") {
            trace {
                moveTo(4.8f, 7.2f); lineTo(19.2f, 7.2f)
                moveTo(9.1f, 7.2f); lineTo(9.1f, 4.5f)
                lineTo(14.9f, 4.5f); lineTo(14.9f, 7.2f)
                moveTo(6.7f, 7.2f); lineTo(7.6f, 19f)
                curveTo(7.7f, 19.7f, 8.2f, 20.1f, 8.9f, 20.1f)
                lineTo(15.1f, 20.1f)
                curveTo(15.8f, 20.1f, 16.3f, 19.7f, 16.4f, 19f)
                lineTo(17.3f, 7.2f)
                moveTo(10.1f, 10.5f); lineTo(10.5f, 16.8f)
                moveTo(13.9f, 10.5f); lineTo(13.5f, 16.8f)
            }
        }
    }

    /** 收藏在阅读页的含义是书签，因此使用折角页签而非爱心。 */
    val Favorites by lazy {
        glyph("Favorites") {
            trace {
                moveTo(7.2f, 3.7f); lineTo(16.8f, 3.7f)
                curveTo(17.6f, 3.7f, 18.1f, 4.3f, 18.1f, 5.1f)
                lineTo(18.1f, 20.1f)
                lineTo(12f, 16.5f)
                lineTo(5.9f, 20.1f)
                lineTo(5.9f, 5.1f)
                curveTo(5.9f, 4.3f, 6.4f, 3.7f, 7.2f, 3.7f)
                close()
            }
        }
    }

    val FavoritesFill by lazy {
        glyph("FavoritesFill") {
            solid {
                moveTo(7.2f, 3.7f); lineTo(16.8f, 3.7f)
                curveTo(17.6f, 3.7f, 18.1f, 4.3f, 18.1f, 5.1f)
                lineTo(18.1f, 20.1f)
                lineTo(12f, 16.5f)
                lineTo(5.9f, 20.1f)
                lineTo(5.9f, 5.1f)
                curveTo(5.9f, 4.3f, 6.4f, 3.7f, 7.2f, 3.7f)
                close()
            }
        }
    }

    val ListView by lazy {
        glyph("ListView") {
            trace {
                moveTo(9f, 6.3f); lineTo(19.7f, 6.3f)
                moveTo(9f, 12f); lineTo(19.7f, 12f)
                moveTo(9f, 17.7f); lineTo(19.7f, 17.7f)
            }
            trace {
                moveTo(4.5f, 6.3f); lineTo(4.6f, 6.3f)
                moveTo(4.5f, 12f); lineTo(4.6f, 12f)
                moveTo(4.5f, 17.7f); lineTo(4.6f, 17.7f)
            }
        }
    }

    val More by lazy {
        glyph("More") {
            trace {
                moveTo(5.2f, 12f); lineTo(5.3f, 12f)
                moveTo(12f, 12f); lineTo(12.1f, 12f)
                moveTo(18.8f, 12f); lineTo(18.9f, 12f)
            }
        }
    }

    val Pause by lazy {
        glyph("Pause") {
            trace {
                moveTo(8f, 5.5f); lineTo(8f, 18.5f)
                moveTo(16f, 5.5f); lineTo(16f, 18.5f)
            }
        }
    }

    val Play by lazy {
        glyph("Play") {
            solid {
                moveTo(8.5f, 5.3f)
                curveTo(8.5f, 4.7f, 9.2f, 4.4f, 9.8f, 4.8f)
                lineTo(19.1f, 11.3f)
                curveTo(19.7f, 11.7f, 19.7f, 12.3f, 19.1f, 12.7f)
                lineTo(9.8f, 19.2f)
                curveTo(9.2f, 19.6f, 8.5f, 19.3f, 8.5f, 18.7f)
                close()
            }
        }
    }

    val Remove by lazy {
        glyph("Remove") {
            trace { moveTo(4.5f, 12f); lineTo(19.5f, 12f) }
        }
    }

    val Search by lazy {
        glyph("Search") {
            trace {
                moveTo(16.8f, 10.3f)
                curveTo(16.8f, 13.9f, 13.9f, 16.8f, 10.3f, 16.8f)
                curveTo(6.7f, 16.8f, 3.8f, 13.9f, 3.8f, 10.3f)
                curveTo(3.8f, 6.7f, 6.7f, 3.8f, 10.3f, 3.8f)
                curveTo(13.9f, 3.8f, 16.8f, 6.7f, 16.8f, 10.3f)
                close()
                moveTo(15.2f, 15.2f); lineTo(20.3f, 20.3f)
            }
        }
    }

    val Share by lazy {
        glyph("Share") {
            trace {
                moveTo(5f, 13f); lineTo(5f, 18.3f)
                curveTo(5f, 19.3f, 5.7f, 20f, 6.7f, 20f)
                lineTo(17.3f, 20f)
                curveTo(18.3f, 20f, 19f, 19.3f, 19f, 18.3f)
                lineTo(19f, 13f)
                moveTo(12f, 15.2f); lineTo(12f, 4f)
                moveTo(7.9f, 8.2f); lineTo(12f, 4f); lineTo(16.1f, 8.2f)
            }
        }
    }

    val Sort by lazy {
        glyph("Sort") {
            trace {
                moveTo(4f, 6.5f); lineTo(19.5f, 6.5f)
                moveTo(4f, 11.8f); lineTo(15.8f, 11.8f)
                moveTo(4f, 17.1f); lineTo(11.9f, 17.1f)
            }
        }
    }

    val Timer by lazy {
        glyph("Timer") {
            trace {
                moveTo(19.6f, 13.3f)
                curveTo(19.6f, 17.5f, 16.2f, 20.9f, 12f, 20.9f)
                curveTo(7.8f, 20.9f, 4.4f, 17.5f, 4.4f, 13.3f)
                curveTo(4.4f, 9.1f, 7.8f, 5.7f, 12f, 5.7f)
                curveTo(16.2f, 5.7f, 19.6f, 9.1f, 19.6f, 13.3f)
                close()
                moveTo(9.5f, 3.2f); lineTo(14.5f, 3.2f)
                moveTo(12f, 5.7f); lineTo(12f, 3.2f)
                moveTo(12f, 9.4f); lineTo(12f, 13.3f); lineTo(14.9f, 15f)
            }
        }
    }

    /** 亮度：小圆和八道光。滑块两端一小一大地放。 */
    val Brightness by lazy {
        glyph("Brightness") {
            trace {
                moveTo(15.6f, 12f)
                curveTo(15.6f, 14f, 14f, 15.6f, 12f, 15.6f)
                curveTo(10f, 15.6f, 8.4f, 14f, 8.4f, 12f)
                curveTo(8.4f, 10f, 10f, 8.4f, 12f, 8.4f)
                curveTo(14f, 8.4f, 15.6f, 10f, 15.6f, 12f)
                close()
                moveTo(12f, 3.5f); lineTo(12f, 5.2f)
                moveTo(12f, 18.8f); lineTo(12f, 20.5f)
                moveTo(3.5f, 12f); lineTo(5.2f, 12f)
                moveTo(18.8f, 12f); lineTo(20.5f, 12f)
                moveTo(6f, 6f); lineTo(7.2f, 7.2f)
                moveTo(16.8f, 16.8f); lineTo(18f, 18f)
                moveTo(6f, 18f); lineTo(7.2f, 16.8f)
                moveTo(16.8f, 7.2f); lineTo(18f, 6f)
            }
        }
    }

    /** 复制：后面一张纸露出左上角，前面一张完整的圆角纸。 */
    val Copy by lazy {
        glyph("Copy") {
            trace {
                moveTo(9.5f, 8f)
                lineTo(17.5f, 8f)
                curveTo(18.6f, 8f, 19.5f, 8.9f, 19.5f, 10f)
                lineTo(19.5f, 18.5f)
                curveTo(19.5f, 19.6f, 18.6f, 20.5f, 17.5f, 20.5f)
                lineTo(9.5f, 20.5f)
                curveTo(8.4f, 20.5f, 7.5f, 19.6f, 7.5f, 18.5f)
                lineTo(7.5f, 10f)
                curveTo(7.5f, 8.9f, 8.4f, 8f, 9.5f, 8f)
                close()
            }
            trace {
                moveTo(16.5f, 5.5f)
                curveTo(16.5f, 4.4f, 15.6f, 3.5f, 14.5f, 3.5f)
                lineTo(6.5f, 3.5f)
                curveTo(5.4f, 3.5f, 4.5f, 4.4f, 4.5f, 5.5f)
                lineTo(4.5f, 14f)
                curveTo(4.5f, 15.1f, 5.4f, 16f, 6.5f, 16f)
            }
        }
    }

    /** 划线：斜着的笔，笔下一道横线。 */
    val Highlight by lazy {
        glyph("Highlight") {
            trace {
                moveTo(14.8f, 4.6f)
                lineTo(18.4f, 8.2f)
                lineTo(10.2f, 16.4f)
                lineTo(6f, 17.2f)
                lineTo(6.8f, 13f)
                close()
                moveTo(12.6f, 6.8f); lineTo(16.2f, 10.4f)
            }
            trace { moveTo(4f, 20.5f); lineTo(20f, 20.5f) }
        }
    }

    val Tune by lazy {
        glyph("Tune") {
            trace {
                moveTo(4f, 6.2f); lineTo(7f, 6.2f)
                moveTo(11f, 6.2f); lineTo(20f, 6.2f)
                moveTo(4f, 12f); lineTo(12f, 12f)
                moveTo(16f, 12f); lineTo(20f, 12f)
                moveTo(4f, 17.8f); lineTo(9f, 17.8f)
                moveTo(13f, 17.8f); lineTo(20f, 17.8f)
                moveTo(9f, 4.2f)
                curveTo(7.9f, 4.2f, 7f, 5.1f, 7f, 6.2f)
                curveTo(7f, 7.3f, 7.9f, 8.2f, 9f, 8.2f)
                curveTo(10.1f, 8.2f, 11f, 7.3f, 11f, 6.2f)
                curveTo(11f, 5.1f, 10.1f, 4.2f, 9f, 4.2f)
                close()
                moveTo(14f, 10f)
                curveTo(12.9f, 10f, 12f, 10.9f, 12f, 12f)
                curveTo(12f, 13.1f, 12.9f, 14f, 14f, 14f)
                curveTo(15.1f, 14f, 16f, 13.1f, 16f, 12f)
                curveTo(16f, 10.9f, 15.1f, 10f, 14f, 10f)
                close()
                moveTo(11f, 15.8f)
                curveTo(9.9f, 15.8f, 9f, 16.7f, 9f, 17.8f)
                curveTo(9f, 18.9f, 9.9f, 19.8f, 11f, 19.8f)
                curveTo(12.1f, 19.8f, 13f, 18.9f, 13f, 17.8f)
                curveTo(13f, 16.7f, 12.1f, 15.8f, 11f, 15.8f)
                close()
            }
        }
    }

    val VolumeUp by lazy {
        glyph("VolumeUp") {
            trace {
                moveTo(4f, 9f); lineTo(7.5f, 9f)
                lineTo(12f, 5.2f)
                lineTo(12f, 18.8f)
                lineTo(7.5f, 15f)
                lineTo(4f, 15f)
                close()
                moveTo(15.2f, 9f)
                curveTo(16.2f, 9.7f, 16.7f, 10.7f, 16.7f, 12f)
                curveTo(16.7f, 13.3f, 16.2f, 14.3f, 15.2f, 15f)
                moveTo(17.8f, 6.5f)
                curveTo(19.5f, 7.8f, 20.4f, 9.6f, 20.4f, 12f)
                curveTo(20.4f, 14.4f, 19.5f, 16.2f, 17.8f, 17.5f)
            }
        }
    }
}
