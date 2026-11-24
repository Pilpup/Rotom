package com.college.core.network.repository

import com.college.core.model.Role
import com.college.core.model.User
import com.college.core.network.NetworkClient
import com.college.core.network.NetworkResult
import com.college.core.network.SessionManager
import com.college.core.network.model.LoginRequest
import com.college.core.network.model.LoginResponse
import com.college.core.network.model.OtpSentResponse
import com.college.core.network.model.ProfilePhotoUploadRequest
import com.college.core.network.model.RegisterRequest
import com.college.core.network.model.ResendOtpRequest
import com.college.core.network.model.TokenResponse
import com.college.core.network.model.UserResponse
import com.college.core.network.model.VerifyOtpRequest
import com.college.core.network.safeApiCall
import com.college.core.network.service.AuthApiService

class AuthRepository(
    private val authService: AuthApiService = NetworkClient.createService<AuthApiService>(),
    private val sessionManager: SessionManager = SessionManager.default
) {
    suspend fun login(email: String, pass: String): NetworkResult<LoginResponse> {
        val result = safeApiCall { authService.login(LoginRequest(email.trim(), pass)) }
        if (result is NetworkResult.Success) {
            val body = result.data
            if (!body.requiresOtp && body.accessToken != null && body.user != null) {
                applyAuthenticatedSession(body.accessToken, body.user)
            }
        }
        return result
    }

    suspend fun verifyLoginOtp(email: String, otpCode: String): NetworkResult<TokenResponse> {
        val result = safeApiCall { authService.verifyLoginOtp(VerifyOtpRequest(email.trim(), otpCode.trim())) }
        if (result is NetworkResult.Success) {
            applyAuthenticatedSession(result.data.accessToken, result.data.user)
        }
        return result
    }

    suspend fun register(request: RegisterRequest): NetworkResult<OtpSentResponse> {
        return safeApiCall { authService.register(request) }
    }

    suspend fun verifyRegisterOtp(email: String, otpCode: String): NetworkResult<TokenResponse> {
        val result = safeApiCall { authService.verifyRegisterOtp(VerifyOtpRequest(email.trim(), otpCode.trim())) }
        if (result is NetworkResult.Success) {
            applyAuthenticatedSession(result.data.accessToken, result.data.user)
        }
        return result
    }

    suspend fun resendOtp(email: String, purpose: String = "LOGIN"): NetworkResult<OtpSentResponse> {
        return safeApiCall { authService.resendOtp(ResendOtpRequest(email.trim(), purpose)) }
    }

    suspend fun refreshProfile(): NetworkResult<UserResponse> {
        val result = safeApiCall { authService.getMyProfile() }
        if (result is NetworkResult.Success<UserResponse>) applyUserUpdate(result.data)
        return result
    }

    suspend fun uploadProfilePhoto(base64: String): NetworkResult<UserResponse> {
        val result = safeApiCall {
            authService.uploadProfilePhoto(ProfilePhotoUploadRequest(base64))
        }
        if (result is NetworkResult.Success<UserResponse>) applyUserUpdate(result.data)
        return result
    }

    suspend fun deleteProfilePhoto(): NetworkResult<UserResponse> {
        val result = safeApiCall { authService.deleteProfilePhoto() }
        if (result is NetworkResult.Success<UserResponse>) applyUserUpdate(result.data)
        return result
    }

    private fun applyAuthenticatedSession(token: String, userDto: UserResponse) {
        val domainUser = toDomainUser(userDto)
        sessionManager.startSession(user = domainUser, token = token)
    }

    private fun applyUserUpdate(userDto: UserResponse) {
        sessionManager.updateUser(toDomainUser(userDto))
    }

    private fun toDomainUser(userDto: UserResponse): User {
        val role = Role.fromBackendName(userDto.role) ?: Role.STUDENT
        return User(
            id = userDto.id,
            name = userDto.name,
            email = userDto.email,
            roles = setOf(role),
            rollNumber = userDto.rollNumber,
            department = userDto.department,
            program = userDto.program,
            semester = userDto.semester,
            profilePhotoUrl = userDto.profilePhotoUrl,
            profilePalette = userDto.profilePalette
        )
    }

    companion object {
        val default by lazy { AuthRepository() }
    }
}
