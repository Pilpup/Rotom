package com.college.core.network.service

import com.college.core.network.model.LoginRequest
import com.college.core.network.model.LoginResponse
import com.college.core.network.model.OtpSentResponse
import com.college.core.network.model.ProfilePhotoUploadRequest
import com.college.core.network.model.RegisterRequest
import com.college.core.network.model.ResendOtpRequest
import com.college.core.network.model.TokenResponse
import com.college.core.network.model.UserResponse
import com.college.core.network.model.VerifyOtpRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT

interface AuthApiService {

    @POST("api/v1/auth/login")
    suspend fun login(
        @Body body: LoginRequest
    ): Response<LoginResponse>

    @POST("api/v1/auth/login/verify-otp")
    suspend fun verifyLoginOtp(
        @Body body: VerifyOtpRequest
    ): Response<TokenResponse>

    @POST("api/v1/auth/register")
    suspend fun register(
        @Body body: RegisterRequest
    ): Response<OtpSentResponse>

    @POST("api/v1/auth/register/verify-otp")
    suspend fun verifyRegisterOtp(
        @Body body: VerifyOtpRequest
    ): Response<TokenResponse>

    @POST("api/v1/auth/otp/resend")
    suspend fun resendOtp(
        @Body body: ResendOtpRequest
    ): Response<OtpSentResponse>

    @GET("api/v1/auth/me")
    suspend fun getMyProfile(): Response<UserResponse>

    @PUT("api/v1/auth/me/profile-photo")
    suspend fun uploadProfilePhoto(
        @Body body: ProfilePhotoUploadRequest
    ): Response<UserResponse>

    @DELETE("api/v1/auth/me/profile-photo")
    suspend fun deleteProfilePhoto(): Response<UserResponse>
}
