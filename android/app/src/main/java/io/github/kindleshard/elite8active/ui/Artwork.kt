package io.github.kindleshard.elite8active.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kindleshard.elite8active.R

/**
 * An earbud in outline, traced over Jabra's render (res/drawable/ic_bud_outline):
 * the ear gel on its stem and the body, with L or R on its touch surface. The right one is the
 * mirror image, as they sit in your ears.
 */
@Composable
fun EarbudIcon(letter: String?, mirrored: Boolean = false, size: Dp = 64.dp, color: Color = MaterialTheme.colorScheme.primary) {
    val flip = Modifier.graphicsLayer { if (mirrored) scaleX = -1f }
    Box(Modifier.size(size)) {
        Icon(ImageVector.vectorResource(R.drawable.ic_bud_outline), null, Modifier.matchParentSize().then(flip), tint = color)
        if (letter != null) {
            Icon(ImageVector.vectorResource(R.drawable.ic_bud_face), null, Modifier.matchParentSize().then(flip), tint = color)
            // The touch surface is centred at (26.8, 28.3) on the drawing's 48-unit grid.
            val dx = size * (2.8f / 48) * (if (mirrored) -1 else 1)
            Text(
                letter,
                Modifier.align(Alignment.Center).offset(x = dx, y = size * (4.3f / 48)),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black, fontSize = (size.value * 0.2f).sp),
                color = color,
            )
        }
    }
}

/** The app icon as a rounded tile, for the title bar and the connect screen. */
@Composable
fun AppLogo(size: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f))) {
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        // The foreground is drawn for adaptive-icon masks, so zoom in on its safe zone.
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.matchParentSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f })
    }
}
