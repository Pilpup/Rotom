package com.attendance.ble.shell

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.college.core.ui.theme.AppTheme
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Decodes at most 1600px so a large camera photo never blows up memory on an
// old device. ImageDecoder (API 28+) also handles HEIC/HEIF natively.
private fun decodeCropSource(bytes: ByteArray): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val ratio = min(
                1600f / info.size.width.toFloat(),
                min(1600f / info.size.height.toFloat(), 1f)
            )
            decoder.setTargetSize(
                (info.size.width * ratio).toInt().coerceAtLeast(1),
                (info.size.height * ratio).toInt().coerceAtLeast(1)
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
        }
    } else {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
} catch (_: Exception) {
    null
}

// Square, drag-and-pinch crop. The server still center-crops and scales to
// 256x256; this only decides WHICH square gets sent, so no precision loss.
@Composable
fun PhotoCropDialog(
    sourceBytes: ByteArray,
    onCancel: () -> Unit,
    onConfirm: (ByteArray) -> Unit
) {
    val image = remember(sourceBytes) { decodeCropSource(sourceBytes) }
    val imageBitmap: ImageBitmap? = remember(image) { image?.asImageBitmap() }
    var scale by remember(sourceBytes) { mutableStateOf(1f) }
    var pan by remember(sourceBytes) { mutableStateOf(Offset.Zero) }

    val boxSize = 320.dp
    val boxPx = with(LocalDensity.current) { boxSize.toPx() }

    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(20.dp), color = AppTheme.colors.surface) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Adjust profile photo",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = AppTheme.colors.textPrimary
                )

                if (image != null && imageBitmap != null) {
                    val baseScale = max(boxPx / image.width, boxPx / image.height)
                    val totalScale = baseScale * scale
                    val drawW = image.width * totalScale
                    val drawH = image.height * totalScale
                    val maxX = max(0f, (drawW - boxPx) / 2f)
                    val maxY = max(0f, (drawH - boxPx) / 2f)
                    val offsetX = pan.x.coerceIn(-maxX, maxX)
                    val offsetY = pan.y.coerceIn(-maxY, maxY)
                    val left = (boxPx - drawW) / 2f + offsetX
                    val top = (boxPx - drawH) / 2f + offsetY

                    Canvas(
                        modifier = Modifier
                            .size(boxSize)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppTheme.colors.background)
                            .pointerInput(image, scale) {
                                detectTransformGestures { _, delta, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    pan = Offset(
                                        (pan.x + delta.x).coerceIn(-maxX, maxX),
                                        (pan.y + delta.y).coerceIn(-maxY, maxY)
                                    )
                                }
                            }
                    ) {
                        drawImage(
                            image = imageBitmap,
                            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                            dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt())
                        )
                    }

                    Text(
                        text = "Drag to move • Pinch to zoom",
                        fontSize = 11.sp,
                        color = AppTheme.colors.textSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                            Text("Cancel", color = AppTheme.colors.textSecondary)
                        }
                        Button(
                            onClick = {
                                val srcLeft = (-left / totalScale).roundToInt()
                                    .coerceIn(0, image.width - 1)
                                val srcTop = (-top / totalScale).roundToInt()
                                    .coerceIn(0, image.height - 1)
                                val side = min(
                                    (boxPx / totalScale).roundToInt(),
                                    min(image.width - srcLeft, image.height - srcTop)
                                ).coerceAtLeast(1)
                                val square = Bitmap.createBitmap(image, srcLeft, srcTop, side, side)
                                val final = Bitmap.createScaledBitmap(square, 512, 512, true)
                                val out = ByteArrayOutputStream()
                                final.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                onConfirm(out.toByteArray())
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Use photo")
                        }
                    }
                } else {
                    Text(
                        text = "Preview unavailable on this device.",
                        fontSize = 13.sp,
                        color = AppTheme.colors.textSecondary
                    )
                    Text(
                        text = "You can still upload it — the server crops it to a square.",
                        fontSize = 12.sp,
                        color = AppTheme.colors.textSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = { onConfirm(sourceBytes) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Upload original")
                        }
                    }
                }
            }
        }
    }
}
