package com.college.feature.attendance

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.college.core.ui.components.GlassDivider
import com.college.core.ui.components.GlassGlowButton
import com.college.core.ui.components.GlassSurface
import com.college.core.ui.components.connectedCardSliceShape
import com.college.core.ui.components.connectedSliceEdge
import com.college.core.ui.components.mixGlassColor
import com.college.core.ui.components.profileAccentColor
import com.college.core.ui.theme.AppTheme
import com.college.feature.attendance.network.StudentHeatmapCourseItem
import com.college.feature.attendance.network.StudentHeatmapResponse
import com.college.feature.attendance.network.StudentHeatmapSessionItem
import com.college.feature.attendance.network.StudentHeatmapSummary
import com.college.feature.attendance.viewmodel.AttendanceViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class HeatmapDaySnapshot(
    val date: String,
    val presentCount: Int,
    val absentCount: Int,
    val leaveCount: Int,
    val totalSessionsCount: Int,
    val level: Int
)

internal data class HeatmapWeekColumn(
    val weekIndex: Int,
    val monthLabel: String?,
    val days: List<String?>
)

internal data class HeatmapMonthBlock(
    val monthLabel: String,
    val columns: List<HeatmapWeekColumn>
)

internal data class HeatmapSelectionState(
    val summary: StudentHeatmapSummary,
    val daySnapshotsByDate: Map<String, HeatmapDaySnapshot>,
    val sessionsByDate: Map<String, List<StudentHeatmapSessionItem>>
)

private val HEATMAP_UTC: TimeZone = TimeZone.getTimeZone("UTC")
private val HEATMAP_ISO_DATE = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            isLenient = false
            timeZone = HEATMAP_UTC
        }
}
private val HEATMAP_MONTH_SHORT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat =
        SimpleDateFormat("MMM", Locale.US).apply { timeZone = HEATMAP_UTC }
}
private val HEATMAP_PRETTY_DAY = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat =
        SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()).apply { timeZone = HEATMAP_UTC }
}

// Builds discrete month-separated Mon-Sun week blocks over [startDate..endDate] without java.time (minSdk 24).
internal fun buildHeatmapMonthBlocks(startDate: String, endDate: String): List<HeatmapMonthBlock> {
    val iso = HEATMAP_ISO_DATE.get() ?: return emptyList()
    val monthFmt = HEATMAP_MONTH_SHORT.get() ?: return emptyList()
    val startParsed = try { iso.parse(startDate) } catch (_: Exception) { null } ?: return emptyList()
    val endParsed = try { iso.parse(endDate) } catch (_: Exception) { null } ?: return emptyList()
    if (startParsed.after(endParsed)) return emptyList()

    val monthCursor = Calendar.getInstance(HEATMAP_UTC, Locale.US).apply {
        time = startParsed
        set(Calendar.DAY_OF_MONTH, 1)
    }
    val endCal = Calendar.getInstance(HEATMAP_UTC, Locale.US).apply { time = endParsed }
    val blocks = ArrayList<HeatmapMonthBlock>(8)
    var globalWeekIndex = 0

    while (!monthCursor.after(endCal)) {
        val monthFirstDay = monthCursor.time
        val nextMonthCal = (monthCursor.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        val monthLastDayCal = (nextMonthCal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }

        val effectiveStart = if (monthFirstDay.before(startParsed)) startParsed else monthFirstDay
        val effectiveEnd = if (monthLastDayCal.time.after(endParsed)) endParsed else monthLastDayCal.time

        val weekCursor = Calendar.getInstance(HEATMAP_UTC, Locale.US).apply { time = effectiveStart }
        val startDayOffset = (weekCursor.get(Calendar.DAY_OF_WEEK) + 5) % 7
        weekCursor.add(Calendar.DAY_OF_MONTH, -startDayOffset)

        val effEndCal = Calendar.getInstance(HEATMAP_UTC, Locale.US).apply { time = effectiveEnd }
        val monthLabel = monthFmt.format(monthFirstDay)
        val monthColumns = ArrayList<HeatmapWeekColumn>(6)
        var firstInMonth = true

        while (!weekCursor.after(effEndCal)) {
            val weekDays = ArrayList<String?>(7)
            for (dow in 0 until 7) {
                val d = weekCursor.time
                if (d.before(effectiveStart) || d.after(effectiveEnd)) {
                    weekDays.add(null)
                } else {
                    weekDays.add(iso.format(d))
                }
                weekCursor.add(Calendar.DAY_OF_MONTH, 1)
            }
            monthColumns.add(
                HeatmapWeekColumn(
                    weekIndex = globalWeekIndex++,
                    monthLabel = if (firstInMonth) monthLabel else null,
                    days = weekDays
                )
            )
            firstInMonth = false
        }

        if (monthColumns.isNotEmpty()) {
            blocks.add(HeatmapMonthBlock(monthLabel = monthLabel, columns = monthColumns))
        }
        monthCursor.time = nextMonthCal.time
    }
    return blocks
}

internal fun buildHeatmapCalendarColumns(startDate: String, endDate: String): List<HeatmapWeekColumn> =
    buildHeatmapMonthBlocks(startDate, endDate).flatMap { it.columns }

// Selects backend-computed overall or per-course summaries & day levels without recomputing formulas client-side.
internal fun resolveHeatmapSelection(
    response: StudentHeatmapResponse,
    selectedCourseCode: String?
): HeatmapSelectionState {
    if (selectedCourseCode.isNullOrBlank()) {
        val snapshots = LinkedHashMap<String, HeatmapDaySnapshot>(response.days.size)
        val sessions = LinkedHashMap<String, List<StudentHeatmapSessionItem>>(response.days.size)
        for (day in response.days) {
            snapshots[day.date] = HeatmapDaySnapshot(
                date = day.date,
                presentCount = day.presentCount,
                absentCount = day.absentCount,
                leaveCount = day.leaveCount,
                totalSessionsCount = day.totalSessionsCount,
                level = day.level
            )
            sessions[day.date] = day.sessions
        }
        return HeatmapSelectionState(
            summary = response.summary,
            daySnapshotsByDate = snapshots,
            sessionsByDate = sessions
        )
    }

    val courseView = response.courseViews[selectedCourseCode]
        ?: return HeatmapSelectionState(
            summary = StudentHeatmapSummary(),
            daySnapshotsByDate = emptyMap(),
            sessionsByDate = emptyMap()
        )

    val snapshots = LinkedHashMap<String, HeatmapDaySnapshot>(courseView.days.size)
    for (day in courseView.days) {
        snapshots[day.date] = HeatmapDaySnapshot(
            date = day.date,
            presentCount = day.presentCount,
            absentCount = day.absentCount,
            leaveCount = day.leaveCount,
            totalSessionsCount = day.totalSessionsCount,
            level = day.level
        )
    }

    val sessions = LinkedHashMap<String, List<StudentHeatmapSessionItem>>(courseView.days.size)
    for (day in response.days) {
        val matching = day.sessions.filter { it.courseCode == selectedCourseCode }
        if (matching.isNotEmpty()) {
            sessions[day.date] = matching
        }
    }

    return HeatmapSelectionState(
        summary = courseView.summary,
        daySnapshotsByDate = snapshots,
        sessionsByDate = sessions
    )
}

internal fun formatPrettyDayHeader(dateStr: String): String {
    val iso = HEATMAP_ISO_DATE.get() ?: return dateStr
    val out = HEATMAP_PRETTY_DAY.get() ?: return dateStr
    val parsed = try { iso.parse(dateStr) } catch (_: Exception) { null } ?: return dateStr
    return out.format(parsed)
}

@Composable
fun StudentAttendanceHeatmapScreen(
    viewModel: AttendanceViewModel,
    onBack: () -> Unit
) {
    LaunchedEffect(Unit) {
        viewModel.loadStudentHeatmap()
    }

    val heatmap = viewModel.studentHeatmap
    val isLoading = viewModel.isLoadingHeatmap
    val errorMsg = viewModel.heatmapError

    var selectedCourseCode by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    var isStatsExpanded by rememberSaveable { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }

    val selection = remember(heatmap, selectedCourseCode) {
        heatmap?.let { resolveHeatmapSelection(it, selectedCourseCode) }
    }

    // Reset course selection cleanly if no longer present in refreshed payload.
    LaunchedEffect(heatmap) {
        if (selectedCourseCode != null && heatmap != null && !heatmap.courseViews.containsKey(selectedCourseCode)) {
            selectedCourseCode = null
        }
    }

    // Auto-select today (if active) or latest active day when data loads or subject filter switches.
    LaunchedEffect(selection, heatmap?.startDate, heatmap?.endDate) {
        if (heatmap == null || selection == null) return@LaunchedEffect
        val cur = selectedDate
        val validRange = cur != null && cur >= heatmap.startDate && cur <= heatmap.endDate
        if (!validRange || (selectedCourseCode != null && !selection.daySnapshotsByDate.containsKey(cur))) {
            selectedDate = when {
                selection.daySnapshotsByDate.containsKey(heatmap.endDate) -> heatmap.endDate
                selection.daySnapshotsByDate.isNotEmpty() -> selection.daySnapshotsByDate.keys.max()
                else -> heatmap.endDate
            }
        }
    }

    val colors = AppTheme.colors
    val accent = profileAccentColor()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        // Navigation header matching StudentSection & LeavePortalUi
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
                    tint = colors.textPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = "My Attendance",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                letterSpacing = (-0.5).sp
            )
        }

        when {
            heatmap == null && isLoading -> {
                HeatmapSkeletonBody(modifier = Modifier.weight(1f))
            }

            heatmap == null && errorMsg != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    GlassSurface(
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = errorMsg,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.textSecondary
                            )
                            GlassGlowButton(
                                onClick = { viewModel.loadStudentHeatmap() },
                                glowColor = accent
                            ) {
                                Text(
                                    text = "Retry",
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary
                                )
                            }
                        }
                    }
                }
            }

            heatmap != null && selection != null -> {
                val monthBlocks = remember(heatmap.startDate, heatmap.endDate) {
                    buildHeatmapMonthBlocks(heatmap.startDate, heatmap.endDate)
                }
                val activeDay = selectedDate ?: heatmap.endDate
                val daySessions = selection.sessionsByDate[activeDay].orEmpty()

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp)
                ) {
                    if (errorMsg != null) {
                        item(key = "heatmap_banner_err") {
                            Text(
                                text = errorMsg,
                                fontSize = 12.5.sp,
                                color = colors.dangerRed,
                                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp)
                            )
                        }
                    }

                    // Subject filter bar (Filter + All, matching OlderSessionsView)
                    if (heatmap.courses.isNotEmpty()) {
                        item(key = "course_filter_bar") {
                            HeatmapFilterBar(
                                hasActiveFilter = selectedCourseCode != null,
                                accent = accent,
                                onOpenFilter = { showFilterDialog = true },
                                onSelectAll = { selectedCourseCode = null },
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }
                    }

                    // GitHub-style heatmap + collapsible attendance % summary merged into one island
                    item(key = "heatmap_grid") {
                        HeatmapGridCard(
                            monthBlocks = monthBlocks,
                            snapshotsByDate = selection.daySnapshotsByDate,
                            selectedDate = activeDay,
                            summary = selection.summary,
                            isStatsExpanded = isStatsExpanded,
                            onToggleStats = { isStatsExpanded = !isStatsExpanded },
                            accent = accent,
                            onSelectDate = { tappedDate -> selectedDate = tappedDate },
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }

                    // Day details header (date only)
                    item(key = "day_details_header") {
                        AnimatedContent(
                            targetState = activeDay,
                            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                            label = "day_header_transition"
                        ) { dayKey ->
                            Text(
                                text = formatPrettyDayHeader(dayKey),
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 10.dp)
                            )
                        }
                    }

                    // Selected day sessions list (connected Library-style slices)
                    if (daySessions.isEmpty()) {
                        item(key = "day_sessions_empty") {
                            GlassSurface(
                                shape = RoundedCornerShape(22.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 28.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (heatmap.summary.totalSessions == 0) {
                                            "No finalized attendance sessions in the last 6 months yet."
                                        } else {
                                            "No classes recorded on ${formatPrettyDayHeader(activeDay)}."
                                        },
                                        fontSize = 13.5.sp,
                                        color = colors.textSecondary
                                    )
                                }
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = daySessions,
                            key = { _, item -> "${activeDay}_${item.sessionId}" }
                        ) { index, session ->
                            HeatmapSessionRow(
                                session = session,
                                index = index,
                                totalCount = daySessions.size
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFilterDialog && heatmap != null) {
        HeatmapCourseFilterDialog(
            courses = heatmap.courses,
            selectedCourseCode = selectedCourseCode,
            onApply = { selectedCourseCode = it },
            onDismiss = { showFilterDialog = false }
        )
    }
}

@Composable
private fun HeatmapSummaryContent(
    summary: StudentHeatmapSummary,
    accent: Color
) {
    val colors = AppTheme.colors
    val countable = summary.presentCount + summary.absentCount
    val pctText = summary.attendancePercentage?.let { pct ->
        if (pct % 1.0 == 0.0) "${pct.toInt()}%" else "${pct}%"
    } ?: "—"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        GlassDivider(modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Attendance",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = pctText,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (summary.attendancePercentage != null) accent else colors.textPrimary
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Attended",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${summary.presentCount} / $countable",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                if (summary.leaveCount > 0) {
                    Text(
                        text = "${summary.leaveCount} leave",
                        fontSize = 11.sp,
                        color = colors.infoBlue
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "Streak",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${summary.currentStreakDays}d",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
            }
        }
    }
}

@Composable
private fun HeatmapFilterBar(
    hasActiveFilter: Boolean,
    accent: Color,
    onOpenFilter: () -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GlassSurface(
            shape = RoundedCornerShape(14.dp),
            onClick = onOpenFilter,
            border = if (hasActiveFilter) BorderStroke(1.dp, accent.copy(alpha = 0.55f)) else null
        ) {
            Row(
                modifier = Modifier
                    .then(
                        if (hasActiveFilter) Modifier.background(accent.copy(alpha = 0.18f))
                        else Modifier
                    )
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = "Filter",
                    tint = if (hasActiveFilter) colors.textPrimary else colors.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (hasActiveFilter) "Filter (1)" else "Filter",
                    fontSize = 12.5.sp,
                    fontWeight = if (hasActiveFilter) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (hasActiveFilter) colors.textPrimary else colors.textSecondary
                )
            }
        }

        val isAllSelected = !hasActiveFilter
        GlassSurface(
            shape = RoundedCornerShape(14.dp),
            onClick = onSelectAll,
            border = if (isAllSelected) BorderStroke(1.dp, accent.copy(alpha = 0.55f)) else null
        ) {
            Box(
                modifier = Modifier
                    .then(
                        if (isAllSelected) Modifier.background(accent.copy(alpha = 0.18f))
                        else Modifier
                    )
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    text = "All",
                    fontSize = 12.5.sp,
                    fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isAllSelected) colors.textPrimary else colors.textSecondary
                )
            }
        }
    }
}

@Composable
private fun HeatmapCourseFilterDialog(
    courses: List<StudentHeatmapCourseItem>,
    selectedCourseCode: String?,
    onApply: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var pendingCourse by remember { mutableStateOf(selectedCourseCode) }
    val colors = AppTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textPrimary,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "Filter Subject",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = colors.textPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                courses.forEach { course ->
                    val isSel = pendingCourse == course.courseCode
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSel) colors.primaryPurple else colors.background,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                pendingCourse = if (isSel) null else course.courseCode
                            }
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = course.courseCode,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSel) Color.White else colors.textPrimary
                            )
                            Text(
                                text = course.courseName,
                                fontSize = 11.5.sp,
                                color = if (isSel) Color.White.copy(alpha = 0.85f) else colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                pendingCourse = null
                onApply(null)
                onDismiss()
            }) {
                Text("Reset", color = colors.textSecondary)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onApply(pendingCourse)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.primaryPurple)
            ) {
                Text("Apply", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun HeatmapGridCard(
    monthBlocks: List<HeatmapMonthBlock>,
    snapshotsByDate: Map<String, HeatmapDaySnapshot>,
    selectedDate: String,
    summary: StudentHeatmapSummary,
    isStatsExpanded: Boolean,
    onToggleStats: () -> Unit,
    accent: Color,
    onSelectDate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    val scrollState = rememberScrollState()

    LaunchedEffect(monthBlocks.size) {
        if (monthBlocks.isNotEmpty()) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    val cellSize = 15.dp
    val cellGap = 4.dp
    val monthBlockGap = 10.dp
    val monthHeaderHeight = 18.dp
    val monthGridGap = 6.dp
    val dayAxisLabels = remember { listOf("M", "T", "W", "T", "F", "S", "S") }
    val monthLabelStyle = remember(colors.textSecondary) {
        TextStyle(
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = colors.textSecondary.copy(alpha = 0.85f),
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )
    }
    val dayAxisTextStyle = remember(colors.textSecondary) {
        TextStyle(
            fontSize = 9.5.sp,
            lineHeight = 10.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            color = colors.textSecondary.copy(alpha = 0.72f),
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )
    }

    GlassSurface(
        onClick = onToggleStats,
        shape = RoundedCornerShape(22.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Sticky left M T W T F S S weekday axis matched identically to month column structure
                Column(modifier = Modifier.padding(end = 8.dp)) {
                    Box(modifier = Modifier.height(monthHeaderHeight))
                    Spacer(modifier = Modifier.height(monthGridGap))
                    Column(verticalArrangement = Arrangement.spacedBy(cellGap)) {
                        dayAxisLabels.forEach { label ->
                            Box(
                                modifier = Modifier.size(width = 14.dp, height = cellSize),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = dayAxisTextStyle
                                )
                            }
                        }
                    }
                }

                // Horizontally scrollable discrete month blocks (each month starts in a fresh column)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(scrollState),
                    horizontalArrangement = Arrangement.spacedBy(monthBlockGap)
                ) {
                    monthBlocks.forEach { block ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier.height(monthHeaderHeight),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = block.monthLabel,
                                    style = monthLabelStyle,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            Spacer(modifier = Modifier.height(monthGridGap))

                            Row(horizontalArrangement = Arrangement.spacedBy(cellGap)) {
                                block.columns.forEach { col ->
                                    Column(verticalArrangement = Arrangement.spacedBy(cellGap)) {
                                        col.days.forEach { dateStr ->
                                            if (dateStr == null) {
                                                Spacer(modifier = Modifier.size(cellSize))
                                            } else {
                                                val snap = snapshotsByDate[dateStr]
                                                val fill = heatmapCellFillColor(snap, accent)
                                                val isSelected = dateStr == selectedDate
                                                val cellDesc = buildCellDescription(dateStr, snap)

                                                Box(
                                                    modifier = Modifier
                                                        .size(cellSize)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(fill)
                                                        .then(
                                                            if (isSelected) {
                                                                Modifier.border(
                                                                    width = 1.5.dp,
                                                                    color = colors.textPrimary.copy(alpha = 0.90f),
                                                                    shape = RoundedCornerShape(4.dp)
                                                                )
                                                            } else {
                                                                Modifier
                                                            }
                                                        )
                                                        .semantics { contentDescription = cellDesc }
                                                        .clickable {
                                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            onSelectDate(dateStr)
                                                        }
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

            Spacer(modifier = Modifier.height(12.dp))

            // Borderless legend row: Less -> More ramp (absent & leave count as neutral base)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Less",
                        fontSize = 10.sp,
                        color = colors.textSecondary.copy(alpha = 0.78f),
                        modifier = Modifier.padding(end = 2.dp)
                    )
                    listOf(
                        HEATMAP_EMPTY_TILE_COLOR,
                        accent.copy(alpha = 0.26f),
                        accent.copy(alpha = 0.48f),
                        accent.copy(alpha = 0.76f),
                        mixGlassColor(accent, Color.White, 0.22f)
                    ).forEach { fill ->
                        Box(
                            modifier = Modifier
                                .size(11.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(fill)
                        )
                    }
                    Text(
                        text = "More",
                        fontSize = 10.sp,
                        color = colors.textSecondary.copy(alpha = 0.78f),
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = isStatsExpanded,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 200),
                    expandFrom = Alignment.Top
                ) + fadeIn(animationSpec = tween(durationMillis = 180)),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = 170),
                    shrinkTowards = Alignment.Top
                ) + fadeOut(animationSpec = tween(durationMillis = 130))
            ) {
                HeatmapSummaryContent(
                    summary = summary,
                    accent = accent
                )
            }
        }
    }
}

private val HEATMAP_EMPTY_TILE_COLOR = Color.Black.copy(alpha = 0.42f)

private fun heatmapCellFillColor(
    snapshot: HeatmapDaySnapshot?,
    accent: Color
): Color {
    if (snapshot == null || snapshot.totalSessionsCount == 0 || snapshot.presentCount == 0) {
        return HEATMAP_EMPTY_TILE_COLOR
    }
    return when (snapshot.level) {
        1 -> accent.copy(alpha = 0.26f)
        2 -> accent.copy(alpha = 0.48f)
        3 -> accent.copy(alpha = 0.76f)
        else -> mixGlassColor(accent, Color.White, 0.22f)
    }
}

private fun buildCellDescription(dateStr: String, snapshot: HeatmapDaySnapshot?): String {
    if (snapshot == null || snapshot.totalSessionsCount == 0) return "$dateStr: No sessions"
    return "$dateStr: ${snapshot.presentCount} present, ${snapshot.absentCount} absent, ${snapshot.leaveCount} leave"
}

@Composable
private fun HeatmapSessionRow(
    session: StudentHeatmapSessionItem,
    index: Int,
    totalCount: Int
) {
    val colors = AppTheme.colors
    val normalizedStatus = session.status.uppercase()
    val statusLabel: String
    val statusColor: Color

    when (normalizedStatus) {
        "PRESENT" -> {
            statusLabel = "Present"
            statusColor = colors.successGreen
        }
        "LEAVE" -> {
            statusLabel = "Leave"
            statusColor = colors.infoBlue
        }
        else -> {
            statusLabel = "Absent"
            statusColor = colors.dangerRed
        }
    }

    val title = session.subjectName?.takeIf { it.isNotBlank() }
        ?: session.courseName?.takeIf { it.isNotBlank() }
        ?: session.courseCode?.takeIf { it.isNotBlank() }
        ?: "Class Session"

    GlassSurface(
        shape = connectedCardSliceShape(index, totalCount, 22.dp),
        sliceEdge = connectedSliceEdge(index, totalCount),
        sliceCornerRadius = 22.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (index > 0) {
                GlassDivider()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        if (size.width <= 0f || size.height <= 0f) return@drawBehind
                        // Subtle full-slice wash + wide right-side subsurface radial bloom matching Crystal Glass inner glow.
                        drawRect(color = statusColor.copy(alpha = 0.045f))
                        withTransform({
                            translate(left = size.width * 0.90f, top = size.height * 0.50f)
                            scale(scaleX = size.width * 0.78f, scaleY = size.height * 1.25f, pivot = Offset.Zero)
                        }) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    0.00f to statusColor.copy(alpha = 0.34f),
                                    0.55f to statusColor.copy(alpha = 0.14f),
                                    1.00f to Color.Transparent,
                                    center = Offset.Zero,
                                    radius = 1f
                                ),
                                radius = 1f,
                                center = Offset.Zero
                            )
                        }
                    }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "$title, $statusLabel"
                    }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun HeatmapSkeletonBody(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "heatmap_skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.62f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heatmap_skeleton_alpha"
    )
    val barColor = AppTheme.colors.textSecondary.copy(alpha = 0.22f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassSurface(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .padding(18.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(18.dp)
                        .alpha(pulse)
                        .background(barColor, RoundedCornerShape(8.dp))
                )
            }
        }
        GlassSurface(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .padding(18.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(pulse)
                        .background(barColor.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                )
            }
        }
        GlassSurface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(16.dp)
                        .alpha(pulse)
                        .background(barColor, RoundedCornerShape(8.dp))
                )
                Box(
                    modifier = Modifier
                        .width(60.dp)
                        .height(24.dp)
                        .alpha(pulse)
                        .background(barColor, CircleShape)
                )
            }
        }
    }
}
