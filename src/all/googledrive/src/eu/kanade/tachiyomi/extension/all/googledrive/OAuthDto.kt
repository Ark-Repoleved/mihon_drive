package eu.kanade.tachiyomi.extension.all.googledrive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Device Authorization Grant 回應
 * POST https://oauth2.googleapis.com/device/code
 */
@Serializable
data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("interval") val interval: Int
)

/**
 * OAuth Token 回應
 * POST https://oauth2.googleapis.com/token
 */
@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("token_type") val tokenType: String,
    @SerialName("scope") val scope: String? = null
)

/**
 * OAuth 錯誤回應
 */
@Serializable
data class OAuthErrorResponse(
    val error: String,
    @SerialName("error_description") val errorDescription: String? = null
)
