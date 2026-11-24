package com.college.core.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.college.core.network.NetworkClient
import com.college.core.network.ServerConfigManager

// One shared loader for every avatar. It reuses the authenticated OkHttp
// client (the avatar route is owner-only), disables crossfade, and decodes to
// RGB_565 so a 128px avatar costs ~32 KB instead of ~64 KB on older devices.
private var sharedAvatarLoader: ImageLoader? = null
private val avatarLoaderLock = Any()

private fun avatarLoader(context: Context): ImageLoader {
    sharedAvatarLoader?.let { return it }
    return synchronized(avatarLoaderLock) {
        sharedAvatarLoader ?: ImageLoader.Builder(context.applicationContext)
            .okHttpClient(NetworkClient.okHttpClient)
            .crossfade(false)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .build()
            .also { sharedAvatarLoader = it }
    }
}

private fun resolveAvatarUrl(relative: String): String =
    ServerConfigManager.DEFAULT_HTTP_URL.resolve(relative)?.toString() ?: relative

// Tap-to-preview: full-screen, fit-to-screen view of the same square avatar.
@Composable
fun ProfilePhotoPreviewDialog(
    url: String?,
    name: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val loader = remember { avatarLoader(context) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (!url.isNullOrBlank()) {
                val request = remember(url) {
                    ImageRequest.Builder(context)
                        .data(resolveAvatarUrl(url))
                        .size(1024)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = "Profile photo",
                    contentScale = ContentScale.Fit,
                    imageLoader = loader,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = initials(name),
                    color = Color.White,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close preview",
                    tint = Color.White
                )
            }
        }
    }
}

// Called on sign-out so a private avatar never lingers in memory/disk cache.
fun clearAvatarCache() {
    sharedAvatarLoader?.memoryCache?.clear()
    sharedAvatarLoader?.diskCache?.clear()
}

private fun initials(name: String?): String {
    val parts = name?.trim().orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

// Fixed 1:1 avatar: the server already center-crops uploads to a square, so
// this is a single ContentScale.Crop decode with no client-side cropping work.
@Composable
fun ProfileAvatar(
    url: String?,
    name: String?,
    fallbackColor: Color,
    size: Dp = 48.dp,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = "Profile photo"
) {
    val context = LocalContext.current
    val loader = remember { avatarLoader(context) }
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(fallbackColor)
            .then(clickable),
        contentAlignment = Alignment.Center
    ) {
        if (!url.isNullOrBlank()) {
            val request = remember(url, size) {
                ImageRequest.Builder(context)
                    .data(resolveAvatarUrl(url))
                    .size(size.value.toInt().coerceAtLeast(64).coerceAtMost(256))
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                imageLoader = loader,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = initials(name),
                color = Color.White,
                fontSize = (size.value * 0.36f).sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
