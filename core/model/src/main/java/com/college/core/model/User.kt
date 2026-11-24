package com.college.core.model

// Three colors derived from the owner's profile photo on the server. The
// client never decodes an image to compute these.
data class ProfilePalette(
    val primary: String,
    val secondary: String,
    val tertiary: String
)

data class User(
    val id: String,
    val name: String,
    val email: String,
    val roles: Set<Role>,
    val rollNumber: String? = null,
    val department: String? = null,
    val program: String? = null,
    val semester: Int? = null,
    // Owner-only, content-versioned avatar path and its precomputed palette.
    val profilePhotoUrl: String? = null,
    val profilePalette: ProfilePalette? = null
) {
    val primaryRole: Role? get() = roles.maxByOrNull { it.priority }

    fun hasRole(role: Role): Boolean = role in roles
    fun isDeveloper(): Boolean = roles.any { it.isDeveloper() }
    fun canAccessStaffTools(): Boolean = roles.any { it.canAccessStaffTools() }
    fun canTakeAttendance(): Boolean = roles.any { it.canTakeAttendance() }
}
