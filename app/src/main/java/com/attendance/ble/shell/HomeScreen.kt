package com.attendance.ble.shell

import android.os.Build
import android.util.Base64
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Approval
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.college.core.model.Role
import com.college.core.model.User
import com.college.core.network.NetworkResult
import com.college.core.network.SessionManager
import com.college.core.network.repository.AuthRepository
import com.college.core.ui.components.AppCardGroup
import com.college.core.ui.components.AppCardItem
import com.college.core.ui.components.AppDivider
import com.college.core.ui.components.AppProfileCard
import com.college.core.ui.components.AppSearchPill
import com.college.core.ui.components.clearAvatarCache
import com.college.core.ui.components.clearGlassCache
import com.college.core.ui.components.ProfilePhotoPreviewDialog
import com.college.core.ui.components.PokemonTrainerCard
import com.college.core.ui.components.GlassDivider
import com.college.core.ui.components.TopDangerAmbientOverlay
import com.college.core.ui.theme.AppTheme
import com.college.feature.attendance.AttendanceDashboardCard
import com.college.feature.library.LibraryDashboardCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Main dashboard home screen
@Composable
fun HomeScreen(
    user: User?,
    onNavigate: (String) -> Unit,
    // R8: sign-out is routed through the ViewModel logout so the server-side
    // token revocation (chunk 7 route) actually fires; the direct endSession
    // fallback keeps the shell usable standalone
    onRequestSignOut: (() -> Unit)? = null
) {
    var showAccountDialog by remember { mutableStateOf(false) }
    var isAboutExpanded by remember { mutableStateOf(false) }
    var showDevActingDialog by remember { mutableStateOf(false) }
    var showPhotoPreview by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val backdropBlurred = showAccountDialog || showPhotoPreview || showDevActingDialog

    // R8 (review §3.3): the unmasked account is collected state now — the old
    // direct getter re-parsed Gson JSON from prefs on every recomposition and
    // could render a stale developer badge.
    val realUser by SessionManager.default.realUser.collectAsState()

    val colors = AppTheme.colors
    val featureItems = remember(user, colors) {
        buildList {
            add(
                FeatureItem("Attendance", Icons.AutoMirrored.Filled.FactCheck, colors.accentOrange) {
                    if (user != null) onNavigate(AttendanceDashboardCard.NAV_ROUTE) else onNavigate("auth")
                }
            )
            if (user?.hasRole(Role.STUDENT) == true) {
                add(
                    FeatureItem("My Attendance", Icons.Default.CalendarMonth, colors.primaryPurple) {
                        onNavigate(AttendanceDashboardCard.MY_ATTENDANCE_ROUTE)
                    }
                )
            }
            if (user?.canTakeAttendance() == true) {
                add(
                    FeatureItem("Older Sessions", Icons.Default.History, colors.primaryPurple) {
                        onNavigate("${AttendanceDashboardCard.NAV_ROUTE}/older")
                    }
                )
                // HOD-only: the department queue endpoint rejects every other
                // role with 403 ("Only the HOD can view leave applications"),
                // so teachers landing here only ever saw an error banner.
                if (user.hasRole(Role.HOD)) {
                    add(
                        FeatureItem("Leave Approvals", Icons.Default.Approval, colors.accentOrange) {
                            onNavigate("leave_approvals")
                        }
                    )
                }
            }
            if (user?.hasRole(Role.STUDENT) == true) {
                add(
                    FeatureItem("Leave Application", Icons.AutoMirrored.Filled.EventNote, colors.successGreen) {
                        onNavigate("leave_application")
                    }
                )
            }
            add(
                FeatureItem("Library", Icons.AutoMirrored.Filled.MenuBook, colors.primaryPurple) {
                    if (user != null) onNavigate(LibraryDashboardCard.NAV_ROUTE) else onNavigate("auth")
                }
            )
            add(
                FeatureItem("Account", Icons.Default.Person, colors.warningAmber) {
                    if (user != null) {
                        showAccountDialog = true
                    } else {
                        onNavigate("auth")
                    }
                }
            )
        }
    }

    val visibleFeatures = remember(searchQuery, featureItems) {
        if (searchQuery.isBlank()) {
            featureItems.filter { it.title != "Account" }
        } else {
            featureItems.filter {
                it.title.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (backdropBlurred && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Modifier.blur(20.dp)
                } else {
                    Modifier
                }
            )
            .systemBarsPadding()
    ) {
        val screenHeight = maxHeight
        val viewingAreaHeight = (screenHeight * 0.28f).coerceIn(180.dp, 250.dp)

        // Main content column shifted down for one-handed reach without scrolling
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Viewing area displaying Rotom title
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(viewingAreaHeight),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Rotom",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.textPrimary,
                    letterSpacing = (-0.6).sp
                )
            }

            val activeRole = user?.primaryRole ?: user?.roles?.firstOrNull() ?: Role.STUDENT
            val activeRoleLabel = when (activeRole) {
                Role.TEACHER -> "Teacher"
                Role.HOD -> "HOD"
                Role.STUDENT -> "Student"
                Role.STAFF -> "Staff"
                Role.DEVELOPER -> "Developer"
            }

            // User account profile card opening account modal when authenticated or auth screen when logged out
            AppProfileCard(
                name = user?.name ?: "Account",
                subtitle = if (user != null) {
                    "$activeRoleLabel • ${user.rollNumber ?: user.email}"
                } else {
                    "Sign in or create profile"
                },
                initial = user?.name,
                avatarBg = if (user != null) AppTheme.colors.accentOrange else AppTheme.colors.primaryPurple,
                photoUrl = user?.profilePhotoUrl,
                onPhotoClick = { if (user?.profilePhotoUrl != null) showPhotoPreview = true },
                border = null,
                glass = true,
                onClick = {
                    if (user != null) {
                        showAccountDialog = true
                    } else {
                        onNavigate("auth")
                    }
                }
            )

            // Campus features card group (filters directly inside this island)
            AppCardGroup(glass = true) {
                if (visibleFeatures.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No matching features",
                            fontSize = 14.sp,
                            color = AppTheme.colors.textSecondary
                        )
                    }
                } else {
                    visibleFeatures.forEachIndexed { index, item ->
                        if (index > 0) AppDivider(glass = true)
                        AppCardItem(
                            icon = item.icon,
                            iconTint = Color.White,
                            iconBg = item.iconBg,
                            title = item.title,
                            onClick = item.onClick
                        )
                    }
                }
            }

            // Expandable application information group
            val aboutChevronRotation by animateFloatAsState(
                targetValue = if (isAboutExpanded) 180f else 0f,
                animationSpec = tween(durationMillis = 150),
                label = "about_chevron_rotation"
            )

            AppCardGroup(
                modifier = Modifier.animateContentSize(animationSpec = tween(durationMillis = 150)),
                glass = true
            ) {
                AppCardItem(
                    icon = Icons.Default.Info,
                    iconTint = Color.White,
                    iconBg = Color(0xFF64748B),
                    title = "About",
                    subtitle = "v1.0",
                    onClick = { isAboutExpanded = !isAboutExpanded },
                    trailing = {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isAboutExpanded) "Collapse" else "Expand",
                            tint = AppTheme.colors.textSecondary,
                            modifier = Modifier
                                .size(22.dp)
                                .rotate(aboutChevronRotation)
                        )
                    }
                )

                AnimatedVisibility(
                    visible = isAboutExpanded,
                    enter = expandVertically(animationSpec = tween(durationMillis = 150)) + fadeIn(animationSpec = tween(durationMillis = 150)),
                    exit = shrinkVertically(animationSpec = tween(durationMillis = 150)) + fadeOut(animationSpec = tween(durationMillis = 150))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 2.dp)
                    ) {
                        GlassDivider(modifier = Modifier.padding(bottom = 12.dp))

                        // Gratitude message
                        Text(
                            text = "Thank you for using our app",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppTheme.colors.textSecondary
                        )
                    }
                }
            }

            // Bottom clearance above floating search bar
            Spacer(modifier = Modifier.height(72.dp))
        }

        // Floating search pill pinned at bottom
        AppSearchPill(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = "Search feature",
            modifier = Modifier.align(Alignment.BottomCenter),
            glass = true
        )
    }

    // Modal dialogs
    if (showAccountDialog) {
        PokemonTrainerCardDialog(
            user = user,
            onDismiss = { showAccountDialog = false },
            onNavigateAuth = {
                showAccountDialog = false
                onNavigate("auth")
            },
            onSignOut = {
                clearAvatarCache()
                clearGlassCache()
                onRequestSignOut?.invoke() ?: SessionManager.default.endSession()
                showAccountDialog = false
            },
            onRoleClick = if (realUser?.isDeveloper() == true) {
                { showDevActingDialog = true }
            } else null,
            onPhotoClick = if (user?.profilePhotoUrl != null) {
                { showPhotoPreview = true }
            } else null
        )
    }

    if (showDevActingDialog) {
        DevActingDialog(
            onDismiss = { showDevActingDialog = false }
        )
    }

    if (showPhotoPreview && user?.profilePhotoUrl != null) {
        ProfilePhotoPreviewDialog(
            url = user.profilePhotoUrl,
            name = user.name,
            onDismiss = { showPhotoPreview = false }
        )
    }
}

// Pokemon 2D Game inspired Trainer Card modal popup with hold-to-drag sign-out drop zone
@Composable
private fun PokemonTrainerCardDialog(
    user: User?,
    onDismiss: () -> Unit,
    onNavigateAuth: () -> Unit,
    onSignOut: () -> Unit,
    onRoleClick: (() -> Unit)? = null,
    onPhotoClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var isUploading by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf<String?>(null) }
    var cropBytes by remember { mutableStateOf<ByteArray?>(null) }

    var isAvatarTouched by remember { mutableStateOf(false) }
    var isDraggingCard by remember { mutableStateOf(false) }
    var inDropZone by remember { mutableStateOf(false) }
    val dragOffset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val cardScale by animateFloatAsState(
        targetValue = when {
            isDraggingCard && inDropZone -> 0.92f
            isDraggingCard -> 1.04f
            else -> 1f
        },
        animationSpec = tween(durationMillis = 140),
        label = "trainer_card_drag_scale"
    )

    fun uploadPhoto(bytes: ByteArray) {
        isUploading = true
        photoError = null
        scope.launch(Dispatchers.IO) {
            try {
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val result = AuthRepository.default.uploadProfilePhoto(base64)
                withContext(Dispatchers.Main) {
                    isUploading = false
                    when (result) {
                        is NetworkResult.Success<*> -> photoError = null
                        is NetworkResult.Error -> photoError = result.message
                        is NetworkResult.Exception ->
                            photoError = result.throwable.message ?: "Upload failed"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isUploading = false
                    photoError = e.message ?: "Upload failed"
                }
            }
        }
    }

    fun deletePhoto() {
        isUploading = true
        photoError = null
        scope.launch(Dispatchers.IO) {
            try {
                val result = AuthRepository.default.deleteProfilePhoto()
                withContext(Dispatchers.Main) {
                    isUploading = false
                    when (result) {
                        is NetworkResult.Success<*> -> {
                            clearAvatarCache()
                            clearGlassCache()
                            photoError = null
                        }
                        is NetworkResult.Error -> photoError = result.message
                        is NetworkResult.Exception ->
                            photoError = result.throwable.message ?: "Failed to remove photo"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isUploading = false
                    photoError = e.message ?: "Failed to remove photo"
                }
            }
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        photoError = null
        scope.launch(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Could not read the selected image")
                if (bytes.size > 20 * 1024 * 1024) {
                    throw IllegalStateException("Image is too large (20 MB limit)")
                }
                withContext(Dispatchers.Main) { cropBytes = bytes }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { photoError = e.message ?: "Could not read image" }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                @Suppress("DEPRECATION")
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                @Suppress("DEPRECATION")
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                window.setDimAmount(0.78f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.apply {
                        blurBehindRadius = 48
                    }
                }
            }
            onDispose {}
        }

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val dropThresholdPx = -(constraints.maxHeight * 0.35f)

            // Constant-enabled sibling dismiss layer so drag state changes never reset pointer modifiers
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (!isDraggingCard) onDismiss()
                    }
            )

            // Reactive red ambient occlusion glow along top edge while holding & dragging up
            TopDangerAmbientOverlay(
                visible = isDraggingCard,
                pullFraction = { ((-dragOffset.value.y) / (-dropThresholdPx)).coerceIn(0f, 1f) }
            )

            // Centered draggable trainer card: pointerInput stays on the stationary outer Box
            // while translation/scale animate strictly on the inner graphicsLayer Box.
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(user != null, dropThresholdPx) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (isAvatarTouched) return@awaitEachGesture

                                // Signed-out guests just tap to dismiss cleanly
                                if (user == null) {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (change.changedToUp()) {
                                            if (!change.isConsumed) onDismiss()
                                            break
                                        }
                                    }
                                    return@awaitEachGesture
                                }

                                // Forgiving 260ms hold (with 24.dp thumb-wobble tolerance) vs quick tap-to-dismiss
                                val holdMs = 260L
                                val maxPreHoldSlopPx = 24.dp.toPx()
                                val startTimeMs = System.currentTimeMillis()
                                var aborted = false

                                while (System.currentTimeMillis() - startTimeMs < holdMs) {
                                    val remainingMs = (holdMs - (System.currentTimeMillis() - startTimeMs)).coerceAtLeast(1L)
                                    val event = withTimeoutOrNull(remainingMs) { awaitPointerEvent() } ?: break
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed) {
                                        if (change != null && !change.isConsumed &&
                                            (change.position - down.position).getDistance() < 16.dp.toPx()
                                        ) {
                                            onDismiss()
                                        }
                                        aborted = true
                                        break
                                    }
                                    if ((change.position - down.position).getDistance() > maxPreHoldSlopPx || isAvatarTouched) {
                                        aborted = true
                                        break
                                    }
                                }

                                if (aborted || isAvatarTouched) return@awaitEachGesture

                                isDraggingCard = true
                                inDropZone = false
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                var currentDrag = dragOffset.value

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
                                                x = (currentDrag.x + delta.x).coerceIn(-220f, 220f),
                                                y = currentDrag.y + delta.y
                                            )
                                            val targetDrag = currentDrag
                                            scope.launch { dragOffset.snapTo(targetDrag) }
                                            val inside = targetDrag.y <= dropThresholdPx
                                            if (inside != inDropZone) {
                                                inDropZone = inside
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    }
                                } finally {
                                    val shouldSignOut = inDropZone
                                    isDraggingCard = false
                                    inDropZone = false
                                    if (shouldSignOut) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onSignOut()
                                    } else {
                                        scope.launch {
                                            dragOffset.animateTo(
                                                targetValue = Offset.Zero,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMedium
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                translationX = dragOffset.value.x
                                translationY = dragOffset.value.y
                                scaleX = cardScale
                                scaleY = cardScale
                                rotationZ = (dragOffset.value.x / 42f).coerceIn(-6f, 6f)
                            }
                    ) {
                        PokemonTrainerCard(
                            user = user,
                            onClick = null,
                            onRoleClick = onRoleClick,
                            onPhotoClick = onPhotoClick,
                            onPhotoDoubleClick = if (user != null && !isUploading) {
                                { pickPhoto.launch("image/*") }
                            } else {
                                null
                            },
                            onPhotoLongClick = if (user?.profilePhotoUrl != null && !isUploading) {
                                { deletePhoto() }
                            } else {
                                null
                            },
                            onAvatarTouchActive = { active -> isAvatarTouched = active }
                        )
                    }
                }

                if (user != null) {
                    if (isUploading) {
                        CircularProgressIndicator(
                            color = AppTheme.colors.primaryPurple,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    photoError?.let { message ->
                        Text(text = message, color = AppTheme.colors.dangerRed, fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = onNavigateAuth,
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primaryPurple),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Sign In", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    cropBytes?.let { bytes ->
        PhotoCropDialog(
            sourceBytes = bytes,
            onCancel = { cropBytes = null },
            onConfirm = { cropped ->
                cropBytes = null
                uploadPhoto(cropped)
            }
        )
    }
}


// DEVELOPER-only acting-role selector: wears a role on the client lens so
// developer accounts can preview student, teacher and staff flows end to end.
// Server-side sees the real account (DEVELOPER clears require_* everywhere),
// so no student data holes open — this is purely a UI lens.
@Composable
private fun DevActingDialog(onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    // R8 (review §3.3): collected — the dialog's role checkmarks stay truthful
    // while it is open (used to be plain getters read once at composition)
    val acting by SessionManager.default.actingRole.collectAsState()
    val real by SessionManager.default.realUser.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textPrimary,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Dev: act as role") },
        text = {
            Column {
                Text(
                    "Local preview only — the server continues to authorize your real DEVELOPER account. A fresh login always presents the real role.",
                    fontSize = 11.sp,
                    color = colors.textSecondary
                )
                Spacer(Modifier.height(10.dp))
                listOf(Role.STUDENT, Role.TEACHER, Role.HOD, Role.STAFF, Role.DEVELOPER).forEach { role ->
                    val isActive = acting == role
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                SessionManager.default.setActingRole(role)
                                onDismiss()
                            },
                        color = if (isActive) colors.primaryPurpleBg else colors.surface,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val label = role.name.lowercase().replaceFirstChar { it.uppercase() }
                            Text(
                                if (isActive) "Acting: $label ✓" else "Act as $label",
                                fontSize = 14.sp,
                                color = if (isActive) colors.primaryPurple else colors.textPrimary
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Real account: ${real?.primaryRole?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "unknown"}",
                    fontSize = 11.sp,
                    color = colors.textSecondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                SessionManager.default.setActingRole(null)
                onDismiss()
            }) { Text("Back to real role") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

// Feature item for the campus features island
private data class FeatureItem(
    val title: String,
    val icon: ImageVector,
    val iconBg: Color,
    val onClick: () -> Unit
)
