package com.college.feature.leave

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import com.college.core.ui.components.GlassDivider
import com.college.core.ui.components.GlassGlowButton
import com.college.core.ui.components.GlassSurface
import com.college.core.ui.components.glassButtonTransition
import com.college.core.ui.components.profileAccentColor
import com.college.core.ui.theme.AppTheme
import com.college.feature.leave.network.LeaveApplicationResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ---------------------------------------------------------------------------
// Timezone-safe date helpers.
//
// Material3's date picker emits UTC-midnight millis, and the wire format is a
// bare calendar date ("YYYY-MM-DD"). Everything here treats those values as
// LOCAL CALENDAR DATES — no default-timezone instantiation, no UTC offset
// shifts — so a range picked on the phone is never off by one day when
// compared against the server's dates.
// ---------------------------------------------------------------------------

// Thread-cached formatters avoid rebuilding SimpleDateFormat instances per card/recomposition.
private val LEAVE_DATE_PARSER = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}
private val PRETTY_LEAVE_DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)
}
private val LEAVE_TS_PATTERNS = listOf(
    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    "yyyy-MM-dd'T'HH:mm:ssXXX",
    "yyyy-MM-dd'T'HH:mm:ss"
)
private val LEAVE_TS_PARSERS = object : ThreadLocal<List<SimpleDateFormat>>() {
    override fun initialValue(): List<SimpleDateFormat> = LEAVE_TS_PATTERNS.map { pat ->
        SimpleDateFormat(pat, Locale.US)
    }
}
private val PRETTY_LEAVE_TS_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US)
}
private val LEAVE_MILLI_REGEX = Regex("\\.(\\d{3})\\d*")

private const val MS_PER_DAY = 86_400_000L

// Today's UTC-midnight millis — the lower bound for selectable leave dates.
fun todayUtcMidnightMillis(): Long {
    val now = System.currentTimeMillis()
    return now - (now % MS_PER_DAY)
}

// Parses a wire date ("YYYY-MM-DD") into UTC-midnight millis, or null when
// malformed. Same scheme the picker uses, so comparisons stay exact.
fun parseLeaveDate(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return try {
        LEAVE_DATE_PARSER.get()?.parse(raw)?.time
    } catch (_: ParseException) {
        null
    }
}

fun formatLeaveDate(millis: Long?): String {
    if (millis == null) return ""
    return LEAVE_DATE_PARSER.get()?.format(Date(millis)).orEmpty()
}

// Interval intersection between a stored [from,to] and a candidate [from,to].
fun leaveRangesOverlap(aFrom: Long, aTo: Long, bFrom: Long, bTo: Long): Boolean =
    !(aTo < bFrom || aFrom > bTo)

// "2026-11-13" -> "13 Nov 2026" for card display
fun prettyLeaveDate(isoDate: String): String {
    val millis = parseLeaveDate(isoDate) ?: return isoDate
    return PRETTY_LEAVE_DATE_FORMAT.get()?.format(Date(millis)) ?: isoDate
}

// Renders an ISO timestamp from the API as "13 Nov 2026, 9:41 AM". On any
// surprise format the raw text is shown instead of crashing.
fun prettyLeaveTimestamp(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val normalized = iso.replace("Z", "+00:00")
    val withMilli = normalized.replace(LEAVE_MILLI_REGEX) { m -> ".${m.groupValues[1]}" }
    val parsers = LEAVE_TS_PARSERS.get().orEmpty()
    for (parser in parsers) {
        try {
            val parsed = parser.parse(withMilli) ?: continue
            return PRETTY_LEAVE_TS_FORMAT.get()?.format(parsed).orEmpty()
        } catch (_: ParseException) {
            // try next pattern
        }
    }
    return normalized.take(16).replace("T", " ")
}

private const val MAX_REASON_LENGTH = 1000
private const val MIN_REASON_LENGTH = 3

// ---------------------------------------------------------------------------
// Leave application form: From/To date selection with chronological validation,
// overlap prevention, and the applicant reason with a live character counter.
// All errors render inline next to the field they belong to.
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaveApplicationForm(
    applications: List<LeaveApplicationResponse>,
    isSubmitting: Boolean,
    message: String?,
    onSubmit: (fromDate: String, toDate: String, reason: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var fromMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var toMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
    var reason by rememberSaveable { mutableStateOf("") }

    val fromText = formatLeaveDate(fromMillis)
    val toText = formatLeaveDate(toMillis)

    // Overlap prevention against the student's own live (PENDING/APPROVED)
    // requests — mirrors the server-side guard so the error shows before any
    // network round trip.
    val overlapWith: LeaveApplicationResponse? = remember(applications, fromMillis, toMillis) {
        val newFrom = fromMillis
        val newTo = toMillis
        if (newFrom == null || newTo == null) {
            null
        } else {
            applications.firstOrNull { app ->
                (app.status.equals("PENDING", true) || app.status.equals("APPROVED", true)) &&
                        leaveRangesOverlap(
                            aFrom = parseLeaveDate(app.fromDate) ?: return@firstOrNull false,
                            aTo = parseLeaveDate(app.toDate) ?: return@firstOrNull false,
                            bFrom = newFrom,
                            bTo = newTo
                        )
            }
        }
    }

    val reasonTrimmed = reason.trim()
    val reasonError = when {
        reasonTrimmed.isNotEmpty() && reasonTrimmed.length < MIN_REASON_LENGTH ->
            "Reason must be at least $MIN_REASON_LENGTH characters"
        reason.length > MAX_REASON_LENGTH -> "Reason is too long"
        else -> null
    }

    val canSubmit = fromMillis != null && toMillis != null &&
            overlapWith == null &&
            reasonTrimmed.length >= MIN_REASON_LENGTH &&
            reason.length <= MAX_REASON_LENGTH &&
            !isSubmitting

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassSurface(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // --- Date range (From / To) -------------------------------------
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LeaveDateChip(
                        label = "From",
                        text = if (fromMillis == null) "" else prettyLeaveDate(fromText),
                        onClick = { showFromPicker = true },
                        modifier = Modifier.weight(1f)
                    )
                    // The To chip stays locked until a From date exists — its
                    // picker's earliest selectable date IS the From date.
                    LeaveDateChip(
                        label = "To",
                        text = if (toMillis == null) "" else prettyLeaveDate(toText),
                        enabled = fromMillis != null,
                        onClick = { showToPicker = true },
                        modifier = Modifier.weight(1f)
                    )
                }

                // --- Inline overlap error ---------------------------------------
                if (overlapWith != null) {
                    Text(
                        text = "Overlaps your ${overlapWith.status.lowercase(Locale.US)} leave " +
                                "(${prettyLeaveDate(overlapWith.fromDate)} → ${prettyLeaveDate(overlapWith.toDate)}). " +
                                "Pick different dates or cancel that request.",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppTheme.colors.dangerRed
                    )
                }

                // --- Applicant reason (required) ---------------------------------
                GlassSurface(
                    shape = RoundedCornerShape(12.dp),
                    border = if (reasonError != null) {
                        BorderStroke(1.dp, AppTheme.colors.dangerRed.copy(alpha = 0.7f))
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextField(
                        value = reason,
                        onValueChange = { if (it.length <= MAX_REASON_LENGTH) reason = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                text = "Reason",
                                color = AppTheme.colors.textSecondary
                            )
                        },
                        isError = reasonError != null,
                        minLines = 3,
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = AppTheme.colors.textPrimary,
                            unfocusedTextColor = AppTheme.colors.textPrimary,
                            errorTextColor = AppTheme.colors.textPrimary,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            errorContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            errorIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            focusedPlaceholderColor = AppTheme.colors.textSecondary,
                            unfocusedPlaceholderColor = AppTheme.colors.textSecondary,
                            cursorColor = AppTheme.colors.textPrimary
                        )
                    )
                }
                if (reasonError != null) {
                    Text(
                        text = reasonError,
                        color = AppTheme.colors.dangerRed,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                // --- Submission error banner -------------------------------------
                if (!message.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = AppTheme.colors.dangerRedBg,
                        border = BorderStroke(
                            1.dp,
                            AppTheme.colors.dangerRed.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = message,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppTheme.colors.dangerRed,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                // --- Submit -------------------------------------------------------
                GlassGlowButton(
                    onClick = {
                        if (canSubmit) {
                            onSubmit(fromText, toText, reasonTrimmed)
                            reason = ""
                            fromMillis = null
                            toMillis = null
                        }
                    },
                    enabled = canSubmit,
                    fillMaxWidth = true,
                    minHeight = 46.dp,
                    shape = RoundedCornerShape(12.dp),
                    glowColor = if (isSubmitting) AppTheme.colors.accentOrange else profileAccentColor(),
                    glowActive = canSubmit || isSubmitting
                ) {
                    AnimatedContent(
                        targetState = isSubmitting,
                        transitionSpec = glassButtonTransition(),
                        label = "leave_submit_text"
                    ) { submitting ->
                        Text(
                            text = if (submitting) "Submitting…" else "Submit application",
                            fontWeight = FontWeight.SemiBold,
                            color = if (canSubmit || submitting) AppTheme.colors.textPrimary else AppTheme.colors.textMuted
                        )
                    }
                }
            }
        }
    }

    // From picker: only current and future dates (no previous months/years)
    if (showFromPicker) {
        val today = todayUtcMidnightMillis()
        val currentYear = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = today
        }.get(Calendar.YEAR)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = fromMillis ?: today,
            initialDisplayedMonthMillis = today,
            yearRange = currentYear..2100,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= today
                override fun isSelectableYear(year: Int): Boolean = year >= currentYear
            }
        )
        DatePickerDialog(
            onDismissRequest = { showFromPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val picked = state.selectedDateMillis
                    if (picked != null) {
                        fromMillis = picked
                        // Clamp To forward so to_date >= from_date always holds.
                        if (toMillis != null && toMillis!! < picked) toMillis = picked
                    }
                    showFromPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showFromPicker = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.colors.accentOrange)
                ) { Text("Cancel") }
            }
        ) { DatePicker(state = state) }
    }

    // To picker: earliest selectable date is the later of today and From
    if (showToPicker) {
        val today = todayUtcMidnightMillis()
        val minDate = maxOf(today, fromMillis ?: today)
        val currentYear = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = today
        }.get(Calendar.YEAR)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = toMillis ?: minDate,
            initialDisplayedMonthMillis = minDate,
            yearRange = currentYear..2100,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= minDate
                override fun isSelectableYear(year: Int): Boolean = year >= currentYear
            }
        )
        DatePickerDialog(
            onDismissRequest = { showToPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { toMillis = it }
                    showToPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showToPicker = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.colors.accentOrange)
                ) { Text("Cancel") }
            }
        ) { DatePicker(state = state) }
    }
}

// A tappable date chip in the form ("From" / "To")
@Composable
private fun LeaveDateChip(
    label: String,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    GlassSurface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            val hasDate = text.isNotBlank()
            Text(
                text = if (hasDate) text else label,
                fontSize = 15.sp,
                fontWeight = if (hasDate) FontWeight.SemiBold else FontWeight.Medium,
                color = when {
                    hasDate && enabled -> AppTheme.colors.textPrimary
                    hasDate -> AppTheme.colors.textMuted
                    else -> AppTheme.colors.textSecondary
                },
                textAlign = TextAlign.Center
            )
        }
    }
}

// Coordinates hold-and-drag-to-top cancellation for pending leave application cards.
@Stable
internal class LeaveHistoryDragState(
    val overlayScope: CoroutineScope
) {
    var floatingApp by mutableStateOf<LeaveApplicationResponse?>(null)
    var isDraggingCard by mutableStateOf(false)
    var inDropZone by mutableStateOf(false)
    var cardTopLeftInRoot by mutableStateOf(Offset.Zero)
    var cardSize by mutableStateOf(IntSize.Zero)
    val dragOffset: Animatable<Offset, AnimationVector2D> = Animatable(Offset.Zero, Offset.VectorConverter)
}

private class CardLayoutBounds {
    var posInRoot: Offset = Offset.Zero
    var size: IntSize = IntSize.Zero
}

// ---------------------------------------------------------------------------
// Leave history: list of applications with status badges, expandable reviewer
// feedback, and hold-to-drag-to-top cancellation for pending requests.
// ---------------------------------------------------------------------------
@Composable
internal fun LeaveHistoryTracker(
    applications: List<LeaveApplicationResponse>,
    isLoading: Boolean,
    isCancelling: Boolean,
    onCancel: (String) -> Unit,
    dragState: LeaveHistoryDragState,
    dropZoneCenterY: Float,
    modifier: Modifier = Modifier
) {
    // Newest first by applied/created time
    val ordered = remember(applications) {
        applications.sortedByDescending { it.createdAt ?: "" }
    }

    if (!isLoading && ordered.isEmpty()) return

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "My applications",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppTheme.colors.textPrimary
        )

        if (isLoading) {
            repeat(3) { LeaveSkeletonCard() }
        } else {
            ordered.forEach { app ->
                LeaveHistoryCard(
                    app = app,
                    isCancelling = isCancelling,
                    dragState = dragState,
                    dropZoneCenterY = dropZoneCenterY,
                    onCancel = { onCancel(app.id) }
                )
            }
        }
    }
}

// One history entry: date range, status badge, applicant reason, and expandable
// reviewer feedback. Pending cards can be held and dragged to the top edge to cancel.
@Composable
private fun LeaveHistoryCard(
    app: LeaveApplicationResponse,
    isCancelling: Boolean,
    dragState: LeaveHistoryDragState,
    dropZoneCenterY: Float,
    onCancel: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val isPending = app.status.equals("PENDING", true)
    val canDragCancel = isPending && !isCancelling
    val bounds = remember { CardLayoutBounds() }
    val isDetached = dragState.floatingApp?.id == app.id

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                bounds.posInRoot = coords.positionInRoot()
                bounds.size = coords.size
            }
            .pointerInput(app.id, canDragCancel, dropZoneCenterY) {
                if (!canDragCancel) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (dragState.floatingApp != null) return@awaitEachGesture

                    val holdMs = 260L
                    val maxPreHoldSlopPx = 12.dp.toPx()
                    val minUpwardTravelPx = 48.dp.toPx()
                    val startTimeMs = System.currentTimeMillis()
                    var aborted = false

                    while (System.currentTimeMillis() - startTimeMs < holdMs) {
                        val remainingMs = (holdMs - (System.currentTimeMillis() - startTimeMs)).coerceAtLeast(1L)
                        val event = withTimeoutOrNull(remainingMs) { awaitPointerEvent() } ?: break
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed || change.isConsumed) {
                            aborted = true
                            break
                        }
                        if ((change.position - down.position).getDistance() > maxPreHoldSlopPx) {
                            aborted = true
                            break
                        }
                    }

                    if (aborted || dragState.floatingApp != null) return@awaitEachGesture

                    dragState.cardTopLeftInRoot = bounds.posInRoot
                    dragState.cardSize = bounds.size
                    dragState.floatingApp = app
                    dragState.isDraggingCard = true
                    dragState.inDropZone = false
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                    var currentDrag = Offset.Zero
                    val startCenterY = bounds.posInRoot.y + bounds.size.height * 0.5f

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val delta = change.positionChange()
                            change.consume()
                            if (delta != Offset.Zero) {
                                currentDrag = Offset(
                                    x = (currentDrag.x + delta.x).coerceIn(-180f, 180f),
                                    y = currentDrag.y + delta.y
                                )
                                val targetDrag = currentDrag
                                dragState.overlayScope.launch { dragState.dragOffset.snapTo(targetDrag) }

                                val currentCenterY = startCenterY + targetDrag.y
                                val inside = currentCenterY <= dropZoneCenterY && targetDrag.y <= -minUpwardTravelPx
                                if (inside != dragState.inDropZone) {
                                    dragState.inDropZone = inside
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        }
                    } finally {
                        val shouldCancel = dragState.inDropZone
                        dragState.isDraggingCard = false
                        dragState.inDropZone = false
                        if (shouldCancel) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCancel()
                        }
                        dragState.overlayScope.launch {
                            try {
                                dragState.dragOffset.animateTo(
                                    targetValue = Offset.Zero,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            } finally {
                                if (dragState.floatingApp?.id == app.id) {
                                    dragState.floatingApp = null
                                }
                            }
                        }
                    }
                }
            }
            .alpha(if (isDetached) 0f else 1f)
    ) {
        LeaveHistoryCardContent(app = app)
    }
}

@Composable
internal fun LeaveHistoryCardContent(
    app: LeaveApplicationResponse,
    modifier: Modifier = Modifier
) {
    var feedbackExpanded by remember { mutableStateOf(false) }
    val hasFeedback = !app.reviewComment.isNullOrBlank()
    val isRejected = app.status.equals("REJECTED", true)

    GlassSurface(
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth().animateContentSize()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header: date range with status badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (app.fromDate == app.toDate) prettyLeaveDate(app.fromDate)
                    else "${prettyLeaveDate(app.fromDate)} → ${prettyLeaveDate(app.toDate)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                LeaveStatusBadge(app.status)
            }

            // Applicant reason
            if (app.reason.isNotBlank()) {
                Text(
                    text = app.reason,
                    fontSize = 13.sp,
                    color = AppTheme.colors.textSecondary,
                    maxLines = 3
                )
            }

            // Reviewer feedback: tap to expand full note + reviewer/time.
            if (hasFeedback) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isRejected) AppTheme.colors.dangerRedBg else AppTheme.colors.background,
                    border = BorderStroke(
                        1.dp,
                        (if (isRejected) AppTheme.colors.dangerRed else AppTheme.colors.surfaceBorder).copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { feedbackExpanded = !feedbackExpanded }
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isRejected) "Why it was rejected" else "HOD feedback",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isRejected) AppTheme.colors.dangerRed else AppTheme.colors.textSecondary
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = if (feedbackExpanded) "Show less" else "Show full feedback",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isRejected) AppTheme.colors.dangerRed else AppTheme.colors.primaryPurple
                            )
                        }
                        Text(
                            text = app.reviewComment.orEmpty(),
                            fontSize = 13.sp,
                            fontWeight = if (isRejected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isRejected) AppTheme.colors.dangerRed else AppTheme.colors.textPrimary,
                            maxLines = if (feedbackExpanded) Int.MAX_VALUE else 2
                        )
                        AnimatedVisibility(
                            visible = feedbackExpanded,
                            enter = expandVertically(tween(150)) + fadeIn(tween(120)),
                            exit = shrinkVertically(tween(150)) + fadeOut(tween(120))
                        ) {
                            val reviewerLine = remember(app.reviewerName, app.reviewedAt) {
                                buildString {
                                    val reviewer = app.reviewerName
                                    if (!reviewer.isNullOrBlank()) append("— $reviewer")
                                    val reviewed = prettyLeaveTimestamp(app.reviewedAt)
                                    if (reviewed.isNotBlank()) {
                                        if (isNotBlank()) append(" · ")
                                        append(reviewed)
                                    }
                                }
                            }
                            Text(
                                text = reviewerLine,
                                fontSize = 11.sp,
                                color = AppTheme.colors.textMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

// Status badge: plain "Pending" text for pending items; translucent capsule for decided items.
@Composable
internal fun LeaveStatusBadge(status: String) {
    val upper = status.uppercase(Locale.US)
    if (upper == "PENDING") {
        Text(
            text = "Pending",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppTheme.colors.warningAmber
        )
        return
    }
    val tone = when (upper) {
        "APPROVED" -> AppTheme.colors.successGreen
        "REJECTED" -> AppTheme.colors.dangerRed
        else -> AppTheme.colors.textMuted
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(tone.copy(alpha = 0.14f))
            .border(0.75.dp, tone.copy(alpha = 0.45f), RoundedCornerShape(100.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            text = upper,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = tone
        )
    }
}

// Pulsing glassy placeholder card shown while history loads
@Composable
private fun LeaveSkeletonCard() {
    val transition = rememberInfiniteTransition(label = "leave_skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "leave_skeleton_alpha"
    )
    GlassSurface(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(14.dp)
                    .alpha(alpha)
                    .background(AppTheme.colors.textSecondary.copy(alpha = 0.25f), RoundedCornerShape(7.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(12.dp)
                    .alpha(alpha)
                    .background(AppTheme.colors.textSecondary.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .alpha(alpha)
                    .background(AppTheme.colors.textSecondary.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            )
        }
    }
}

// ---------------------------------------------------------------------------
// HOD review dialog: collects reviewer feedback inside a frosted Crystal glass card.
// Optional on APPROVED, mandatory on REJECTED — the confirm button stays disabled
// until a non-blank remark exists for a rejection, matching the server-side guard.
// ---------------------------------------------------------------------------
@Composable
fun LeaveReviewDialog(
    app: LeaveApplicationResponse,
    isReviewing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (decision: String, comment: String?) -> Unit
) {
    var decision by remember { mutableStateOf("APPROVED") }
    var comment by remember { mutableStateOf("") }

    val commentTrimmed = comment.trim()
    val isRejection = decision == "REJECTED"
    val feedbackMissing = isRejection && commentTrimmed.isEmpty()
    val canConfirm = !isReviewing && !feedbackMissing
    val isDark = AppTheme.colors.isDark

    Dialog(onDismissRequest = { if (!isReviewing) onDismiss() }) {
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            if (window != null) {
                window.setDimAmount(0.74f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.apply {
                        blurBehindRadius = 36
                    }
                }
            }
            onDispose {}
        }

        GlassSurface(
            shape = RoundedCornerShape(22.dp),
            frosted = true,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Review leave request",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = AppTheme.colors.textPrimary
                )

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = buildString {
                            append(app.studentName ?: "Student")
                            if (!app.studentRoll.isNullOrBlank()) append(" (${app.studentRoll})")
                        },
                        fontSize = 14.5.sp,
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

                if (app.reason.isNotBlank()) {
                    GlassSurface(
                        shape = RoundedCornerShape(12.dp),
                        nested = true,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "“${app.reason}”",
                            fontSize = 13.sp,
                            color = AppTheme.colors.textSecondary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                        )
                    }
                }

                GlassDivider()

                // Decision toggle pills in nested Crystal glass
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassSurface(
                        shape = RoundedCornerShape(12.dp),
                        nested = true,
                        onClick = { decision = "APPROVED" },
                        border = BorderStroke(
                            1.dp,
                            if (!isRejection) AppTheme.colors.successGreen.copy(alpha = 0.75f)
                            else AppTheme.colors.surfaceBorder.copy(alpha = 0.45f)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (!isRejection) Modifier.background(AppTheme.colors.successGreen.copy(alpha = 0.20f))
                                    else Modifier
                                )
                                .height(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Approve",
                                fontWeight = FontWeight.SemiBold,
                                color = if (!isRejection) AppTheme.colors.successGreen else AppTheme.colors.textSecondary
                            )
                        }
                    }

                    GlassSurface(
                        shape = RoundedCornerShape(12.dp),
                        nested = true,
                        onClick = { decision = "REJECTED" },
                        border = BorderStroke(
                            1.dp,
                            if (isRejection) AppTheme.colors.dangerRed.copy(alpha = 0.75f)
                            else AppTheme.colors.surfaceBorder.copy(alpha = 0.45f)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isRejection) Modifier.background(AppTheme.colors.dangerRed.copy(alpha = 0.18f))
                                    else Modifier
                                )
                                .height(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Reject",
                                fontWeight = FontWeight.SemiBold,
                                color = if (isRejection) AppTheme.colors.dangerRed else AppTheme.colors.textSecondary
                            )
                        }
                    }
                }

                // Feedback: optional on approval, required on rejection
                OutlinedTextField(
                    value = comment,
                    onValueChange = { if (it.length <= 500) comment = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    label = { Text(if (isRejection) "Feedback (required)" else "Feedback (optional)") },
                    placeholder = {
                        Text(
                            if (isRejection) "Tell the student why this is rejected"
                            else "Optional note for the student",
                            fontSize = 13.sp,
                            color = AppTheme.colors.textMuted
                        )
                    },
                    isError = feedbackMissing,
                    supportingText = {
                        Text(
                            text = when {
                                feedbackMissing -> "A remark is required when rejecting"
                                else -> "${comment.length}/500"
                            },
                            fontSize = 12.sp,
                            color = if (feedbackMissing) AppTheme.colors.dangerRed else AppTheme.colors.textMuted
                        )
                    },
                    minLines = 2,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AppTheme.colors.textPrimary,
                        unfocusedTextColor = AppTheme.colors.textPrimary,
                        focusedContainerColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.45f),
                        unfocusedContainerColor = if (isDark) Color.White.copy(alpha = 0.03f) else Color.White.copy(alpha = 0.30f),
                        focusedBorderColor = AppTheme.colors.primaryPurple,
                        unfocusedBorderColor = AppTheme.colors.surfaceBorder.copy(alpha = 0.6f),
                        focusedLabelColor = AppTheme.colors.primaryPurple,
                        unfocusedLabelColor = AppTheme.colors.textSecondary,
                        cursorColor = AppTheme.colors.primaryPurple
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isReviewing
                    ) {
                        Text("Cancel", color = AppTheme.colors.textSecondary)
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    GlassGlowButton(
                        onClick = { onConfirm(decision, commentTrimmed.takeIf { it.isNotBlank() }) },
                        enabled = canConfirm,
                        minWidth = 96.dp,
                        minHeight = 40.dp,
                        shape = RoundedCornerShape(10.dp),
                        glowColor = if (isRejection) AppTheme.colors.dangerRed else AppTheme.colors.successGreen,
                        glowActive = canConfirm || isReviewing,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        AnimatedContent(
                            targetState = isReviewing,
                            transitionSpec = glassButtonTransition(),
                            label = "leave_review_confirm"
                        ) { reviewing ->
                            if (reviewing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = if (isRejection) AppTheme.colors.dangerRed else AppTheme.colors.successGreen,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = if (isRejection) "Reject" else "Approve",
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (canConfirm) AppTheme.colors.textPrimary else AppTheme.colors.textMuted
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
