package com.college.feature.attendance

import com.college.core.ui.components.GlassDivider
import com.college.core.ui.components.GlassSurface
import com.college.core.ui.theme.AppTheme
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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

internal fun formatSessionDateTime(raw: String?): String {
    if (raw.isNullOrBlank()) return "Past Session"
    val trimmed = raw.trim()
    // Server timestamps are UTC (trailing Z or +00:00); stripping the zone and
    // parsing in the device-default timezone shifted session times by the
    // local UTC offset. Parse in UTC, format in the device timezone.
    val clean = trimmed.replace("Z", "").replace("+00:00", "")
    val utcZone = java.util.TimeZone.getTimeZone("UTC")
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd"
    )
    for (pat in patterns) {
        try {
            val sdf = SimpleDateFormat(pat, Locale.getDefault())
            sdf.isLenient = false
            sdf.timeZone = utcZone
            val parsed = sdf.parse(clean.take(pat.length))
            if (parsed != null) {
                val outFormat = if (clean.length >= 16) {
                    SimpleDateFormat("dd MMM yyyy • hh:mm a", Locale.getDefault())
                } else {
                    SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                }
                return outFormat.format(parsed)
            }
        } catch (_: Exception) {
            // Evaluates subsequent date pattern on mismatch
        }
    }
    return trimmed
}

// Read-only metadata island matching the island input styling
@Composable
internal fun AppDetailIsland(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    GlassSurface(
        modifier = modifier,
        shape = shape
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .fillMaxWidth()
                .defaultMinSize(minHeight = 30.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.colors.textSecondary
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = value.ifBlank { "—" },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// Filter dialog for past attendance sessions
@Composable
internal fun OlderSessionsFilterDialog(
    selectedCourse: String?,
    selectedClassType: String?,
    selectedSemester: String?,
    onApply: (course: String?, classType: String?, semester: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var course by remember { mutableStateOf(selectedCourse) }
    var classType by remember { mutableStateOf(selectedClassType) }
    var semester by remember { mutableStateOf(selectedSemester) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.colors.surface,
        titleContentColor = AppTheme.colors.textPrimary,
        textContentColor = AppTheme.colors.textPrimary,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "Filter Sessions",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = AppTheme.colors.textPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Program filter section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Course Program",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.colors.textSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("B-Tech", "BCA").forEach { prog ->
                            val isSel = course == prog
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSel) AppTheme.colors.primaryPurple else AppTheme.colors.surface,
                                border = null,
                                modifier = Modifier.clickable { course = if (isSel) null else prog }
                            ) {
                                Text(
                                    text = prog,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSel) Color.White else AppTheme.colors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // Class type filter section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Class Type",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.colors.textSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Regular", "Extra", "Adjustment").forEach { cls ->
                            val isSel = classType == cls
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSel) AppTheme.colors.primaryPurple else AppTheme.colors.surface,
                                border = null,
                                modifier = Modifier.clickable { classType = if (isSel) null else cls }
                            ) {
                                Text(
                                    text = cls,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSel) Color.White else AppTheme.colors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // Academic semester filter section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Semester",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.colors.textSecondary
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        (1..8).map { "Semester $it" }.forEach { sem ->
                            val isSel = semester == sem
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSel) AppTheme.colors.primaryPurple else AppTheme.colors.surface,
                                border = null,
                                modifier = Modifier.clickable { semester = if (isSel) null else sem }
                            ) {
                                Text(
                                    text = sem,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSel) Color.White else AppTheme.colors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                course = null
                classType = null
                semester = null
                onApply(null, null, null)
                onDismiss()
            }) {
                Text("Reset", color = AppTheme.colors.textSecondary)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onApply(course, classType, semester)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primaryPurple)
            ) {
                Text("Apply", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    )
}

// Past attendance session list sorted by latest first with top filters and bottom search
@Composable
internal fun OlderSessionsView(
    pastSessions: List<AttendanceSessionResponse>,
    isLoading: Boolean,
    onSelectSession: (AttendanceSessionResponse) -> Unit
) {
    val sortedSessions = remember(pastSessions) {
        pastSessions.sortedByDescending { it.startedAt ?: "" }
    }

    var showFilterDialog by remember { mutableStateOf(false) }
    var filterCourse by remember { mutableStateOf<String?>(null) }
    var filterClassType by remember { mutableStateOf<String?>(null) }
    var filterSemester by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val activeFilterCount = (if (filterCourse != null) 1 else 0) +
        (if (filterClassType != null) 1 else 0) +
        (if (filterSemester != null) 1 else 0)

    val filteredSessions = remember(sortedSessions, filterCourse, filterClassType, filterSemester, searchQuery) {
        val curCourse = filterCourse
        val curClassType = filterClassType
        val curSemester = filterSemester
        sortedSessions.filter { session ->
            val details = getSessionAcademicDetails(session)
            val formattedDate = formatSessionDateTime(session.startedAt)

            val matchesCourse = curCourse == null || details.courseProgram.equals(curCourse, ignoreCase = true)
            val matchesClassType = curClassType == null || details.classType.contains(curClassType, ignoreCase = true)
            val matchesSemester = curSemester == null || details.semester.contains(curSemester, ignoreCase = true)

            val matchesSearch = if (searchQuery.isBlank()) true else {
                formattedDate.contains(searchQuery, ignoreCase = true) ||
                    (session.startedAt?.contains(searchQuery, ignoreCase = true) == true) ||
                    details.subjectName.contains(searchQuery, ignoreCase = true) ||
                    details.courseProgram.contains(searchQuery, ignoreCase = true) ||
                    details.branch.contains(searchQuery, ignoreCase = true) ||
                    details.topicCovered.contains(searchQuery, ignoreCase = true)
            }

            matchesCourse && matchesClassType && matchesSemester && matchesSearch
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AppTheme.colors.primaryPurple, strokeWidth = 2.5.dp)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Filter bar at the top with Filter button and All option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Filter dialog button
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (activeFilterCount > 0) AppTheme.colors.primaryPurple.copy(alpha = 0.15f) else AppTheme.colors.surface,
                        border = null,
                        modifier = Modifier.clickable { showFilterDialog = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = "Filter",
                                tint = if (activeFilterCount > 0) AppTheme.colors.primaryPurple else AppTheme.colors.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (activeFilterCount > 0) "Filter ($activeFilterCount)" else "Filter",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (activeFilterCount > 0) AppTheme.colors.primaryPurple else AppTheme.colors.textSecondary
                            )
                        }
                    }

                    // All chip to display all sessions or reset active filters
                    val isAllSelected = activeFilterCount == 0 && searchQuery.isBlank()
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isAllSelected) AppTheme.colors.primaryPurple else AppTheme.colors.surface,
                        border = null,
                        modifier = Modifier.clickable {
                            filterCourse = null
                            filterClassType = null
                            filterSemester = null
                            searchQuery = ""
                        }
                    ) {
                        Text(
                            text = "All",
                            fontSize = 12.5.sp,
                            fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isAllSelected) Color.White else AppTheme.colors.textSecondary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }

                // Active search banner with clear action
                if (searchQuery.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Filtered by: \"$searchQuery\"",
                            fontSize = 12.sp,
                            color = AppTheme.colors.textSecondary
                        )
                        Text(
                            text = "Clear",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.primaryPurple,
                            modifier = Modifier.clickable { searchQuery = "" }
                        )
                    }
                }

                // Single big island containing past sessions
                if (filteredSessions.isEmpty()) {
                    GlassSurface(
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 36.dp, horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (pastSessions.isEmpty()) "No past attendance sessions found" else "No sessions match your filter",
                                    fontSize = 14.sp,
                                    color = AppTheme.colors.textSecondary,
                                    fontWeight = FontWeight.Medium
                                )
                                if (activeFilterCount > 0 || searchQuery.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Reset all filters",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppTheme.colors.primaryPurple,
                                        modifier = Modifier.clickable {
                                            filterCourse = null
                                            filterClassType = null
                                            filterSemester = null
                                            searchQuery = ""
                                        }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    GlassSurface(
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            filteredSessions.forEachIndexed { index, session ->
                                if (index > 0) {
                                    GlassDivider(modifier = Modifier.fillMaxWidth())
                                }

                                // Single session row displaying only date and time
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectSession(session) }
                                        .padding(horizontal = 18.dp, vertical = 15.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = formatSessionDateTime(session.startedAt),
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AppTheme.colors.textPrimary
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = AppTheme.colors.textMuted.copy(alpha = 0.6f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Clearance for floating search pill
                Spacer(modifier = Modifier.height(72.dp))
            }

            // Floating search pill pinned at bottom
            AppSearchPill(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "Search sessions",
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showFilterDialog) {
        OlderSessionsFilterDialog(
            selectedCourse = filterCourse,
            selectedClassType = filterClassType,
            selectedSemester = filterSemester,
            onApply = { c, cls, sem ->
                filterCourse = c
                filterClassType = cls
                filterSemester = sem
            },
            onDismiss = { showFilterDialog = false }
        )
    }
}

// Detailed roster and breakdown view for a selected attendance session
@Composable
internal fun SessionDetailScreen(
    session: AttendanceSessionResponse,
    onExportAttendance: () -> Unit
) {
    val details = remember(session) { getSessionAcademicDetails(session) }
    val records = session.records ?: emptyList()
    val sortedRecords = remember(records) {
        records.sortedWith { r1, r2 ->
            compareRollNumbers(getDisplayRollNo(r1), getDisplayRollNo(r2))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            // Details on top in same island format as while filling in before taking attendance
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AppDetailIsland(
                        label = "Class Type",
                        value = details.classType,
                        modifier = Modifier.weight(1f)
                    )
                    AppDetailIsland(
                        label = "Program",
                        value = details.courseProgram,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AppDetailIsland(
                        label = "Branch",
                        value = details.branch.ifBlank { "N/A" },
                        modifier = Modifier.weight(1f)
                    )
                    AppDetailIsland(
                        label = "Semester",
                        value = details.semester,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AppDetailIsland(
                        label = "Batch",
                        value = details.batchType,
                        modifier = Modifier.weight(1f)
                    )
                    AppDetailIsland(
                        label = "Unit",
                        value = details.unit,
                        modifier = Modifier.weight(1f)
                    )
                }

                AppDetailIsland(
                    label = "Subject Name",
                    value = details.subjectName,
                    modifier = Modifier.fillMaxWidth()
                )

                AppDetailIsland(
                    label = "Topic Covered",
                    value = details.topicCovered,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(6.dp))

            // Attendance list title with grey Export text option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Attendance list",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.textPrimary
                )

                Text(
                    text = "Export",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppTheme.colors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onExportAttendance() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Attendance student roster unified into a single island
        if (sortedRecords.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(AppTheme.colors.surface, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No students recorded in this session.",
                        fontSize = 13.sp,
                        color = textMuted
                    )
                }
            }
        } else {
            item {
                GlassSurface(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        sortedRecords.forEachIndexed { index, rec ->
                            if (index > 0) {
                                GlassDivider(modifier = Modifier.fillMaxWidth())
                            }

                            val isPresent = isRecordPresent(rec)
                            val statusText = if (isPresent) "Present" else "Absent"
                            val statusBg = if (isPresent) AppTheme.colors.primaryPurple else AppTheme.colors.accentOrange

                            val rollNo = remember(rec) { getDisplayRollNo(rec) }
                            val shortRoll = remember(rollNo) { getRollNumberTwoDigits(rollNo) }
                            val studentName = remember(rec) { getDisplayStudentName(rec) }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Roll number badge circle
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(AppTheme.colors.background, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = shortRoll,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = AppTheme.colors.textPrimary
                                    )
                                }

                                // Student name
                                Text(
                                    text = studentName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppTheme.colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                // Chimp challenge score (blank when absent)
                                rec.gameScore?.let { score ->
                                    Text(
                                        text = "Score $score",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = AppTheme.colors.textSecondary
                                    )
                                }

                                // Present or Absent status pill
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = statusBg,
                                    border = null
                                ) {
                                    Text(
                                        text = statusText,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
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

@Immutable
internal data class SessionAcademicDetails(
    val classType: String,
    val courseProgram: String,
    val branch: String,
    val semester: String,
    val batchType: String,
    val subjectName: String,
    val unit: String,
    val topicCovered: String
)

private val SEMESTER_REGEX_1 = Regex("Semester\\s*\\d", RegexOption.IGNORE_CASE)
private val SEMESTER_REGEX_2 = Regex("Sem\\s*\\d", RegexOption.IGNORE_CASE)
private val UNIT_REGEX = Regex("Unit\\s*\\d", RegexOption.IGNORE_CASE)

// Extracts a clean subject name from course name metadata or course code
internal fun extractSubjectName(rawCourseName: String?, courseCode: String?): String {
    val raw = rawCourseName?.trim().orEmpty()
    if (raw.isBlank()) return courseCode?.trim()?.takeIf { it.isNotBlank() } ?: "NONE"

    if (raw.contains("•")) {
        val prefix = raw.substringBefore("•").trim()
        val isClassTypePrefix = prefix.startsWith("Regular", ignoreCase = true) ||
            prefix.startsWith("Extra", ignoreCase = true) ||
            prefix.startsWith("Adjustment", ignoreCase = true) ||
            prefix.startsWith("Lecture", ignoreCase = true) ||
            prefix.startsWith("Lab", ignoreCase = true) ||
            prefix.startsWith("Tutorial", ignoreCase = true)
        if (!isClassTypePrefix && prefix.isNotBlank()) return prefix
    }

    val isClassTypeOnly = raw.startsWith("Regular", ignoreCase = true) ||
        raw.startsWith("Extra", ignoreCase = true) ||
        raw.startsWith("Adjustment", ignoreCase = true)
    return if (isClassTypeOnly) "NONE" else raw
}

// Parses and reconstructs academic session fields from session response metadata
internal fun getSessionAcademicDetails(session: AttendanceSessionResponse): SessionAcademicDetails {
    val courseProg = session.courseProgram
        ?: session.courseCode?.takeIf { it.isNotBlank() }
        ?: "N/A"
    val branch = session.courseCode?.let { code ->
        when {
            code.contains("CSE", ignoreCase = true) -> "CSE"
            code.contains("ME", ignoreCase = true) -> "ME"
            code.contains("ECE", ignoreCase = true) -> "ECE"
            code.contains("EE", ignoreCase = true) -> "EE"
            code.contains("CE", ignoreCase = true) -> "CE"
            code.contains("IT", ignoreCase = true) -> "IT"
            else -> null
        }
    } ?: "N/A"
    val sem = session.semester
        ?: SEMESTER_REGEX_1.find(session.courseCode ?: "")?.value
        ?: SEMESTER_REGEX_2.find(session.courseCode ?: "")?.value
        ?: "N/A"

    val rawName = session.courseName ?: ""
    val subject = session.subjectName?.takeIf { it.isNotBlank() }
        ?: extractSubjectName(rawName, session.courseCode)
    val remainder = if (rawName.startsWith(subject, ignoreCase = true)) {
        rawName.substring(subject.length).trim().removePrefix("•").trim()
    } else if (rawName.contains("•")) {
        rawName.substringAfter("•").trim()
    } else {
        rawName
    }

    val clsType = session.classType ?: when {
        remainder.contains("Extra", ignoreCase = true) -> "Extra"
        remainder.contains("Adjustment", ignoreCase = true) -> "Adjustment"
        else -> "Regular"
    }
    val batch = session.batchType ?: if (remainder.contains("Lab", ignoreCase = true) || rawName.contains("Lab", ignoreCase = true)) "Lab" else "Lecture"
    val unit = session.unit
        ?: UNIT_REGEX.find(remainder)?.value
        ?: UNIT_REGEX.find(rawName)?.value
        ?: "N/A"
    val topic = session.topicCovered?.takeIf { it.isNotBlank() } ?: run {
        if (rawName.contains("(") && rawName.contains(")")) {
            val inside = rawName.substringAfter("(").substringBefore(")").trim()
            when {
                inside.contains("•") -> inside.substringAfter("•").trim()
                !inside.startsWith("Unit", ignoreCase = true) && !inside.startsWith("Exp", ignoreCase = true) -> inside
                remainder.contains("•") -> remainder.substringAfterLast("•").trim()
                else -> "N/A"
            }
        } else if (remainder.contains("•")) {
            val last = remainder.substringAfterLast("•").trim()
            if (!last.startsWith("Unit", ignoreCase = true)) last else remainder
        } else if (remainder.isNotBlank()) {
            remainder
        } else {
            "N/A"
        }
    }
    return SessionAcademicDetails(clsType, courseProg, branch, sem, batch, subject, unit, topic)
}

