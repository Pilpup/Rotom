package com.college.core.ui.components

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.college.core.ui.theme.AppTheme
import kotlin.random.Random

private const val FROST_PIXELS = 64
private const val GRAIN_PIXELS = 128

// Vertical frost gradient: bright at the top edge, fading to nothing. Gives
// the glass a lit rim without a real backdrop blur.
private fun buildFrost(base: Color): ImageBitmap {
    val bitmap = Bitmap.createBitmap(8, FROST_PIXELS, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint()
    val top = base.copy(alpha = 0.22f)
    val clear = base.copy(alpha = 0f)
    for (y in 0 until FROST_PIXELS) {
        val t = y / (FROST_PIXELS - 1).toFloat()
        paint.color = lerp(top, clear, (t * t)).toArgb()
        canvas.drawRect(0f, y.toFloat(), 8f, (y + 1).toFloat(), paint)
    }
    return bitmap.asImageBitmap()
}

// Static grain tile, repeated (not stretched) so it stays fine-grained.
private fun buildGrain(): ImageBitmap {
    val pixels = IntArray(GRAIN_PIXELS * GRAIN_PIXELS)
    val random = Random(99)
    for (i in pixels.indices) {
        val value = random.nextInt(256)
        val alpha = random.nextInt(16)
        pixels[i] = (alpha shl 24) or (value shl 16) or (value shl 8) or value
    }
    val bitmap = Bitmap.createBitmap(GRAIN_PIXELS, GRAIN_PIXELS, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, GRAIN_PIXELS, 0, 0, GRAIN_PIXELS, GRAIN_PIXELS)
    return bitmap.asImageBitmap()
}

// Cheap glass-like island: translucent fill + cached frost/grain/highlight.
// No Modifier.blur, no RenderEffect, no per-frame work — the animated mesh
// behind it simply shows through, which is what makes it read as glass.
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = AppTheme.colors
    val base = colors.surface.copy(alpha = if (colors.isDark) 0.64f else 0.80f)
    val frost = remember(colors) { buildFrost(colors.surface) }
    val grain = remember { buildGrain() }
    val grainBrush = remember(grain) {
        ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated))
    }
    val highlight = Color.White.copy(alpha = if (colors.isDark) 0.13f else 0.24f)

    Box(
        modifier = modifier
            .clip(shape)
            .background(base)
            .then(border?.let { Modifier.border(it, shape) } ?: Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().drawBehind {
                drawImage(
                    image = frost,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )
                drawRect(brush = grainBrush, alpha = 0.35f, blendMode = BlendMode.Overlay)
                val inset = 12.dp.toPx()
                drawLine(
                    color = highlight,
                    start = Offset(inset, 1f),
                    end = Offset(size.width - inset, 1f),
                    strokeWidth = 1f
                )
            }
        )
        content()
    }
}
