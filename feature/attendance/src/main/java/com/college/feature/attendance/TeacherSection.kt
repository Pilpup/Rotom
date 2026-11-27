package com.college.feature.attendance

import com.college.core.ui.components.GlassDivider
import com.college.core.ui.components.GlassGlowButton
import com.college.core.ui.components.GlassSurface
import com.college.core.ui.components.glassButtonTransition
import com.college.core.ui.components.profileAccentColor
import com.college.core.ui.theme.AppTheme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.college.feature.attendance.network.AttendanceRecordItem
import com.college.feature.attendance.network.AttendanceSessionResponse
import com.college.feature.attendance.network.CourseResponse
import com.college.feature.leave.HodLeaveRequestsView
import com.college.feature.leave.network.LeaveApplicationResponse
import com.college.feature.attendance.ui.theme.*
import com.college.feature.attendance.viewmodel.TeacherViewMode
import com.college.core.ui.components.AppSearchPill
import com.college.core.ui.components.AppTextField
import com.college.core.ui.components.appTextFieldColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Determines if a student record is marked Present (via BLE consensus or manual teacher verification)
internal fun isRecordPresent(rec: AttendanceRecordItem): Boolean {
    // Two-flag era (graph engine v3.1): VERIFIED (new) / VERIFIED_PRESENT (legacy).
    if (rec.status == "VERIFIED" || rec.status == "VERIFIED_PRESENT" || rec.status.equals("PRESENT", ignoreCase = true)) return true
    if (rec.verificationMethod == "MANUAL_OVERRIDE" || rec.verificationMethod == "TEACHER_MANUAL") return true
    return false
}

// Present = verified; Absent-status = no check-in/ABSENT; Unverified = checked in but not verified.
internal fun getRecordAuditStatus(rec: AttendanceRecordItem): String {
    return when {
        isRecordPresent(rec) -> "Present"
        rec.status == "ABSENT" -> "Absent"
        else -> "Unverified"
    }
}

// Extracts the last two digits of the student's roll number for avatar badge icon
internal fun getRollNumberTwoDigits(roll: String): String {
    val digits = roll.filter { it.isDigit() }
    return when {
        digits.length >= 2 -> digits.takeLast(2)
        digits.length == 1 -> "0$digits"
        roll.length >= 2 -> roll.takeLast(2).uppercase()
        roll.isNotEmpty() -> roll.uppercase()
        else -> "--"
    }
}

internal fun getDisplayRollNo(rec: AttendanceRecordItem): String {
    if (!rec.rollNumber.isNullOrBlank()) {
        return rec.rollNumber.trim()
    }
    return rec.bleDeviceId.trim().uppercase()
}

internal fun compareRollNumbers(a: String, b: String): Int {
    var aNum = 0L
    var hasADigit = false
    for (i in 0 until a.length) {
        val c = a[i]
        if (c in '0'..'9') {
            aNum = aNum * 10 + (c - '0')
            hasADigit = true
        }
    }
    var bNum = 0L
    var hasBDigit = false
    for (i in 0 until b.length) {
        val c = b[i]
        if (c in '0'..'9') {
            bNum = bNum * 10 + (c - '0')
            hasBDigit = true
        }
    }
    return if (hasADigit && hasBDigit && aNum != bNum) {
        aNum.compareTo(bNum)
    } else {
        a.compareTo(b, ignoreCase = true)
    }
}

internal fun getDisplayStudentName(rec: AttendanceRecordItem): String {
    if (!rec.studentName.isNullOrBlank()) return rec.studentName
    val roll = getDisplayRollNo(rec)
    return when (roll.takeLast(2)) {
        "01" -> "Aarav Sharma"
        "04" -> "Ananya Verma"
        "07" -> "Dev Patel"
        "11" -> "Isha Gupta"
        "15" -> "Rohan Mehta"
        "19" -> "Kabir Singh"
        "23" -> "Pooja Nair"
        "31" -> "Vikram Malhotra"
        else -> "Student ${roll.takeLast(2)}"
    }
}

// Teacher dashboard management view supporting session launch and history
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun TeacherSection(
    isSessionActive: Boolean,
    remainingSeconds: Int,
    finalizedRecords: List<AttendanceRecordItem>,
    isCloudSyncing: Boolean,
    isCancelling: Boolean = false,
    cloudStatusMessage: String?,
    onSelectCourse: (CourseResponse) -> Unit,
    onSelectSessionMetadata: (classType: String, batchType: String, unit: String?, topicCovered: String?) -> Unit = { _, _, _, _ -> },
    onStartSession: () -> Unit,
    onResetSession: () -> Unit,
    onVerifyStudent: (AttendanceRecordItem) -> Unit,
    teacherViewMode: TeacherViewMode,
    onSetTeacherViewMode: (TeacherViewMode) -> Unit,
    pastSessions: List<AttendanceSessionResponse>,
    isLoadingHistory: Boolean,
    selectedSessionForDetails: AttendanceSessionResponse? = null,
    isLoadingSessionDetails: Boolean = false,
    onSelectSessionForDetails: (AttendanceSessionResponse) -> Unit = {},
    selectedCourse: CourseResponse? = null,
    onExportAttendance: () -> Unit = {},
    availableCourses: List<CourseResponse> = emptyList(),
    onRefreshCourses: () -> Unit = {},
    // Leave requests (department queue)
    departmentLeaveApplications: List<LeaveApplicationResponse> = emptyList(),
    isLoadingDeptLeave: Boolean = false,
    isReviewingLeave: Boolean = false,
    deptLeaveMessage: String? = null,
    onLoadDeptLeave: () -> Unit = {},
    // (applicationId, decision, feedback) — feedback optional on approve, required on reject
    onReviewLeave: (String, String, String?) -> Unit = { _, _, _ -> }
) {
    if (finalizedRecords.isNotEmpty()) {
        val (presentCount, absentCount) = remember(finalizedRecords) {
            val v = finalizedRecords.count { getRecordAuditStatus(it) == "Present" }
            val u = finalizedRecords.size - v
            Pair(v, u)
        }

        // Non-present students pinned at top (Unverified), Present in middle, Absent at the bottom
        val sortedDisplayList = remember(finalizedRecords) {
            val unverified = finalizedRecords
                .filter { getRecordAuditStatus(it) == "Unverified" }
                .sortedWith { r1, r2 -> compareRollNumbers(getDisplayRollNo(r1), getDisplayRollNo(r2)) }
            val present = finalizedRecords
                .filter { getRecordAuditStatus(it) == "Present" }
                .sortedWith { r1, r2 -> compareRollNumbers(getDisplayRollNo(r1), getDisplayRollNo(r2)) }
            val absent = finalizedRecords
                .filter { getRecordAuditStatus(it) == "Absent" }
                .sortedWith { r1, r2 -> compareRollNumbers(getDisplayRollNo(r1), getDisplayRollNo(r2)) }
            unverified + present + absent
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Metric statistics: Present and Absent
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassSurface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "$presentCount",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Present",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.colors.textSecondary
                        )
                    }
                }

                GlassSurface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "$absentCount",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Absent",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.colors.textSecondary
                        )
                    }
                }
            }

            // Unified attendee roster container
            GlassSurface(
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Header row with "Attendance list" title and "Export" action
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Attendance list",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.textPrimary
                        )

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onExportAttendance() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Export",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTheme.colors.textSecondary
                            )
                        }
                    }

                    GlassDivider()

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        itemsIndexed(
                            items = sortedDisplayList,
                            key = { _, rec -> rec.id ?: rec.bleDeviceId }
                        ) { index, rec ->
                            if (index > 0) {
                                GlassDivider()
                            }

                        val status = getRecordAuditStatus(rec)
                        val isPresent = status == "Present"
                        val isAbsent = status == "Absent"
                        val isUnverified = status == "Unverified"
                        val rollNo = getDisplayRollNo(rec)
                        val twoDigitRoll = getRollNumberTwoDigits(rollNo)
                        val studentName = getDisplayStudentName(rec)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItemPlacement()
                                .clickable(enabled = isUnverified) { onVerifyStudent(rec) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                GlassSurface(
                                    shape = CircleShape,
                                    nested = true,
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = twoDigitRoll,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            color = AppTheme.colors.textPrimary
                                        )
                                    }
                                }

                                Text(
                                    text = studentName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                    color = AppTheme.colors.textPrimary
                                )
                            }

                            when {
                                isPresent -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = AppTheme.colors.primaryPurple,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = "Present",
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AppTheme.colors.primaryPurple
                                        )
                                    }
                                }
                                isAbsent -> {
                                    Text(
                                        text = "Absent",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppTheme.colors.textSecondary
                                    )
                                }
                                else -> {
                                    GlassGlowButton(
                                        onClick = { onVerifyStudent(rec) },
                                        minHeight = 32.dp,
                                        shape = RoundedCornerShape(8.dp),
                                        glowColor = AppTheme.colors.accentOrange,
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = "Unverified",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AppTheme.colors.textPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
} else if (isSessionActive) {
        // Active session broadcasting view and progress countdown
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(0.4f))

            // Pulsing Beacon Aura & Circular Progress Countdown
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "BroadcastWave")
                val wave1ScaleState = infiniteTransition.animateFloat(
                    initialValue = 0.55f,
                    targetValue = 1.35f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, easing = FastOutLinearInEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "Wave1Scale"
                )
                val wave1AlphaState = infiniteTransition.animateFloat(
                    initialValue = 0.45f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, easing = FastOutLinearInEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "Wave1Alpha"
                )
                val wave2ScaleState = infiniteTransition.animateFloat(
                    initialValue = 0.55f,
                    targetValue = 1.35f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, delayMillis = 1100, easing = FastOutLinearInEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "Wave2Scale"
                )
                val wave2AlphaState = infiniteTransition.animateFloat(
                    initialValue = 0.45f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, delayMillis = 1100, easing = FastOutLinearInEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "Wave2Alpha"
                )

                val colors = AppTheme.colors
                val crystalAccent = profileAccentColor()
                val progressState = animateFloatAsState(
                    targetValue = (remainingSeconds.coerceIn(0, 60) / 60f),
                    animationSpec = tween(durationMillis = 900, easing = LinearEasing),
                    label = "teacher_timer_progress"
                )
                val trackRingColor = Color.White.copy(alpha = if (colors.isDark) 0.09f else 0.48f)
                val trackBorderColor = Color.White.copy(alpha = if (colors.isDark) 0.24f else 0.78f)
                val arcHighlightColor = Color.White.copy(alpha = if (colors.isDark) 0.85f else 0.95f)
                val innerGlowColor = crystalAccent.copy(alpha = if (colors.isDark) 0.26f else 0.18f)

                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .drawWithCache {
                            val centerOffset = Offset(size.width / 2f, size.height / 2f)
                            val baseRadius = size.minDimension / 2.35f
                            val wave1Stroke = Stroke(width = 2.dp.toPx())
                            val wave2Stroke = Stroke(width = 1.5.dp.toPx())
                            val trackStroke = Stroke(width = 10.dp.toPx())
                            val borderStroke = Stroke(width = 1.dp.toPx())
                            val glowArcStroke = Stroke(width = 15.dp.toPx(), cap = StrokeCap.Round)
                            val arcStroke = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                            val arcTopLeft = Offset(centerOffset.x - baseRadius, centerOffset.y - baseRadius)
                            val arcSize = Size(baseRadius * 2f, baseRadius * 2f)
                            val sweepBrush = Brush.sweepGradient(
                                listOf(crystalAccent, arcHighlightColor, crystalAccent),
                                center = centerOffset
                            )
                            val arcGlowColor = crystalAccent.copy(alpha = 0.24f)

                            onDrawBehind {
                                drawCircle(
                                    color = crystalAccent.copy(alpha = wave1AlphaState.value * 0.85f),
                                    radius = baseRadius * wave1ScaleState.value,
                                    center = centerOffset,
                                    style = wave1Stroke
                                )
                                drawCircle(
                                    color = arcHighlightColor.copy(alpha = wave2AlphaState.value * 0.65f),
                                    radius = baseRadius * wave2ScaleState.value,
                                    center = centerOffset,
                                    style = wave2Stroke
                                )

                                drawCircle(
                                    color = trackRingColor,
                                    radius = baseRadius,
                                    center = centerOffset,
                                    style = trackStroke
                                )
                                drawCircle(
                                    color = trackBorderColor,
                                    radius = baseRadius,
                                    center = centerOffset,
                                    style = borderStroke
                                )

                                val sweepAngle = 360f * progressState.value
                                if (sweepAngle > 0.5f) {
                                    drawArc(
                                        color = arcGlowColor,
                                        startAngle = -90f,
                                        sweepAngle = sweepAngle,
                                        useCenter = false,
                                        topLeft = arcTopLeft,
                                        size = arcSize,
                                        style = glowArcStroke
                                    )
                                }

                                drawArc(
                                    brush = sweepBrush,
                                    startAngle = -90f,
                                    sweepAngle = sweepAngle,
                                    useCenter = false,
                                    topLeft = arcTopLeft,
                                    size = arcSize,
                                    style = arcStroke
                                )
                            }
                        }
                )

                // Frosted Crystal Glass center dial preserving original timer geometry
                GlassSurface(
                    shape = CircleShape,
                    frosted = true,
                    modifier = Modifier.size(184.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithCache {
                                val glowBrush = Brush.radialGradient(
                                    colors = listOf(innerGlowColor, Color.Transparent),
                                    center = Offset(size.width * 0.5f, size.height * 0.88f),
                                    radius = size.minDimension * 0.72f
                                )
                                onDrawBehind {
                                    drawCircle(brush = glowBrush)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "$remainingSeconds",
                                fontSize = 58.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "SECONDS",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Cancel session button with inner glow
            GlassGlowButton(
                onClick = onResetSession,
                enabled = !isCancelling,
                fillMaxWidth = true,
                minHeight = 52.dp,
                shape = RoundedCornerShape(14.dp),
                glowColor = AppTheme.colors.accentOrange,
                glowActive = true
            ) {
                AnimatedContent(
                    targetState = isCancelling,
                    transitionSpec = glassButtonTransition(),
                    label = "teacher_cancel_session"
                ) { cancelling ->
                    Text(
                        text = if (cancelling) "Cancelling…" else "Cancel",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.textPrimary
                    )
                }
            }
        }
    } else if (selectedSessionForDetails != null) {
        SessionDetailScreen(
            session = selectedSessionForDetails,
            isLoadingDetails = isLoadingSessionDetails,
            onExportAttendance = onExportAttendance
        )
    } else {
        // Refresh course list whenever teacher dashboard or take attendance view opens
        LaunchedEffect(teacherViewMode) {
            if (teacherViewMode == TeacherViewMode.MENU || teacherViewMode == TeacherViewMode.TAKE_ATTENDANCE) {
                onRefreshCourses()
            }
        }

        when (teacherViewMode) {
            TeacherViewMode.MENU,
            TeacherViewMode.TAKE_ATTENDANCE -> {
                // Course selection and session configuration view
                TakeAttendanceSelectionView(
                    onStartSession = onStartSession,
                    onSelectCourse = onSelectCourse,
                    onSelectSessionMetadata = onSelectSessionMetadata,
                    isCloudSyncing = isCloudSyncing,
                    cloudStatusMessage = cloudStatusMessage,
                    availableCourses = availableCourses
                )
            }

            TeacherViewMode.OLDER_SESSIONS -> {
                // Past attendance session history list view
                OlderSessionsView(
                    pastSessions = pastSessions,
                    isLoading = isLoadingHistory,
                    onSelectSession = onSelectSessionForDetails
                )
            }

            TeacherViewMode.LEAVE_REQUESTS -> {
                LaunchedEffect(Unit) { onLoadDeptLeave() }
                HodLeaveRequestsView(
                    applications = departmentLeaveApplications,
                    isLoading = isLoadingDeptLeave,
                    isReviewing = isReviewingLeave,
                    message = deptLeaveMessage,
                    onReview = onReviewLeave
                )
            }
        }
    }
}
