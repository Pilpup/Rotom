package com.college.core.ui.components

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.college.core.model.DefaultProfilePalette
import com.college.core.model.ProfilePalette
import com.college.core.ui.theme.AppColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val ROOT_BLOB_PIXELS = 256
private const val SOFT_BLOB_PIXELS = 128
private const val NOISE_PIXELS = 128
private const val SCRIM_PIXELS = 256
private const val MAX_PALETTE_CACHE_ENTRIES = 2

internal fun paletteColor(value: String?): Color? = try {
    if (value.isNullOrBlank()) null else Color(android.graphics.Color.parseColor(value))
} catch (_: Exception) {
    null
}

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

// Safe sRGB color mix avoiding Oklab out-of-gamut crashes on transparent or extreme stops.
fun mixGlassColor(from: Color, to: Color, fraction: Float): Color =
    Color(mixArgb(from.toArgb(), to.toArgb(), fraction))

internal fun parseHex(value: String?, fallback: Color): Color = try {
    if (value.isNullOrBlank()) fallback else Color(android.graphics.Color.parseColor(value))
} catch (_: Exception) {
    fallback
}

// Push extracted colors slightly more vivid: artwork palettes are often muted
// once reduced to 3 average colors, but the glow wants saturated light.
private fun vivid(color: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[1] = (hsv[1] * 1.45f).coerceAtMost(1f)
    hsv[2] = max(0.55f, (hsv[2] * 1.30f).coerceAtMost(1f))
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun buildGaussianBlob(
    color: Color,
    sizePx: Int,
    falloff: Float,
    peakAlpha: Float
): ImageBitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val center = sizePx / 2f
    for (step in 40 downTo 1) {
        val t = step / 40f
        paint.color = color.copy(alpha = peakAlpha * exp(-falloff * t * t)).toArgb()
        canvas.drawCircle(center, center, center * t, paint)
    }
    return bitmap.asImageBitmap()
}

private fun buildGradientBase(palette: List<Color>, background: Color, isDark: Boolean): ImageBitmap {
    val width = 8
    val bitmap = Bitmap.createBitmap(width, SCRIM_PIXELS, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint()
    val bg = background.toArgb()
    val top = mixArgb(bg, palette[0].toArgb(), if (isDark) 0.17f else 0.10f)
    val middle = mixArgb(bg, palette[2].toArgb(), if (isDark) 0.12f else 0.07f)
    val bottom = mixArgb(bg, palette[1].toArgb(), if (isDark) 0.15f else 0.09f)
    for (y in 0 until SCRIM_PIXELS) {
        val t = y / (SCRIM_PIXELS - 1).toFloat()
        paint.color = if (t < 0.5f) {
            mixArgb(top, middle, t * 2f)
        } else {
            mixArgb(middle, bottom, (t - 0.5f) * 2f)
        }
        canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + 1).toFloat(), paint)
    }
    return bitmap.asImageBitmap()
}

private fun buildScrim(background: Color): ImageBitmap {
    val bitmap = Bitmap.createBitmap(4, SCRIM_PIXELS, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint()
    val top = background.copy(alpha = 0.88f).toArgb()
    val clear = background.copy(alpha = 0f).toArgb()
    val bottom = background.copy(alpha = 0.72f).toArgb()
    for (y in 0 until SCRIM_PIXELS) {
        val t = y / (SCRIM_PIXELS - 1).toFloat()
        paint.color = if (t < 0.35f) {
            mixArgb(top, clear, t / 0.35f)
        } else {
            mixArgb(clear, bottom, (t - 0.35f) / 0.65f)
        }
        canvas.drawRect(0f, y.toFloat(), 4f, (y + 1).toFloat(), paint)
    }
    return bitmap.asImageBitmap()
}

private fun buildGrain(maxAlphaByte: Int, seed: Int): ImageBitmap {
    val pixels = IntArray(NOISE_PIXELS * NOISE_PIXELS)
    val random = Random(seed)
    for (i in pixels.indices) {
        val value = random.nextInt(256)
        val alpha = random.nextInt(maxAlphaByte)
        pixels[i] = (alpha shl 24) or (value shl 16) or (value shl 8) or value
    }
    val bitmap = Bitmap.createBitmap(NOISE_PIXELS, NOISE_PIXELS, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, NOISE_PIXELS, 0, 0, NOISE_PIXELS, NOISE_PIXELS)
    return bitmap.asImageBitmap()
}

// Process-lifetime grain overlays shared across all Crystal GlassSurface and background instances.
// Never evicted so ShaderBrushes always reference live bitmaps.
internal object GlassStaticAssets {
    val glassGrainBrush: ShaderBrush by lazy {
        ShaderBrush(ImageShader(buildGrain(22, 99), TileMode.Repeated, TileMode.Repeated))
    }
    val bgNoiseBrush: ShaderBrush by lazy {
        ShaderBrush(ImageShader(buildGrain(18, 1337), TileMode.Repeated, TileMode.Repeated))
    }
}

internal data class MeshCacheKey(
    val primary: String?,
    val secondary: String?,
    val tertiary: String?,
    val isDark: Boolean,
    val backgroundArgb: Int,
    val surfaceArgb: Int
)

internal class PaletteMeshAssets(
    val rootBlobs: List<ImageBitmap>,
    val softBlobs: List<ImageBitmap>,
    val ultraSoftBlobs: List<ImageBitmap>,
    val rootBase: ImageBitmap,
    val glassBase: ImageBitmap,
    val scrim: ImageBitmap,
    val dividerColor: Color,
    val accentColor: Color
)

// Bounded cache (max 2 active theme/palette bundles) so scrolling lists and expanding cards
// allocate 0 bitmaps while old profile photo palettes never accumulate in memory.
internal object GlassRenderCache {
    private val cache = object : LinkedHashMap<MeshCacheKey, PaletteMeshAssets>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MeshCacheKey, PaletteMeshAssets>?): Boolean {
            return size > MAX_PALETTE_CACHE_ENTRIES
        }
    }

    @Synchronized
    fun getOrBuild(palette: ProfilePalette?, colors: AppColors): PaletteMeshAssets {
        val activePalette = palette ?: DefaultProfilePalette
        val key = MeshCacheKey(
            primary = activePalette.primary,
            secondary = activePalette.secondary,
            tertiary = activePalette.tertiary,
            isDark = colors.isDark,
            backgroundArgb = colors.background.toArgb(),
            surfaceArgb = colors.surface.toArgb()
        )
        cache[key]?.let { return it }

        val first = vivid(parseHex(activePalette.primary, colors.primaryPurple))
        val second = vivid(parseHex(activePalette.secondary, colors.accentOrange))
        val third = vivid(parseHex(activePalette.tertiary, colors.infoBlue))
        val nodeColors = listOf(
            first,
            second,
            third,
            Color(mixArgb(first.toArgb(), second.toArgb(), 0.5f)),
            Color(mixArgb(second.toArgb(), third.toArgb(), 0.5f))
        )

        val fieldBase = Color(mixArgb(colors.surface.toArgb(), Color.White.toArgb(), 0.10f))
        val scrimBase = Color(
            mixArgb(
                colors.background.toArgb(),
                nodeColors[1].toArgb(),
                if (colors.isDark) 0.10f else 0.06f
            )
        )
        val rawPrimary = paletteColor(activePalette.primary)
        val dividerColor = rawPrimary ?: colors.surfaceBorder
        val accentColor = if (rawPrimary != null) {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(rawPrimary.toArgb(), hsv)
            hsv[1] = (hsv[1] * 1.25f).coerceAtMost(1f)
            hsv[2] = if (colors.isDark) maxOf(0.70f, hsv[2]) else minOf(0.65f, maxOf(0.40f, hsv[2]))
            Color(android.graphics.Color.HSVToColor(hsv))
        } else {
            colors.primaryPurple
        }

        val built = PaletteMeshAssets(
            rootBlobs = nodeColors.map { buildGaussianBlob(it, ROOT_BLOB_PIXELS, falloff = 4.5f, peakAlpha = 0.55f) },
            softBlobs = nodeColors.map { buildGaussianBlob(it, SOFT_BLOB_PIXELS, falloff = 2.4f, peakAlpha = 0.55f) },
            ultraSoftBlobs = nodeColors.map { buildGaussianBlob(it, SOFT_BLOB_PIXELS, falloff = 1.45f, peakAlpha = 0.50f) },
            rootBase = buildGradientBase(nodeColors, colors.background, colors.isDark),
            glassBase = buildGradientBase(nodeColors, fieldBase, colors.isDark),
            scrim = buildScrim(scrimBase),
            dividerColor = dividerColor,
            accentColor = accentColor
        )
        cache[key] = built
        return built
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }
}

// Clear cached palette bitmaps on sign-out or profile palette replacement.
fun clearGlassCache() {
    GlassRenderCache.clear()
}

// Draw the animated drifting mesh aligned to screen coordinates directly inside any DrawScope.
internal fun DrawScope.drawMeshLayer(
    base: ImageBitmap,
    blobs: List<ImageBitmap>,
    baseAlpha: Float,
    blobBaseAlpha: Float,
    nodeBlend: BlendMode,
    flow: Float,
    sway: Float,
    breath: Float,
    gx: Float,
    gy: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    offsetX: Float,
    offsetY: Float,
    nodeScale: Float = 0.95f
) {
    val w = size.width
    val h = size.height
    drawImage(
        image = base,
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
        alpha = baseAlpha
    )

    val vw = if (viewportWidth > 0f) viewportWidth else w
    val vh = if (viewportHeight > 0f) viewportHeight else h
    val span = max(vw, vh)
    val ts = 2f * PI.toFloat() * sway
    val tb = 2f * PI.toFloat() * breath
    val px = -gy
    val py = gx
    val breathing = 1f + 0.18f * sin(tb)

    for (index in blobs.indices) {
        val raw = flow + index / blobs.size.toFloat()
        val progress = raw - floor(raw)
        val travel = (progress * 2.2f - 1.1f) * span
        val sideways = 0.10f * span * sin(ts + index * 1.7f)
        val cx = vw * 0.5f + gx * travel + px * sideways
        val cy = vh * 0.5f + gy * travel + py * sideways

        val stretchX = 1f + 0.14f * sin(ts * 0.61f + index)
        val stretchY = 1f + 0.12f * cos(ts * 0.73f + index)
        val nodeW = span * nodeScale * breathing * stretchX
        val nodeH = span * nodeScale * breathing * stretchY
        drawImage(
            image = blobs[index],
            dstOffset = IntOffset(
                (cx - nodeW / 2f - offsetX).toInt(),
                (cy - nodeH / 2f - offsetY).toInt()
            ),
            dstSize = IntSize(nodeW.toInt().coerceAtLeast(1), nodeH.toInt().coerceAtLeast(1)),
            alpha = (blobBaseAlpha * (0.80f + 0.20f * sin(ts + tb + index))).coerceIn(0f, 1f),
            blendMode = nodeBlend
        )
    }
}
