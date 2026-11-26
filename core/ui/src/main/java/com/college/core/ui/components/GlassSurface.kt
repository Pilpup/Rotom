package com.college.core.ui.components

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
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

// Reactive red ambient occlusion glow along the top screen edge while holding & dragging a card upward.
@Composable
fun TopDangerAmbientOverlay(
    visible: Boolean,
    pullFraction: () -> Float,
    modifier: Modifier = Modifier
) {
    val dragVisibilityState = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "top_danger_ambient_visibility"
    )
    val statusInsets = WindowInsets.statusBars
    val dangerTone = AppTheme.colors.dangerRed

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val fade = dragVisibilityState.value
                if (fade <= 0.005f) return@drawBehind
                val clampedPull = pullFraction().coerceIn(0f, 1f)
                val easedPull = clampedPull * clampedPull
                val peakAlpha = fade * (0.14f + 0.58f * easedPull)
                val spanHeight = statusInsets.getTop(this).toFloat() +
                    (52.dp + 44.dp * clampedPull).toPx()

                drawRect(
                    brush = Brush.verticalGradient(
                        0.00f to dangerTone.copy(alpha = peakAlpha),
                        0.18f to dangerTone.copy(alpha = peakAlpha * 0.70f),
                        0.38f to dangerTone.copy(alpha = peakAlpha * 0.40f),
                        0.60f to dangerTone.copy(alpha = peakAlpha * 0.16f),
                        0.82f to dangerTone.copy(alpha = peakAlpha * 0.04f),
                        1.00f to Color.Transparent,
                        startY = 0f,
                        endY = spanHeight
                    ),
                    size = Size(size.width, spanHeight)
                )

                val bloomRadius = size.width * (0.42f + 0.14f * clampedPull)
                val bloomCenterY = -bloomRadius * 0.48f
                drawCircle(
                    brush = Brush.radialGradient(
                        0.00f to Color(0xFFFF3B5C).copy(alpha = fade * (0.08f + 0.36f * easedPull)),
                        0.55f to dangerTone.copy(alpha = fade * (0.03f + 0.14f * easedPull)),
                        1.00f to Color.Transparent,
                        center = Offset(size.width * 0.5f, bloomCenterY),
                        radius = bloomRadius
                    ),
                    radius = bloomRadius,
                    center = Offset(size.width * 0.5f, bloomCenterY)
                )
            }
    )
}

// Shared morph transition for Crystal glow button labels and loading states.
fun <S> glassButtonTransition(): AnimatedContentTransitionScope<S>.() -> ContentTransform = {
    (fadeIn(tween(180)) + scaleIn(initialScale = 0.94f, animationSpec = tween(180))) togetherWith
        (fadeOut(tween(130)) + scaleOut(targetScale = 0.94f, animationSpec = tween(130)))
}

// Normal Crystal glass action button with subsurface inner glow and subtle outer halo.
@Composable
fun GlassGlowButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillMaxWidth: Boolean = false,
    minWidth: Dp = Dp.Unspecified,
    minHeight: Dp = 44.dp,
    glowColor: Color = profileAccentColor(),
    glowActive: Boolean = enabled,
    glowIntensity: Float = 1f,
    shape: Shape = CircleShape,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val animatedGlow by animateColorAsState(
        targetValue = glowColor,
        animationSpec = tween(durationMillis = 280),
        label = "glass_glow_button_color"
    )
    val strength by animateFloatAsState(
        targetValue = if (glowActive) glowIntensity else 0.18f,
        animationSpec = tween(durationMillis = 220),
        label = "glass_glow_button_strength"
    )
    val widthMod = if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier

    Box(
        contentAlignment = Alignment.Center,
        propagateMinConstraints = true,
        modifier = modifier
            .then(widthMod)
            .drawBehind {
                if (size.width <= 0f || size.height <= 0f) return@drawBehind
                val haloRx = size.width * 0.62f
                val haloRy = size.height * 0.85f
                withTransform({
                    translate(left = center.x, top = center.y)
                    scale(scaleX = haloRx, scaleY = haloRy, pivot = Offset.Zero)
                }) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            0.00f to animatedGlow.copy(alpha = (0.20f * strength).coerceIn(0f, 1f)),
                            0.65f to animatedGlow.copy(alpha = (0.06f * strength).coerceIn(0f, 1f)),
                            1.00f to Color.Transparent,
                            center = Offset.Zero,
                            radius = 1f
                        ),
                        radius = 1f,
                        center = Offset.Zero
                    )
                }
            }
    ) {
        GlassSurface(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            modifier = widthMod
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = widthMod
                    .defaultMinSize(minWidth = minWidth, minHeight = minHeight)
                    .drawBehind {
                        if (size.width <= 0f || size.height <= 0f) return@drawBehind
                        val rx = size.width * 0.56f
                        val ry = size.height * 0.85f
                        withTransform({
                            translate(left = size.width * 0.5f, top = size.height * 0.92f)
                            scale(scaleX = rx, scaleY = ry, pivot = Offset.Zero)
                        }) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    0.00f to animatedGlow.copy(alpha = (0.36f * strength).coerceIn(0f, 1f)),
                                    0.55f to animatedGlow.copy(alpha = (0.14f * strength).coerceIn(0f, 1f)),
                                    1.00f to Color.Transparent,
                                    center = Offset.Zero,
                                    radius = 1f
                                ),
                                radius = 1f,
                                center = Offset.Zero
                            )
                        }
                    }
                    .padding(contentPadding),
                content = content
            )
        }
    }
}
