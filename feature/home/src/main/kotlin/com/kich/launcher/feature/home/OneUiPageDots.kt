package com.kich.launcher.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kich.launcher.ui.LauncherIcons

internal const val ONE_UI_DOTS_TAG = "one_ui_page_dots"

/**
 * The One UI-style page indicator: a leading "=" glyph, a house in place of the default page's dot,
 * round dots for the rest, and (page editor only) a trailing "+" that adds a page.
 *
 * It is original artwork drawn here; nothing is copied from Samsung's launcher.
 */
@Composable
internal fun OneUiPageDots(
    count: Int,
    current: Int,
    home: Int,
    modifier: Modifier = Modifier,
    showAdd: Boolean = false,
    onAdd: () -> Unit = {},
    onMenuClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val inactive = Color.White.copy(alpha = 0.45f)
        MenuGlyph(
            color = inactive,
            modifier = Modifier
                .softHalo(10.dp)
                .then(
                    if (onMenuClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onMenuClick,
                        )
                    } else {
                        Modifier
                    },
                ),
        )
        repeat(count) { index ->
            val color = if (index == current) Color.White else inactive
            if (index == home) {
                Icon(
                    painter = painterResource(LauncherIcons.Home),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp).softHalo(14.dp).testTag(ONE_UI_DOTS_TAG),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .softHalo(7.dp)
                        .background(color = color, shape = CircleShape),
                )
            }
        }
        if (showAdd) {
            Icon(
                painter = painterResource(LauncherIcons.Add),
                contentDescription = null,
                tint = inactive,
                modifier = Modifier
                    .size(14.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onAdd,
                    ),
            )
        }
    }
}

/** The two short bars of the leading "=" marker. */
@Composable
private fun MenuGlyph(color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 1.dp),
        verticalArrangement = Arrangement.spacedBy(2.5.dp),
    ) {
        repeat(2) {
            Box(
                Modifier
                    .size(width = 10.dp, height = 2.dp)
                    .background(color, RoundedCornerShape(1.dp)),
            )
        }
    }
}

/** A faint dark halo so white markers stay readable on a light wallpaper (same idea as PageDots). */
private fun Modifier.softHalo(size: androidx.compose.ui.unit.Dp): Modifier = drawBehind {
    val radius = size.toPx() / 2f + 5.dp.toPx()
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color.Black.copy(alpha = 0.4f),
            1f to Color.Transparent,
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}


