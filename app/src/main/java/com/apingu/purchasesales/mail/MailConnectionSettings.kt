package com.apingu.purchasesales.mail

import android.content.Context
import android.content.pm.PackageManager
import com.apingu.purchasesales.BuildConfig
import java.security.MessageDigest
import java.util.Locale

/**
 * Microsoft OAuth Application (client) ID is public configuration, NOT a client secret.
 * Allow device-specific setup without rebuilding or changing GitHub Actions secrets.
 */
object MailConnectionSettings {
    private const val PREFS = "purchase_sales_mail_configuration"
    private const val KEY_MS_CLIENT_ID = "microsoft_public_client_id"
    private val guid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun isValidMicrosoftClientId(value: String): Boolean = guid.matches(value.trim())

    /**
     * A shared public client ID is built into the APK for all installations.
     * Prefer that registration over legacy per-device settings, so an old
     * override cannot accidentally send friends through another OAuth app.
     */
    fun microsoftClientId(context: Context): String =
        BuildConfig.MICROSOFT_CLIENT_ID.trim()
            .takeIf(::isValidMicrosoftClientId)
            ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MS_CLIENT_ID, null)?.trim().orEmpty()

    fun hasEmbeddedMicrosoftClientId(): Boolean =
        isValidMicrosoftClientId(BuildConfig.MICROSOFT_CLIENT_ID)

    fun saveMicrosoftClientId(context: Context, value: String) {
        val clientId = value.trim()
        require(isValidMicrosoftClientId(clientId)) {
            "Enter the Microsoft Entra Application (client) ID in UUID format, e.g. 00000000-0000-0000-0000-000000000000."
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MS_CLIENT_ID, clientId).apply()
    }

    /**
     * Public fingerprint of the EXACT APK installed on this device.
     * Google Cloud's Android OAuth client must match BOTH package and SHA-1.
     * Does not access or expose any signing private key.
     */
    @Suppress("DEPRECATION")
    fun signingSha1(context: Context): String {
        val info = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_SIGNING_CERTIFICATES
        )
        val signature = info.signingInfo?.apkContentsSigners?.firstOrNull()
            ?: error("Cannot read this APK's signing certificate")
        return MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
            .joinToString(":") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }
    }
}
