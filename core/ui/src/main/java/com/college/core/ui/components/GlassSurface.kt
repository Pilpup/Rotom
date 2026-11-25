package com.college.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.college.core.ui.theme.AppTheme

// Connected row position inside LazyColumn card islands so outer borders skip internal dividers.
enum class GlassSliceEdge {
    STANDALONE,
    TOP,
    MIDDLE,
    BOTTOM
}

fun connectedSliceEdge(index: Int, totalCount: Int): GlassSliceEdge = when {
    totalCount <= 1 -> GlassSliceEdge.STANDALONE
    index == 0 -> GlassSliceEdge.TOP
    index == totalCount - 1 -> GlassSliceEdge.BOTTOM
    else -> GlassSliceEdge.MIDDLE
}

// Palette-colored separator for glass islands. Reads cached palette color without string parsing.
@Composable
fun GlassDivider(modifier: Modifier = Modifier, thickness: Dp = 1.dp) {
    val colors = AppTheme.colors
    val palette = LocalProfilePalette.current
    val assets = remember(palette, colors) { GlassRenderCache.getOrBuild(palette, colors) }
    HorizontalDivider(
        color = assets.dividerColor.copy(alpha = 0.45f),
        thickness = thickness,
        modifier = modifier
    )
}

// Vibrant accent color derived from the active profile palette, falling back
// to the theme's primary purple when no profile photo is set.
@Composable
fun profileAccentColor(): Color {
    val colors = AppTheme.colors
    val palette = LocalProfilePalette.current
    return remember(palette, colors) {
        GlassRenderCache.getOrBuild(palette, colors).accentColor
    }
}

private fun DrawScope.drawSliceRim(
    sliceEdge: GlassSliceEdge,
    brush: Brush,
    strokeWidthPx: Float,
    cornerRadiusPx: Float
) {
    val w = size.width
    val h = size.height
    val inset = strokeWidthPx * 0.5f
    val r = cornerRadiusPx.coerceAtMost(minOf(w, h) * 0.5f)
    val style = Stroke(width = strokeWidthPx)

    when (sliceEdge) {
        GlassSliceEdge.STANDALONE -> Unit
        GlassSliceEdge.TOP -> {
            val path = Path().apply {
                moveTo(inset, h)
                lineTo(inset, inset + r)
                if (r > 0f) {
                    arcTo(Rect(inset, inset, inset + 2f * r, inset + 2f * r), 180f, 90f, false)
                }
                lineTo(w - inset - r, inset)
                if (r > 0f) {
                    arcTo(Rect(w - inset - 2f * r, inset, w - inset, inset + 2f * r), 270f, 90f, false)
                }
                lineTo(w - inset, h)
            }
            drawPath(path, brush = brush, style = style)
        }
        GlassSliceEdge.MIDDLE -> {
            drawLine(brush, Offset(inset, 0f), Offset(inset, h), strokeWidth = strokeWidthPx)
            drawLine(brush, Offset(w - inset, 0f), Offset(w - inset, h), strokeWidth = strokeWidthPx)
        }
        GlassSliceEdge.BOTTOM -> {
            val path = Path().apply {
                moveTo(inset, 0f)
                lineTo(inset, h - inset - r)
                if (r > 0f) {
                    arcTo(Rect(inset, h - inset - 2f * r, inset + 2f * r, h - inset), 180f, -90f, false)
                }
                lineTo(w - inset - r, h - inset)
                if (r > 0f) {
                    arcTo(Rect(w - inset - 2f * r, h - inset - 2f * r, w - inset, h - inset), 90f, -90f, false)
                }
                lineTo(w - inset, 0f)
            }
            drawPath(path, brush = brush, style = style)
        }
    }
}

// Single-pass smoked Crystal glass surface backed by cached pre-softened mesh textures
// synchronized with the root BackgroundEngine (zero blur FBOs or pixel shaders).
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    frosted: Boolean = false,
    nested: Boolean = false,
    sliceEdge: GlassSliceEdge = GlassSliceEdge.STANDALONE,
    sliceCornerRadius: Dp = 22.dp,
    viewportOrigin: Offset? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = AppTheme.colors
    val palette = LocalProfilePalette.current
    val assets = remember(palette, colors) { GlassRenderCache.getOrBuild(palette, colors) }
    val engine = LocalBackgroundEngine.current
    val useMesh = engine != null
    // Non-frosted nested pills sit over their parent card's live mesh; skipping duplicate
    // 6-bitmap mesh draws eliminates 30 redundant quads per expanded book row.
    val drawMesh = useMesh && (!nested || frosted)
    val grainBrush = GlassStaticAssets.glassGrainBrush

    val rimBrush = remember(colors.isDark, nested) {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = if (colors.isDark) (if (nested) 0.34f else 0.28f) else 0.80f),
                Color.White.copy(alpha = if (colors.isDark) 0.06f else 0.22f),
                Color.White.copy(alpha = if (colors.isDark) 0.12f else 0.38f)
            )
        )
    }

    val topGlossBrush = remember(colors.isDark, nested) {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (colors.isDark) (if (nested) 0.14f else 0.10f) else 0.45f),
            0.26f to Color.Transparent,
            1f to Color.Black.copy(alpha = if (colors.isDark) 0.14f else 0.03f)
        )
    }

    val tintColor = remember(colors.isDark, nested, frosted, useMesh) {
        if (colors.isDark) {
            val base = if (nested) Color(0xFF1A2033) else Color(0xFF080A12)
            val alpha = if (useMesh) {
                if (frosted) 0.78f else if (nested) 0.56f else 0.44f
            } else {
                if (frosted) 0.88f else if (nested) 0.72f else 0.62f
            }
            base.copy(alpha = alpha)
        } else {
            val alpha = if (useMesh) {
                if (frosted) 0.86f else if (nested) 0.68f else 0.50f
            } else {
                if (frosted) 0.92f else if (nested) 0.82f else 0.72f
            }
            Color.White.copy(alpha = alpha)
        }
    }

    val standaloneBorder: BorderStroke? = when {
        border != null -> border
        sliceEdge != GlassSliceEdge.STANDALONE -> null
        else -> BorderStroke(1.dp, rimBrush)
    }

    // Snapshot state read strictly inside drawWithContent so scroll/expand updates redraw without recomposing.
    var originX by remember { mutableFloatStateOf(0f) }
    var originY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                if (viewportOrigin == null && drawMesh) {
                    val pos = coords.localToRoot(Offset.Zero)
                    if (pos.x != originX) originX = pos.x
                    if (pos.y != originY) originY = pos.y
                }
            }
            .clip(shape)
            .drawWithContent {
                if (drawMesh) {
                    val e = engine!!
                    drawMeshLayer(
                        base = assets.glassBase,
                        blobs = if (nested) assets.ultraSoftBlobs else assets.softBlobs,
                        baseAlpha = if (frosted) 0.48f else 0.22f,
                        blobBaseAlpha = if (nested) 0.38f else 0.44f,
                        nodeBlend = if (colors.isDark) BlendMode.Screen else BlendMode.SrcOver,
                        flow = e.flow,
                        sway = e.sway,
                        breath = e.breath,
                        gx = e.gx,
                        gy = e.gy,
                        viewportWidth = e.screenWidth,
                        viewportHeight = e.screenHeight,
                        offsetX = viewportOrigin?.x ?: originX,
                        offsetY = viewportOrigin?.y ?: originY,
                        nodeScale = 1.04f
                    )
                }

                // Smoked obsidian (dark) / crisp glacier white (light) tint + sharp top-edge gloss + grain.
                drawRect(color = tintColor)
                drawRect(brush = topGlossBrush)
                drawRect(brush = grainBrush, alpha = 0.18f, blendMode = BlendMode.Overlay)

                drawContent()

                if (sliceEdge != GlassSliceEdge.STANDALONE && border == null) {
                    drawSliceRim(
                        sliceEdge = sliceEdge,
                        brush = rimBrush,
                        strokeWidthPx = 1.dp.toPx(),
                        cornerRadiusPx = sliceCornerRadius.toPx()
                    )
                }
            }
            .then(if (standaloneBorder != null) Modifier.border(standaloneBorder, shape) else Modifier)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        content()
    }
}
