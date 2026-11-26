package com.college.feature.leave

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.college.core.ui.components.TopDangerAmbientOverlay
import com.college.core.ui.theme.AppTheme
import com.college.feature.leave.network.LeaveApplicationResponse

// Standalone leave application screen (student): form (From/To date range,
// overlap guard, reason) and history (badges, reviewer feedback, hold-to-drag cancel).
@Composable
fun LeaveApplicationScreen(
    applications: List<LeaveApplicationResponse>,
    isLoading: Boolean,
    isSubmitting: Boolean,
    isCancelling: Boolean,
    message: String?,
    onLoad: () -> Unit,
    onSubmit: (fromDate: String, toDate: String, reason: String) -> Unit,
    onCancel: (String) -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(Unit) { onLoad() }

    val scope = rememberCoroutineScope()
    val dragState = remember(scope) { LeaveHistoryDragState(scope) }
    val density = LocalDensity.current
    val statusBarTopPx = WindowInsets.statusBars.getTop(density).toFloat()
    var rootOriginInRoot by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords -> rootOriginInRoot = coords.positionInRoot() }
    ) {
        val dropZoneCenterY = with(density) {
            rootOriginInRoot.y + statusBarTopPx +
                (constraints.maxHeight * 0.11f).coerceAtLeast(64.dp.toPx())
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = AppTheme.colors.textPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Leave Application",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.textPrimary,
                    letterSpacing = (-0.5).sp
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                LeaveApplicationForm(
                    applications = applications,
                    isSubmitting = isSubmitting,
                    message = message,
                    onSubmit = onSubmit
                )

                Spacer(modifier = Modifier.height(12.dp))

                LeaveHistoryTracker(
                    applications = applications,
                    isLoading = isLoading,
                    isCancelling = isCancelling,
                    onCancel = onCancel,
                    dragState = dragState,
                    dropZoneCenterY = dropZoneCenterY
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        TopDangerAmbientOverlay(
            visible = dragState.isDraggingCard,
            pullFraction = {
                val startCenterY = dragState.cardTopLeftInRoot.y + dragState.cardSize.height * 0.5f
                val minTravel = with(density) { 80.dp.toPx() }
                val travelNeeded = (startCenterY - dropZoneCenterY).coerceAtLeast(minTravel)
                ((-dragState.dragOffset.value.y) / travelNeeded).coerceIn(0f, 1f)
            }
        )

        val floatingApp = dragState.floatingApp
        if (floatingApp != null && dragState.cardSize.width > 0 && dragState.cardSize.height > 0) {
            val floatingScale by animateFloatAsState(
                targetValue = when {
                    dragState.isDraggingCard && dragState.inDropZone -> 0.92f
                    dragState.isDraggingCard -> 1.04f
                    else -> 1f
                },
                animationSpec = tween(durationMillis = 140),
                label = "leave_card_drag_scale"
            )
            val widthDp = with(density) { dragState.cardSize.width.toDp() }
            val heightDp = with(density) { dragState.cardSize.height.toDp() }

            Box(
                modifier = Modifier
                    .size(width = widthDp, height = heightDp)
                    .graphicsLayer {
                        translationX = dragState.cardTopLeftInRoot.x - rootOriginInRoot.x + dragState.dragOffset.value.x
                        translationY = dragState.cardTopLeftInRoot.y - rootOriginInRoot.y + dragState.dragOffset.value.y
                        scaleX = floatingScale
                        scaleY = floatingScale
                        rotationZ = (dragState.dragOffset.value.x / 42f).coerceIn(-6f, 6f)
                    }
            ) {
                LeaveHistoryCardContent(app = floatingApp)
            }
        }
    }
}
