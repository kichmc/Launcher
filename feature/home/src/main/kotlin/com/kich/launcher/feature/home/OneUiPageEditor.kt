package com.kich.launcher.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kich.launcher.ui.LauncherIcons
import com.kich.launcher.ui.component.AppIcon

internal const val ONE_UI_EDITOR_TAG = "one_ui_page_editor"

/** Width / height of a page card, close to the home workspace's own proportions. */
private const val CARD_ASPECT = 0.525f

/**
 * One UI-style home "edit mode": the home pages shrink into swipeable cards. The house at the top
 * left makes the centred page the default page, the bin at the top right removes it (only when it
 * is empty — the caller decides and reports), a "+" after the dots adds a page, and the bottom row
 * links to wallpaper, themes, widgets and settings.
 *
 * Original implementation inspired by the general pattern; no vendor assets are used. The page
 * previews are drawn from the live layout ([entries]), so they always match the real pages.
 */
@Composable
internal fun OneUiPageEditor(
    pageCount: Int,
    homePage: Int,
    startPage: Int,
    entries: List<HomeEntry>,
    columns: Int,
    rows: Int,
    canEditPages: Boolean,
    canAddPage: Boolean,
    onSetHome: (Int) -> Unit,
    onDeletePage: (Int) -> Unit,
    onAddPage: () -> Unit,
    onOpenPage: (Int) -> Unit,
    onWallpaper: () -> Unit,
    onThemes: () -> Unit,
    onWidgets: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    val pager = rememberPagerState(initialPage = startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))) { pageCount }
    // A page added from the editor slides into view.
    var lastCount by remember { mutableIntStateOf(pageCount) }
    LaunchedEffect(pageCount) {
        if (pageCount > lastCount) pager.animateScrollToPage(pageCount - 1)
        lastCount = pageCount
    }
    val current = pager.currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(ONE_UI_EDITOR_TAG)
            .background(Color.Black.copy(alpha = 0.40f))
            // A tap on the empty space around the cards leaves edit mode; cards and buttons consume theirs.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isHome = current == homePage
            Icon(
                painter = painterResource(LauncherIcons.Home),
                contentDescription = stringResource(R.string.home_options_set_home),
                tint = Color.White.copy(alpha = if (isHome) 1f else 0.55f),
                modifier = Modifier
                    .size(30.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !isHome && canEditPages,
                    ) { onSetHome(current) },
            )
            val canDelete = canEditPages && pageCount > 1
            Icon(
                painter = painterResource(LauncherIcons.Delete),
                contentDescription = stringResource(R.string.home_options_page_remove),
                tint = Color.White.copy(alpha = if (canDelete) 1f else 0.35f),
                modifier = Modifier
                    .size(30.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = canDelete,
                    ) { onDeletePage(current) },
            )
        }

        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val cardW = maxWidth * 0.76f
            val cardH = minOf(maxHeight, cardW / CARD_ASPECT)
            HorizontalPager(
                state = pager,
                pageSize = PageSize.Fixed(cardW),
                contentPadding = PaddingValues(horizontal = (maxWidth - cardW) / 2),
                pageSpacing = 14.dp,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                PageCard(
                    page = page,
                    entries = entries,
                    columns = columns,
                    rows = rows,
                    width = cardW,
                    height = cardH,
                    onClick = { onOpenPage(page) },
                )
            }
        }

        OneUiPageDots(
            count = pageCount,
            current = current,
            home = homePage,
            showAdd = canEditPages && canAddPage,
            onAdd = onAddPage,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 22.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 18.dp),
            verticalAlignment = Alignment.Top,
        ) {
            EditorAction(R.drawable.ic_ou_image, stringResource(R.string.one_ui_edit_wallpaper), onWallpaper, Modifier.weight(1f))
            EditorAction(R.drawable.ic_ou_brush, stringResource(R.string.one_ui_edit_themes), onThemes, Modifier.weight(1f))
            EditorAction(LauncherIcons.Widgets, stringResource(R.string.home_options_widgets), onWidgets, Modifier.weight(1f), enabled = canEditPages)
            EditorAction(R.drawable.ic_home_settings, stringResource(R.string.one_ui_edit_settings), onSettings, Modifier.weight(1f))
        }
    }
}

@Composable
private fun EditorAction(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
            fontSize = 15.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** One zoomed-out home page, drawn from [entries]: real app icons, tiles for folders, panels for widgets. */
@Composable
private fun PageCard(
    page: Int,
    entries: List<HomeEntry>,
    columns: Int,
    rows: Int,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
) {
    val pad = 10.dp
    val cols = columns.coerceAtLeast(1)
    val rws = rows.coerceAtLeast(1)
    val cellW = (width - pad * 2) / cols
    val cellH = (height - pad * 2) / rws
    val iconSize = minOf(cellW, cellH) * 0.62f
    Box(
        modifier = Modifier
            .size(width, height)
            .clip(RoundedCornerShape(30.dp))
            .background(Color.White.copy(alpha = 0.24f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        entries.filter { it.page == page && it.cellX < cols && it.cellY < rws }.forEach { entry ->
            val x = pad + cellW * entry.cellX
            val y = pad + cellH * entry.cellY
            when (entry) {
                is PlacedApp -> Box(Modifier.offset(x, y).size(cellW, cellH), contentAlignment = Alignment.Center) {
                    AppIcon(entry.app, labelColor = Color.White, iconSize = iconSize, showLabel = false)
                }
                is PlacedFolder -> Box(Modifier.offset(x, y).size(cellW, cellH), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(iconSize).clip(RoundedCornerShape(iconSize * 0.3f)).background(Color(0x992A2530)))
                }
                is PlacedShortcut -> Box(Modifier.offset(x, y).size(cellW, cellH), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(iconSize).clip(RoundedCornerShape(iconSize * 0.3f)).background(Color.White.copy(alpha = 0.45f)))
                }
                else -> {
                    val span = when (entry) {
                        is PlacedWidget -> entry.spanX to entry.spanY
                        is PendingWidget -> entry.spanX to entry.spanY
                        is PlacedBuiltin -> entry.spanX to entry.spanY
                        else -> 1 to 1
                    }
                    Box(
                        Modifier
                            .offset(x + 2.dp, y + 2.dp)
                            .size(cellW * span.first - 4.dp, cellH * span.second - 4.dp)
                            .clip(RoundedCornerShape(minOf(cellW, cellH) * 0.45f))
                            .background(Color(0x992A2530)),
                    )
                }
            }
        }
    }
}
