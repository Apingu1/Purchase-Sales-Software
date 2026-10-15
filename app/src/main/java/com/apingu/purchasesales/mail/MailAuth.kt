package com.apingu.purchasesales.mail

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import com.apingu.purchasesales.BuildConfig
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationRequest as MicrosoftAuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.net.Uri

/** Android Keystore-backed encryption for persistent Microsoft OAuth AuthState. */
object MailCredentialVault {
    private const val KEY = "purchase_sales_invoice_mail_key_v1"
    private const val PREFS = "mail_oauth_state"
    private fun secretKey(): java.security.Key {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(KEY, null)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
    fun save(context: Context, email: String, state: AuthState) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val bytes = cipher.doFinal(state.jsonSerializeString().toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv + bytes, Base64.NO_WRAP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(email.trim().lowercase(), encoded).apply()
    }
    fun load(context: Context, email: String): AuthState? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(email.trim().lowercase(), null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            AuthState.jsonDeserialize(String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8))
        }.getOrNull()
    }
    fun remove(context: Context, email: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(email.trim().lowercase()).apply()
    }
}

/** Connections use the current native app's signing key/redirect registration. */
object MailOAuth {
    const val GMAIL_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
    const val MS_REDIRECT = "com.apingu.purchasesales://oauth2redirect"
    private val MS_SCOPES = "User.Read Mail.Read offline_access"
    private val configuration by lazy {
        AuthorizationServiceConfiguration(
            Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/authorize"),
            Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/token")
        )
    }

    private var activeMicrosoftService: AuthorizationService? = null

    /**
     * Use explicit completion AND cancellation callbacks rather than an activity result.
     * Android may recreate the Compose screen while the external Microsoft browser is open.
     * AppAuth returns a filled-in PendingIntent with the OAuth response, even after recreation.
     */
    @Synchronized
    fun startMicrosoftAuthorization(activity: Activity, useExternalBrowser: Boolean = true) {
        val clientId = MailConnectionSettings.microsoftClientId(activity)
        require(MailConnectionSettings.isValidMicrosoftClientId(clientId)) {
            "The app owner must configure the Microsoft Entra public client ID before signing in"
        }
        val request = MicrosoftAuthorizationRequest.Builder(
            configuration, clientId,
            ResponseTypeValues.CODE, Uri.parse(MS_REDIRECT)
        ).setScope(MS_SCOPES).setPrompt("select_account").build()

        // AppAuth must add authorization response/error extras to the callback PendingIntent.
        // Both targets are explicit, non-exported components of our own app.
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                android.app.PendingIntent.FLAG_MUTABLE else 0)
        val success = android.app.PendingIntent.getActivity(
            activity, 6401,
            Intent(activity, MicrosoftOAuthResultActivity::class.java).apply {
                action = MicrosoftOAuthResultActivity.ACTION_COMPLETE
            }, flags
        )
        val cancelled = android.app.PendingIntent.getActivity(
            activity, 6402,
            Intent(activity, MicrosoftOAuthResultActivity::class.java).apply {
                action = MicrosoftOAuthResultActivity.ACTION_CANCEL
            }, flags
        )
        activeMicrosoftService?.dispose()
        // A full system-browser activity does not share the fragile Custom Tab
        // task stack on some devices / account-chooser and authenticator handoffs.
        // Keep AppAuth PKCE, redirect verification and token exchange unchanged.
        // A Custom Tab remains available as an explicit fallback in the UI.
        val service = if (useExternalBrowser) {
            val config = net.openid.appauth.AppAuthConfiguration.Builder()
                .setBrowserMatcher(net.openid.appauth.browser.BrowserMatcher { browser ->
                    !browser.useCustomTab
                })
                .build()
            AuthorizationService(activity, config)
        } else {
            AuthorizationService(activity)
        }
        activeMicrosoftService = service
        try {
            service.performAuthorizationRequest(request, success, cancelled)
        } catch (ex: Exception) {
            service.dispose()
            activeMicrosoftService = null
            throw ex
        }
    }

    @Synchronized
    fun finishMicrosoftAuthorization() {
        activeMicrosoftService?.dispose()
        activeMicrosoftService = null
    }

    suspend fun completeMicrosoft(context: Context, data: Intent): Pair<String, String> {
        val response = AuthorizationResponse.fromIntent(data)
        val error = AuthorizationException.fromIntent(data)
        if (response == null) throw IllegalStateException(error?.errorDescription ?: "Microsoft sign-in did not complete")
        val auth = requireNotNull(response)
        val state = AuthState(auth, error)
        val service = AuthorizationService(context)
        val token = try {
            suspendCancellableCoroutine<String> { cont ->
                service.performTokenRequest(auth.createTokenExchangeRequest()) { result, ex ->
                    state.update(result, ex)
                    if (!cont.isActive) return@performTokenRequest
                    if (result?.accessToken != null) cont.resume(result.accessToken!!)
                    else cont.resumeWithException(IllegalStateException(ex?.errorDescription ?: "Microsoft token request failed"))
                }
            }
        } finally { service.dispose() }
        val profile = MailRest.getJson("https://graph.microsoft.com/v1.0/me?%24select=mail,userPrincipalName", token)
        val address = profile.optString("mail").ifBlank { profile.optString("userPrincipalName") }
            .trim().lowercase()
        require(address.contains("@")) { "Microsoft did not return a usable email identity" }
        MailCredentialVault.save(context, address, state)
        return address to "MICROSOFT"
    }

    suspend fun microsoftToken(context: Context, email: String): String {
        val state = MailCredentialVault.load(context, email)
            ?: error("Reconnect Microsoft account: token missing or no longer decryptable")
        val service = AuthorizationService(context)
        try {
            return suspendCancellableCoroutine { cont ->
                state.performActionWithFreshTokens(service) { token, _, ex ->
                    if (!cont.isActive) return@performActionWithFreshTokens
                    if (ex != null || token.isNullOrBlank()) cont.resumeWithException(
                        IllegalStateException(ex?.errorDescription ?: "Microsoft account needs reconnection"))
                    else {
                        MailCredentialVault.save(context, email, state)
                        cont.resume(token)
                    }
                }
            }
        } finally { service.dispose() }
    }

    fun googleAuthorize(activity: Activity,
        resolution: (IntentSenderRequest) -> Unit,
        complete: (String) -> Unit,
        error: (String) -> Unit,
        accountEmail: String? = null
    ) {
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GMAIL_SCOPE)))
        if (!accountEmail.isNullOrBlank()) {
            builder.setAccount(Account(accountEmail, "com.google"))
        }
        Identity.getAuthorizationClient(activity).authorize(builder.build())
            .addOnSuccessListener { result ->
                if (result.hasResolution()) resolution(IntentSenderRequest.Builder(requireNotNull(result.pendingIntent).intentSender).build())
                else if (!result.accessToken.isNullOrBlank()) complete(result.accessToken!!)
                else error("Google did not issue an access token")
            }
            .addOnFailureListener { error(googleAuthorizationError(it)) }
    }

    /**
     * Show the actual provider error rather than labelling every unsuccessful
     * resolution as a deliberate user cancellation.
     */
    fun googleAuthorizationError(cause: Throwable): String {
        val api = generateSequence(cause) { it.cause }
            .filterIsInstance<ApiException>()
            .firstOrNull()
        return when (api?.statusCode) {
            10 -> "Google OAuth configuration error (status 10). Check the Android OAuth client's package name, signing certificate SHA-1, Gmail API and test-user consent configuration."
            16 -> "Google authorization was cancelled (status 16). If you selected an account rather than cancelling, check its OAuth consent/test-user configuration."
            null -> "Google authorization failed: " + (cause.localizedMessage ?: "Unknown error")
            else -> "Google authorization failed (status " + (api?.statusCode ?: "unknown") + "): " +
                (api?.localizedMessage ?: "Check the Google Cloud OAuth app configuration")
        }
    }

    fun googleTokenFromIntent(context: Context, intent: Intent): String =
        Identity.getAuthorizationClient(context)
            .getAuthorizationResultFromIntent(intent).accessToken
                ?: error("No Gmail access token was returned")

    suspend fun googleSilentToken(context: Context, email: String): String =
        suspendCancellableCoroutine { cont ->
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(GMAIL_SCOPE)))
                .setAccount(Account(email, "com.google")).build()
            Identity.getAuthorizationClient(context).authorize(request)
                .addOnSuccessListener { result ->
                    if (!cont.isActive) return@addOnSuccessListener
                    if (result.hasResolution() || result.accessToken.isNullOrBlank()) {
                        cont.resumeWithException(IllegalStateException("Reconnect Gmail account " + email))
                    } else cont.resume(result.accessToken!!)
                }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        }

    suspend fun verifiedGmailAddress(token: String): String {
        val profile = MailRest.getJson("https://gmail.googleapis.com/gmail/v1/users/me/profile", token)
        return profile.getString("emailAddress").trim().lowercase().also {
            require(it.contains("@")) { "Gmail account identity could not be verified" }
        }
    }
}

object MailRest {
    fun getJson(url: String, token: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("Authorization", "Bearer " + token)
        conn.setRequestProperty("Accept", "application/json")
        return try {
            val code = conn.responseCode
            val response = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Mailbox request failed (HTTP " + code + ")")
            JSONObject(response)
        } finally { conn.disconnect() }
    }
}
