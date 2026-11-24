package com.college.core.ui.components

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.college.core.ui.theme.AppTheme
import kotlin.math.roundToInt
import kotlin.random.Random

private const val PLATE_PIXELS = 96
private const val GRAIN_PIXELS = 128

// Compose's default lerp interpolates in Oklab and can produce tiny negative
// channels when one stop has alpha 0 — Android then throws "outside the range
// for Oklab". Plain sRGB channel mixing is exact, cheap and always valid.
internal fun mixArgb(from: Int, to: Int, fraction: Float): Int {
    val t = fraction.coerceIn(0f, 1f)
    fun channel(a: Int, b: Int): Int =
        (a + (b - a) * t).roundToInt().coerceIn(0, 255)
    return android.graphics.Color.argb(
        channel(android.graphics.Color.alpha(from), android.graphics.Color.alpha(to)),
        channel(android.graphics.Color.red(from), android.graphics.Color.red(to)),
        channel(android.graphics.Color.green(from), android.graphics.Color.green(to)),
        channel(android.graphics.Color.blue(from), android.graphics.Color.blue(to))
    )
}

// Frost + inner shadow in one stretched tile: lit top edge, clear middle,
// darkening bottom. Pre-rendered once, never per frame.
private fun buildPlate(surface: Color): ImageBitmap {
    val bitmap = Bitmap.createBitmap(8, PLATE_PIXELS, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint()
    val lit = surface.copy(alpha = 0.30f).toArgb()
    val clear = surface.copy(alpha = 0f).toArgb()
    val shade = Color(alpha = 0.13f, red = 0f, green = 0f, blue = 0f).toArgb()
    for (y in 0 until PLATE_PIXELS) {
        val t = y / (PLATE_PIXELS - 1).toFloat()
        paint.color = when {
            t < 0.42f -> mixArgb(lit, clear, t / 0.42f)
            else -> mixArgb(clear, shade, (t - 0.42f) / 0.58f)
        }
        canvas.drawRect(0f, y.toFloat(), 8f, (y + 1).toFloat(), paint)
    }
    return bitmap.asImageBitmap()
}

// Diagonal sheen: a soft white band that reads as light catching the glass.
private fun buildSheen(): ImageBitmap {
    val bitmap = Bitmap.createBitmap(PLATE_PIXELS, PLATE_PIXELS, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint()
    for (y in 0 until PLATE_PIXELS) {
        val t = y / (PLATE_PIXELS - 1).toFloat()
        val alpha = 0.10f * (1f - kotlin.math.abs(t - 0.28f) / 0.28f).coerceAtLeast(0f)
        paint.color = Color.White.copy(alpha = alpha).toArgb()
        canvas.drawRect(0f, y.toFloat(), PLATE_PIXELS.toFloat(), (y + 1).toFloat(), paint)
    }
    return bitmap.asImageBitmap()
}

// Static grain, tiled at native scale so it stays fine instead of stretching.
private fun buildGrain(): ImageBitmap {
    val pixels = IntArray(GRAIN_PIXELS * GRAIN_PIXELS)
    val random = Random(99)
    for (i in pixels.indices) {
        val value = random.nextInt(256)
        val alpha = random.nextInt(22)
        pixels[i] = (alpha shl 24) or (value shl 16) or (value shl 8) or value
    }
    val bitmap = Bitmap.createBitmap(GRAIN_PIXELS, GRAIN_PIXELS, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, GRAIN_PIXELS, 0, 0, GRAIN_PIXELS, GRAIN_PIXELS)
    return bitmap.asImageBitmap()
}

// Glass-like island without backdrop blur: translucent base + cached plate,
// sheen, grain and edge light. The animated mesh behind it shows through,
// which is what sells the effect on old GPUs.
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = AppTheme.colors
    val base = colors.surface.copy(alpha = if (colors.isDark) 0.58f else 0.74f)
    val plate = remember(colors) { buildPlate(colors.surface) }
    val sheen = remember { buildSheen() }
    val grain = remember { buildGrain() }
    val grainBrush = remember(grain) {
        ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated))
    }
    val edge = Color.White.copy(alpha = if (colors.isDark) 0.20f else 0.42f)

    Box(
        modifier = modifier
            .clip(shape)
            .background(base)
            .then(border?.let { Modifier.border(it, shape) } ?: Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .drawBehind {
                val width = size.width.toInt().coerceAtLeast(1)
                val height = size.height.toInt().coerceAtLeast(1)
                val full = IntSize(width, height)

                drawImage(image = plate, dstOffset = IntOffset.Zero, dstSize = full)
                drawImage(image = sheen, dstOffset = IntOffset.Zero, dstSize = full)
                drawRect(brush = grainBrush, alpha = 0.40f, blendMode = BlendMode.Overlay)

                drawRoundRect(
                    color = edge,
                    cornerRadius = CornerRadius(size.minDimension * 0.12f),
                    style = Stroke(width = 1.dp.toPx())
                )
                val inset = 12.dp.toPx()
                drawLine(
                    color = edge,
                    start = Offset(inset, 1f),
                    end = Offset(width - inset, 1f),
                    strokeWidth = 1.5f
                )
            }
    ) {
        content()
    }
}
