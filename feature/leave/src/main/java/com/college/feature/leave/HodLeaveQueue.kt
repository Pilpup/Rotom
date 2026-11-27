package com.college.feature.leave

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.college.core.ui.components.GlassDivider
import com.college.core.ui.components.GlassSurface
import com.college.core.ui.components.profileAccentColor
import com.college.core.ui.theme.AppTheme
import com.college.feature.leave.network.LeaveApplicationResponse

// HOD department leave queue: lists applications and opens the review dialog
// (feedback optional on approve, mandatory on reject). Owned by the leave
// feature and shown in the HOD leave approvals portal (and teacher flow).
@Composable
fun HodLeaveRequestsView(
    applications: List<LeaveApplicationResponse>,
    isLoading: Boolean,
    isReviewing: Boolean,
    message: String?,
    onReview: (String, String, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var reviewTarget by remember { mutableStateOf<LeaveApplicationResponse?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!message.isNullOrBlank()) {
            item(key = "hod_leave_msg") {
                val ok = message.contains("approved", true) || message.contains("rejected", true)
                val tone = if (ok) AppTheme.colors.successGreen else AppTheme.colors.dangerRed
                GlassSurface(
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, tone.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = message,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = tone,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                    )
                }
            }
        }

        if (isLoading) {
            items(count = 3, key = { "hod_skeleton_$it" }) {
                HodLeaveSkeletonCard()
            }
        } else if (applications.isEmpty()) {
            item(key = "hod_leave_empty") {
                GlassSurface(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No leave applications for your department",
                            fontSize = 13.sp,
                            color = AppTheme.colors.textSecondary
                        )
                    }
                }
            }
        } else {
            items(
                items = applications,
                key = { it.id }
            ) { app ->
                DepartmentLeaveCard(
                    app = app,
                    isReviewing = isReviewing,
                    onOpenReview = { reviewTarget = app }
                )
            }
        }
    }

    reviewTarget?.let { target ->
        LeaveReviewDialog(
            app = target,
            isReviewing = isReviewing,
            onDismiss = { reviewTarget = null },
            onConfirm = { decision, comment ->
                onReview(target.id, decision, comment)
                reviewTarget = null
            }
        )
    }
}

@Composable
private fun HodLeaveSkeletonCard() {
    val transition = rememberInfiniteTransition(label = "hod_leave_skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hod_skeleton_alpha"
    )
    GlassSurface(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(14.dp)
                    .alpha(pulse)
                    .background(AppTheme.colors.textSecondary.copy(alpha = 0.25f), RoundedCornerShape(7.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.38f)
                    .height(12.dp)
                    .alpha(pulse)
                    .background(AppTheme.colors.textSecondary.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(12.dp)
                    .alpha(pulse)
                    .background(AppTheme.colors.textSecondary.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            )
        }
    }
}

@Composable
private fun DepartmentLeaveCard(
    app: LeaveApplicationResponse,
    isReviewing: Boolean,
    onOpenReview: () -> Unit
) {
    val accent = profileAccentColor()
    val isPending = app.status.equals("PENDING", ignoreCase = true)

    GlassSurface(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = (app.studentName ?: "Student").let { name ->
                            if (!app.studentRoll.isNullOrBlank()) "$name (${app.studentRoll})" else name
                        },
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.colors.textPrimary
                    )
                    Text(
                        text = if (app.fromDate == app.toDate) prettyLeaveDate(app.fromDate)
                        else "${prettyLeaveDate(app.fromDate)} → ${prettyLeaveDate(app.toDate)}",
                        fontSize = 12.5.sp,
                        color = AppTheme.colors.textSecondary
                    )
                }
                LeaveStatusBadge(app.status)
            }

            if (app.reason.isNotBlank() || !app.reviewComment.isNullOrBlank()) {
                GlassDivider()
            }

            if (app.reason.isNotBlank()) {
                Text(
                    text = app.reason,
                    fontSize = 13.5.sp,
                    color = AppTheme.colors.textSecondary,
                    lineHeight = 18.sp
                )
            }

            if (!app.reviewComment.isNullOrBlank()) {
                GlassSurface(
                    shape = RoundedCornerShape(12.dp),
                    nested = true,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "HOD: ${app.reviewComment}",
                        fontSize = 12.sp,
                        color = AppTheme.colors.textPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                    )
                }
            }

            if (app.canReview && isPending) {
                GlassSurface(
                    shape = RoundedCornerShape(12.dp),
                    nested = true,
                    onClick = if (!isReviewing) onOpenReview else null,
                    enabled = !isReviewing,
                    border = BorderStroke(
                        1.dp,
                        if (isReviewing) AppTheme.colors.surfaceBorder
                        else accent.copy(alpha = 0.55f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (!isReviewing) Modifier.background(accent.copy(alpha = 0.16f))
                                else Modifier
                            )
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Review request",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isReviewing) AppTheme.colors.textMuted else AppTheme.colors.textPrimary
                        )
                    }
                }
            } else if (isPending) {
                Text(
                    text = "Awaiting HOD decision",
                    fontSize = 12.sp,
                    color = AppTheme.colors.textMuted
                )
            }
        }
    }
}
