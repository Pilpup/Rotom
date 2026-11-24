package com.college.core.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.college.core.model.ProfilePalette

// Provided once at the app root so glass surfaces can tint themselves from
// the active profile photo without every island taking a parameter.
val LocalProfilePalette = staticCompositionLocalOf<ProfilePalette?> { null }
