package com.college.core.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.college.core.model.DefaultProfilePalette
import com.college.core.model.ProfilePalette

// Provided once at the app root so glass surfaces can tint themselves from
// the active profile photo (or default palette) without per-island params.
val LocalProfilePalette = staticCompositionLocalOf<ProfilePalette?> { DefaultProfilePalette }
