package io.github.kindleshard.elite8active.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Symbols drawn in code on a 24-unit grid like Material icons (so they tint and size the same
 * way). The noise-mode symbols and the earbud outlines are vector drawables in res/drawable.
 */
object EliteIcons {
    private val ink = SolidColor(Color.Black)

    private fun icon(name: String, build: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(build).build()

    /** The charging case: a rounded pill with its lid seam and status light. */
    val Case: ImageVector by lazy {
        icon("Case") {
            path(stroke = ink, strokeLineWidth = 2f) {
                moveTo(8f, 6f); lineTo(16f, 6f)
                arcTo(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 21f, y1 = 11f)
                lineTo(21f, 13f)
                arcTo(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 16f, y1 = 18f)
                lineTo(8f, 18f)
                arcTo(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 3f, y1 = 13f)
                lineTo(3f, 11f)
                arcTo(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 8f, y1 = 6f)
                close()
            }
            path(stroke = ink, strokeLineWidth = 1.6f) {
                moveTo(3.5f, 10.5f); lineTo(20.5f, 10.5f)
            }
            path(fill = ink) {
                moveTo(10.8f, 14.2f)
                arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 13.2f, y1 = 14.2f)
                arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 10.8f, y1 = 14.2f)
                close()
            }
        }
    }
}
