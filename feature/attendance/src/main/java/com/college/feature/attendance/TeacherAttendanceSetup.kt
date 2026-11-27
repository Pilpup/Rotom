package com.college.feature.attendance

import com.college.core.ui.components.GlassGlowButton
import com.college.core.ui.components.GlassSurface
import com.college.core.ui.components.glassButtonTransition
import com.college.core.ui.components.profileAccentColor
import com.college.core.ui.theme.AppTheme
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.layout.LayoutCoordinates
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

@Composable
internal fun AppDropdownField(
    label: String,
    selectedValue: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select $label",
    scrollable: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidthPx by remember { mutableIntStateOf(0) }
    var anchorHeightPx by remember { mutableIntStateOf(0) }
    val anchorCoordsHolder = remember { arrayOfNulls<LayoutCoordinates>(1) }

    val accent = profileAccentColor()
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            anchorCoordsHolder[0] = coordinates
            if (coordinates.size.width != anchorWidthPx) anchorWidthPx = coordinates.size.width
            if (coordinates.size.height != anchorHeightPx) anchorHeightPx = coordinates.size.height
        }
    ) {
        // Compact rounded island container without orange press/hold highlight
        GlassSurface(
            shape = shape,
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .defaultMinSize(minHeight = 30.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (expanded) AppTheme.colors.textPrimary else AppTheme.colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = selectedValue.ifEmpty { placeholder },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selectedValue.isNotEmpty()) AppTheme.colors.textPrimary else AppTheme.colors.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = "Select $label",
                    tint = if (expanded) AppTheme.colors.textPrimary else AppTheme.colors.textSecondary,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(if (expanded) 180f else 0f)
                )
            }
        }

        if (expanded) {
            val density = LocalDensity.current
            val offsetDownPx = with(density) { 4.dp.roundToPx() }
            val positionProvider = remember(density) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize
                    ): IntOffset {
                        return IntOffset(
                            x = anchorBounds.left,
                            y = anchorBounds.bottom + offsetDownPx
                        )
                    }
                }
            }

            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                val anchorWidthDp = with(density) { anchorWidthPx.toDp() }
                val popupOrigin = remember(expanded) {
                    val coords = anchorCoordsHolder[0]
                    if (coords != null && coords.isAttached) {
                        val pos = coords.localToRoot(Offset.Zero)
                        Offset(pos.x, pos.y + anchorHeightPx + offsetDownPx)
                    } else {
                        null
                    }
                }
                GlassSurface(
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
                    frosted = true,
                    viewportOrigin = popupOrigin,
                    modifier = Modifier.width(if (anchorWidthDp > 0.dp) anchorWidthDp else 280.dp)
                ) {
                    val columnModifier = if (scrollable) {
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    }

                    Column(
                        modifier = columnModifier
                    ) {
                        options.forEach { option ->
                            val isSelected = option == selectedValue
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isSelected) accent.copy(alpha = 0.22f) else Color.Transparent)
                                    .clickable {
                                        onSelect(option)
                                        expanded = false
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    text = option,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
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

// Course selection and session configuration form
@Composable
internal fun TakeAttendanceSelectionView(
    onStartSession: () -> Unit,
    onSelectCourse: (CourseResponse) -> Unit,
    onSelectSessionMetadata: (classType: String, batchType: String, unit: String?, topicCovered: String?) -> Unit = { _, _, _, _ -> },
    isCloudSyncing: Boolean,
    cloudStatusMessage: String?,
    availableCourses: List<CourseResponse> = emptyList()
) {
    var classType by remember { mutableStateOf("Regular") }
    var courseProgram by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var semester by remember { mutableStateOf("") }
    var batchType by remember { mutableStateOf("Lecture") }
    var subjectName by remember { mutableStateOf("NONE") }
    var selectedUnit by remember { mutableStateOf("NONE") }
    var topicCovered by remember { mutableStateOf("NONE") }

    val programOptions = remember(availableCourses) {
        availableCourses.mapNotNull { it.program?.replace(".", "-")?.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    val branchOptions = remember(availableCourses, courseProgram) {
        availableCourses
            .filter { courseProgram.isBlank() || it.program?.replace(".", "-").equals(courseProgram.replace(".", "-"), ignoreCase = true) }
            .mapNotNull { it.department?.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    val semesterOptions = remember(availableCourses, courseProgram, branch) {
        availableCourses
            .filter { courseProgram.isBlank() || it.program?.replace(".", "-").equals(courseProgram.replace(".", "-"), ignoreCase = true) }
            .filter { branch.isBlank() || it.department.equals(branch, ignoreCase = true) }
            .mapNotNull { course ->
                val raw = course.semester?.trim().orEmpty()
                val num = raw.filter { it.isDigit() }.toIntOrNull()
                when {
                    num != null -> "Semester $num"
                    raw.isNotBlank() -> raw
                    else -> null
                }
            }
            .distinct()
            .sortedBy { it.filter { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }
    }

    LaunchedEffect(programOptions) {
        if (courseProgram !in programOptions) courseProgram = programOptions.firstOrNull().orEmpty()
    }
    LaunchedEffect(branchOptions) {
        if (branch !in branchOptions) branch = branchOptions.firstOrNull().orEmpty()
    }
    LaunchedEffect(semesterOptions) {
        if (semester !in semesterOptions) semester = semesterOptions.firstOrNull().orEmpty()
    }

    val dbMatchingCourses = remember(availableCourses, courseProgram, branch, semester) {
        val semNum = semester.filter { it.isDigit() }.toIntOrNull()
        availableCourses.filter { course ->
            val progMatches = courseProgram.isBlank() || course.program?.replace(".", "-").equals(courseProgram.replace(".", "-"), ignoreCase = true)
            val branchMatches = branch.isBlank() || course.department.equals(branch, ignoreCase = true)
            val semMatches = semNum == null || (course.semester?.filter { it.isDigit() }?.toIntOrNull() == semNum)
            progMatches && branchMatches && semMatches
        }
    }

    val currentSubjects = remember(dbMatchingCourses) {
        dbMatchingCourses.map { it.courseName }.filter { it.isNotBlank() }.distinct()
    }

    val selectedCourseObj = remember(dbMatchingCourses, subjectName) {
        dbMatchingCourses.firstOrNull { it.courseName.equals(subjectName, ignoreCase = true) }
    }

    val unitOptions = remember(selectedCourseObj) {
        selectedCourseObj?.units
            ?.sortedBy { it.unitNumber }
            ?.map { u -> "Unit ${u.unitNumber}" }
            ?: emptyList()
    }

    fun unitNumberFromLabel(label: String): Int? =
        Regex("""Unit\s*(\d+)""", RegexOption.IGNORE_CASE).find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: label.filter { it.isDigit() }.toIntOrNull()

    val availableTopics = remember(selectedCourseObj, selectedUnit) {
        val unitNum = unitNumberFromLabel(selectedUnit) ?: return@remember emptyList<String>()
        selectedCourseObj?.units
            ?.firstOrNull { it.unitNumber == unitNum }
            ?.topics
            ?.sortedBy { it.topicOrder }
            ?.mapNotNull { it.topicName.takeIf(String::isNotBlank) }
            ?: emptyList()
    }

    LaunchedEffect(currentSubjects) {
        if (subjectName !in currentSubjects) {
            subjectName = currentSubjects.firstOrNull() ?: "NONE"
            selectedUnit = "NONE"
            topicCovered = "NONE"
        }
    }

    LaunchedEffect(subjectName, selectedCourseObj) {
        if (subjectName == "NONE" || selectedCourseObj == null) {
            selectedUnit = "NONE"
            topicCovered = "NONE"
            return@LaunchedEffect
        }
        if (selectedUnit !in unitOptions) {
            selectedUnit = unitOptions.firstOrNull() ?: "NONE"
            topicCovered = "NONE"
        }
        val isLab = subjectName.contains("Lab", ignoreCase = true) || subjectName.contains("Project", ignoreCase = true)
        batchType = if (isLab) "Lab" else if (batchType == "Lab") "Lecture" else batchType
    }

    LaunchedEffect(selectedUnit, availableTopics) {
        if (selectedUnit == "NONE") {
            topicCovered = "NONE"
        } else if (topicCovered !in availableTopics) {
            topicCovered = availableTopics.firstOrNull() ?: "NONE"
        }
    }

    LaunchedEffect(classType, batchType, subjectName, selectedUnit, topicCovered, selectedCourseObj) {
        val course = selectedCourseObj ?: return@LaunchedEffect
        if (subjectName == "NONE") return@LaunchedEffect
        onSelectCourse(course)
        onSelectSessionMetadata(
            classType,
            batchType,
            selectedUnit.takeUnless { it.isBlank() || it == "NONE" },
            topicCovered.takeUnless { it.isBlank() || it == "NONE" }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val batchOptions = listOf("Lecture", "Lab", "Tutorial")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AppDropdownField(
                label = "Class Type",
                selectedValue = classType,
                options = listOf("Regular", "Extra", "Adjustment"),
                onSelect = { classType = it },
                placeholder = "Select Class Type",
                modifier = Modifier.weight(1f),
                scrollable = false
            )
            AppDropdownField(
                label = "Program",
                selectedValue = courseProgram,
                options = programOptions,
                onSelect = { courseProgram = it },
                placeholder = "Select Program",
                modifier = Modifier.weight(1f),
                scrollable = false
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AppDropdownField(
                label = "Branch",
                selectedValue = branch,
                options = branchOptions,
                onSelect = { branch = it },
                placeholder = "Select Branch",
                modifier = Modifier.weight(1f),
                scrollable = false
            )
            AppDropdownField(
                label = "Semester",
                selectedValue = semester,
                options = semesterOptions,
                onSelect = { semester = it },
                placeholder = "Select Semester",
                modifier = Modifier.weight(1f),
                scrollable = false
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AppDropdownField(
                label = "Batch",
                selectedValue = batchType,
                options = batchOptions,
                onSelect = { batchType = it },
                placeholder = "Select Batch",
                modifier = Modifier.weight(1f),
                scrollable = false
            )
            AppDropdownField(
                label = "Unit",
                selectedValue = selectedUnit,
                options = unitOptions,
                onSelect = { selectedUnit = it },
                placeholder = "Select Unit",
                modifier = Modifier.weight(1f),
                scrollable = false
            )
        }

        AppDropdownField(
            label = "Subject Name",
            selectedValue = subjectName,
            options = currentSubjects,
            onSelect = { subjectName = it },
            placeholder = "Select Subject",
            modifier = Modifier.fillMaxWidth(),
            scrollable = true
        )

        AppDropdownField(
            label = "Topic Covered",
            selectedValue = topicCovered,
            options = availableTopics,
            onSelect = { topicCovered = it },
            placeholder = "Select Topic Covered",
            modifier = Modifier.fillMaxWidth(),
            scrollable = false
        )

        // Cloud sync and audit status banner
        if (cloudStatusMessage != null || isCloudSyncing) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isCloudSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AppTheme.colors.accentOrange)
                    }
                    Text(
                        text = cloudStatusMessage ?: "Auditing attendance...",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppTheme.colors.accentOrange
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Start Attendance Button in Electric Purple
        val canStart = selectedCourseObj != null &&
            subjectName != "NONE" &&
            selectedUnit != "NONE" &&
            (availableTopics.isEmpty() || topicCovered != "NONE")
        GlassGlowButton(
            onClick = onStartSession,
            enabled = canStart,
            fillMaxWidth = true,
            minHeight = 52.dp,
            shape = RoundedCornerShape(14.dp),
            glowColor = profileAccentColor(),
            glowActive = canStart
        ) {
            AnimatedContent(
                targetState = canStart,
                transitionSpec = glassButtonTransition(),
                label = "start_attendance_state"
            ) { ready ->
                Text(
                    text = "Start Attendance",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (ready) AppTheme.colors.textPrimary else AppTheme.colors.textMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// Formats session start timestamp into clean date and time
