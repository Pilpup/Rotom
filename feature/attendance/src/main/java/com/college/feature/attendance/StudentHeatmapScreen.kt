package com.college.feature.attendance

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
private val HEATMAP_MONTH_YEAR = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat =
        SimpleDateFormat("MMM yyyy", Locale.US).apply { timeZone = HEATMAP_UTC }
}
private val HEATMAP_PRETTY_DAY = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat =
        SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()).apply { timeZone = HEATMAP_UTC }
}
private val HEATMAP_TIME_PATTERNS = listOf(
    "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
    "yyyy-MM-dd'T'HH:mm:ss.SSS",
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy-MM-dd HH:mm:ss"
)
private val HEATMAP_TIME_PARSERS = object : ThreadLocal<List<SimpleDateFormat>>() {
    override fun initialValue(): List<SimpleDateFormat> = HEATMAP_TIME_PATTERNS.map { pat ->
        SimpleDateFormat(pat, Locale.US).apply {
            isLenient = false
            timeZone = HEATMAP_UTC
        }
    }
}
private val HEATMAP_LOCAL_TIME = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat =
        SimpleDateFormat("hh:mm a", Locale.getDefault())
}

// Builds Monday-aligned calendar week columns over [startDate..endDate] without java.time (minSdk 24).
internal fun buildHeatmapCalendarColumns(startDate: String, endDate: String): List<HeatmapWeekColumn> {
    val iso = HEATMAP_ISO_DATE.get() ?: return emptyList()
    val monthFmt = HEATMAP_MONTH_SHORT.get() ?: return emptyList()
    val startParsed = try { iso.parse(startDate) } catch (_: Exception) { null } ?: return emptyList()
    val endParsed = try { iso.parse(endDate) } catch (_: Exception) { null } ?: return emptyList()
    if (startParsed.after(endParsed)) return emptyList()

    val cursor = Calendar.getInstance(HEATMAP_UTC, Locale.US).apply {
        time = startParsed
    }
    // Shift backward to Monday (Monday=2 -> offset 0 .. Sunday=1 -> offset 6)
    val startDayOffset = (cursor.get(Calendar.DAY_OF_WEEK) + 5) % 7
    cursor.add(Calendar.DAY_OF_MONTH, -startDayOffset)

    val endCal = Calendar.getInstance(HEATMAP_UTC, Locale.US).apply { time = endParsed }
    val columns = ArrayList<HeatmapWeekColumn>(30)
    var weekIndex = 0
    var lastLabeledWeek = -10

    while (!cursor.after(endCal)) {
        val weekDays = ArrayList<String?>(7)
        var firstMonthDate: Date? = null
        var firstVisibleDate: Date? = null

        for (dow in 0 until 7) {
            val d = cursor.time
            if (d.before(startParsed) || d.after(endParsed)) {
                weekDays.add(null)
            } else {
                weekDays.add(iso.format(d))
                if (firstVisibleDate == null) firstVisibleDate = d
                if (firstMonthDate == null && cursor.get(Calendar.DAY_OF_MONTH) == 1) {
                    firstMonthDate = d
                }
            }
            cursor.add(Calendar.DAY_OF_MONTH, 1)
        }

        val labelCandidateDate = when {
            firstMonthDate != null -> firstMonthDate
            weekIndex == 0 && firstVisibleDate != null -> {
                val c = Calendar.getInstance(HEATMAP_UTC, Locale.US).apply { time = firstVisibleDate }
                if (c.get(Calendar.DAY_OF_MONTH) <= 14) firstVisibleDate else null
            }
            else -> null
        }

        val monthLabel = if (labelCandidateDate != null && weekIndex - lastLabeledWeek >= 3) {
            lastLabeledWeek = weekIndex
            monthFmt.format(labelCandidateDate)
        } else {
            null
        }

        columns.add(HeatmapWeekColumn(weekIndex = weekIndex, monthLabel = monthLabel, days = weekDays))
        weekIndex++
    }
    return columns
}

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

internal fun formatHeatmapRangeTitle(startDate: String, endDate: String): String {
    val iso = HEATMAP_ISO_DATE.get() ?: return "$startDate – $endDate"
    val my = HEATMAP_MONTH_YEAR.get() ?: return "$startDate – $endDate"
    val s = try { iso.parse(startDate) } catch (_: Exception) { null } ?: return startDate
    val e = try { iso.parse(endDate) } catch (_: Exception) { null } ?: return endDate
    return "${my.format(s)} – ${my.format(e)}"
}

internal fun formatPrettyDayHeader(dateStr: String): String {
    val iso = HEATMAP_ISO_DATE.get() ?: return dateStr
    val out = HEATMAP_PRETTY_DAY.get() ?: return dateStr
    val parsed = try { iso.parse(dateStr) } catch (_: Exception) { null } ?: return dateStr
    return out.format(parsed)
}

internal fun formatSessionLocalTime(startedAt: String): String {
    val clean = startedAt.trim().replace("Z", "").replace("+00:00", "")
    val parsers = HEATMAP_TIME_PARSERS.get().orEmpty()
    for (i in HEATMAP_TIME_PATTERNS.indices) {
        try {
            val parsed = parsers[i].parse(clean.take(HEATMAP_TIME_PATTERNS[i].length))
            if (parsed != null) {
                return HEATMAP_LOCAL_TIME.get()?.format(parsed) ?: ""
            }
        } catch (_: Exception) {
            // Try next pattern
        }
    }
    return ""
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
                letterSpacing = (-0.5).sp,
                modifier = Modifier.weight(1f)
            )

            IconButton(
                onClick = { if (!isLoading) viewModel.loadStudentHeatmap() },
                enabled = !isLoading
            ) {
                if (isLoading && heatmap != null) {
                    CircularProgressIndicator(
                        color = accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
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
                val columns = remember(heatmap.startDate, heatmap.endDate) {
                    buildHeatmapCalendarColumns(heatmap.startDate, heatmap.endDate)
                }
                val activeDay = selectedDate ?: heatmap.endDate
                val activeSnapshot = selection.daySnapshotsByDate[activeDay]
                val daySessions = selection.sessionsByDate[activeDay].orEmpty()

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (errorMsg != null) {
                        item(key = "heatmap_banner_err") {
                            Text(
                                text = errorMsg,
                                fontSize = 12.5.sp,
                                color = colors.dangerRed,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }

                    // Summary metrics island
                    item(key = "summary_island") {
                        HeatmapSummaryCard(
                            summary = selection.summary,
                            accent = accent
                        )
                    }

                    // Subject filter chip strip when courses are present
                    if (heatmap.courses.isNotEmpty()) {
                        item(key = "course_chips") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                HeatmapFilterChip(
                                    label = "All",
                                    selected = selectedCourseCode == null,
                                    accent = accent,
                                    onClick = { selectedCourseCode = null }
                                )
                                heatmap.courses.forEach { course ->
                                    HeatmapFilterChip(
                                        label = course.courseCode,
                                        selected = selectedCourseCode == course.courseCode,
                                        accent = accent,
                                        onClick = {
                                            selectedCourseCode = if (selectedCourseCode == course.courseCode) {
                                                null
                                            } else {
                                                course.courseCode
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // GitHub-style calendar heatmap island
                    item(key = "heatmap_grid") {
                        HeatmapGridCard(
                            startDate = heatmap.startDate,
                            endDate = heatmap.endDate,
                            columns = columns,
                            snapshotsByDate = selection.daySnapshotsByDate,
                            selectedDate = activeDay,
                            totalSessionsCount = selection.summary.totalSessions,
                            accent = accent,
                            onSelectDate = { tappedDate -> selectedDate = tappedDate }
                        )
                    }

                    // Day details header
                    item(key = "day_details_header") {
                        AnimatedContent(
                            targetState = Pair(activeDay, activeSnapshot),
                            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                            label = "day_header_transition"
                        ) { (dayKey, snap) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = formatPrettyDayHeader(dayKey),
                                    fontSize = 15.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary
                                )
                                val countSummary = when {
                                    snap == null -> "No sessions"
                                    else -> buildList {
                                        if (snap.presentCount > 0) add("${snap.presentCount} Present")
                                        if (snap.absentCount > 0) add("${snap.absentCount} Absent")
                                        if (snap.leaveCount > 0) add("${snap.leaveCount} Leave")
                                    }.joinToString(" · ").ifBlank { "No sessions" }
                                }
                                Text(
                                    text = countSummary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textSecondary
                                )
                            }
                        }
                    }

                    // Selected day sessions list (or empty card)
                    if (daySessions.isEmpty()) {
                        item(key = "day_sessions_empty") {
                            GlassSurface(
                                shape = RoundedCornerShape(20.dp),
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
                                totalCount = daySessions.size,
                                accent = accent
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapSummaryCard(
    summary: StudentHeatmapSummary,
    accent: Color
) {
    val colors = AppTheme.colors
    val countable = summary.presentCount + summary.absentCount
    val pctText = summary.attendancePercentage?.let { pct ->
        if (pct % 1.0 == 0.0) "${pct.toInt()}%" else "${pct}%"
    } ?: "—"

    GlassSurface(
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                    if (summary.longestStreakDays > summary.currentStreakDays) {
                        Text(
                            text = "Best ${summary.longestStreakDays}d",
                            fontSize = 11.sp,
                            color = colors.textSecondary.copy(alpha = 0.78f)
                        )
                    }
                }
            }

            Text(
                text = "Consecutive class days (leaves excluded)",
                fontSize = 11.sp,
                color = colors.textSecondary.copy(alpha = 0.72f)
            )
        }
    }
}

@Composable
private fun HeatmapFilterChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    val colors = AppTheme.colors
    GlassSurface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        nested = true,
        border = if (selected) BorderStroke(1.dp, accent.copy(alpha = 0.58f)) else null
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (selected) Modifier.background(accent.copy(alpha = 0.20f))
                    else Modifier
                )
                .padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Text(
                text = label,
                fontSize = 12.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) colors.textPrimary else colors.textSecondary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun HeatmapGridCard(
    startDate: String,
    endDate: String,
    columns: List<HeatmapWeekColumn>,
    snapshotsByDate: Map<String, HeatmapDaySnapshot>,
    selectedDate: String,
    totalSessionsCount: Int,
    accent: Color,
    onSelectDate: (String) -> Unit
) {
    val colors = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()

    LaunchedEffect(columns.size) {
        if (columns.isNotEmpty()) {
            listState.scrollToItem(columns.lastIndex)
        }
    }

    val cellSize = 16.dp
    val cellGap = 4.dp
    val monthHeaderHeight = 18.dp
    val dayAxisLabels = remember { listOf("M", "", "W", "", "F", "", "S") }

    GlassSurface(
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatHeatmapRangeTitle(startDate, endDate),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
                Text(
                    text = "$totalSessionsCount ${if (totalSessionsCount == 1) "class" else "classes"}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textSecondary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Sticky left Mon-Sun axis
                Column(
                    verticalArrangement = Arrangement.spacedBy(cellGap),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Spacer(modifier = Modifier.height(monthHeaderHeight))
                    dayAxisLabels.forEach { label ->
                        Box(
                            modifier = Modifier.height(cellSize),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = label,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textSecondary.copy(alpha = 0.72f)
                            )
                        }
                    }
                }

                // Horizontally scrollable week columns
                LazyRow(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(cellGap)
                ) {
                    itemsIndexed(
                        items = columns,
                        key = { _, col -> col.weekIndex }
                    ) { _, col ->
                        Column(
                            verticalArrangement = Arrangement.spacedBy(cellGap),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(cellSize)
                                    .height(monthHeaderHeight),
                                contentAlignment = Alignment.BottomStart
                            ) {
                                if (!col.monthLabel.isNullOrBlank()) {
                                    Text(
                                        text = col.monthLabel,
                                        fontSize = 9.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.textSecondary.copy(alpha = 0.85f),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }

                            col.days.forEach { dateStr ->
                                if (dateStr == null) {
                                    Spacer(modifier = Modifier.size(cellSize))
                                } else {
                                    val snap = snapshotsByDate[dateStr]
                                    val (fill, rim) = heatmapCellColors(snap, accent, colors)
                                    val isSelected = dateStr == selectedDate
                                    val cellDesc = buildCellDescription(dateStr, snap)

                                    Box(
                                        modifier = Modifier
                                            .size(cellSize)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(fill)
                                            .border(
                                                width = if (isSelected) 1.5.dp else 0.75.dp,
                                                color = if (isSelected) colors.textPrimary else rim,
                                                shape = RoundedCornerShape(4.dp)
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

            // Legend row: Missed & Leave on left, dynamic ramp Less -> More on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LegendMarker(
                        label = "Missed",
                        fill = colors.dangerRed.copy(alpha = 0.24f),
                        rim = colors.dangerRed.copy(alpha = 0.55f)
                    )
                    LegendMarker(
                        label = "Leave",
                        fill = colors.infoBlue.copy(alpha = 0.22f),
                        rim = colors.infoBlue.copy(alpha = 0.55f)
                    )
                }

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
                        Color.White.copy(alpha = 0.05f) to Color.White.copy(alpha = 0.09f),
                        accent.copy(alpha = 0.24f) to accent.copy(alpha = 0.38f),
                        accent.copy(alpha = 0.46f) to accent.copy(alpha = 0.62f),
                        accent.copy(alpha = 0.74f) to accent.copy(alpha = 0.88f),
                        mixGlassColor(accent, Color.White, 0.22f) to Color.White.copy(alpha = 0.85f)
                    ).forEach { (fill, rim) ->
                        Box(
                            modifier = Modifier
                                .size(11.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(fill)
                                .border(0.75.dp, rim, RoundedCornerShape(3.dp))
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
        }
    }
}

@Composable
private fun LegendMarker(
    label: String,
    fill: Color,
    rim: Color
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(11.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(fill)
                .border(0.75.dp, rim, RoundedCornerShape(3.dp))
        )
        Text(
            text = label,
            fontSize = 10.sp,
            color = AppTheme.colors.textSecondary.copy(alpha = 0.82f)
        )
    }
}

private fun heatmapCellColors(
    snapshot: HeatmapDaySnapshot?,
    accent: Color,
    colors: com.college.core.ui.theme.AppColors
): Pair<Color, Color> {
    if (snapshot == null || snapshot.totalSessionsCount == 0) {
        return Color.White.copy(alpha = 0.05f) to Color.White.copy(alpha = 0.09f)
    }
    if (snapshot.presentCount == 0) {
        return if (snapshot.absentCount > 0) {
            colors.dangerRed.copy(alpha = 0.24f) to colors.dangerRed.copy(alpha = 0.55f)
        } else {
            colors.infoBlue.copy(alpha = 0.22f) to colors.infoBlue.copy(alpha = 0.55f)
        }
    }
    return when (snapshot.level) {
        1 -> accent.copy(alpha = 0.24f) to accent.copy(alpha = 0.38f)
        2 -> accent.copy(alpha = 0.46f) to accent.copy(alpha = 0.62f)
        3 -> accent.copy(alpha = 0.74f) to accent.copy(alpha = 0.88f)
        else -> mixGlassColor(accent, Color.White, 0.22f) to Color.White.copy(alpha = 0.85f)
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
    totalCount: Int,
    accent: Color
) {
    val colors = AppTheme.colors
    val normalizedStatus = session.status.uppercase()
    val statusLabel: String
    val pillBg: Color
    val pillText: Color

    when (normalizedStatus) {
        "PRESENT" -> {
            statusLabel = "Present"
            pillBg = accent.copy(alpha = 0.22f)
            pillText = mixGlassColor(accent, Color.White, 0.30f)
        }
        "LEAVE" -> {
            statusLabel = "Leave"
            pillBg = colors.infoBlue.copy(alpha = 0.22f)
            pillText = mixGlassColor(colors.infoBlue, Color.White, 0.35f)
        }
        else -> {
            statusLabel = "Absent"
            pillBg = colors.dangerRed.copy(alpha = 0.22f)
            pillText = mixGlassColor(colors.dangerRed, Color.White, 0.25f)
        }
    }

    val title = session.subjectName?.takeIf { it.isNotBlank() }
        ?: session.courseName?.takeIf { it.isNotBlank() }
        ?: session.courseCode?.takeIf { it.isNotBlank() }
        ?: "Class Session"

    val timeLabel = remember(session.startedAt) { formatSessionLocalTime(session.startedAt) }
    val metaLine = remember(session, timeLabel) {
        buildList {
            session.courseCode?.takeIf { it.isNotBlank() && it != title }?.let { add(it) }
            add(session.batchType)
            if (!session.classType.equals("Regular", ignoreCase = true)) {
                add(session.classType)
            }
            if (timeLabel.isNotBlank()) add(timeLabel)
        }.joinToString(" · ")
    }
    val detailLine = remember(session.unit, session.topicCovered) {
        listOfNotNull(
            session.unit?.takeIf { it.isNotBlank() },
            session.topicCovered?.takeIf { it.isNotBlank() }
        ).joinToString(" • ")
    }

    GlassSurface(
        shape = connectedCardSliceShape(index, totalCount, 20.dp),
        sliceEdge = connectedSliceEdge(index, totalCount),
        sliceCornerRadius = 20.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (index > 0) {
                GlassDivider(modifier = Modifier.fillMaxWidth())
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = metaLine,
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (detailLine.isNotBlank()) {
                        Text(
                            text = detailLine,
                            fontSize = 11.5.sp,
                            color = colors.textSecondary.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(pillBg)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = statusLabel,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = pillText
                    )
                }
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
