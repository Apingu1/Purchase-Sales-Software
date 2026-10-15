package com.apingu.purchasesales.mail

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * The AppAuth AuthorizationManagementActivity can send USER_CANCELED_AUTH_FLOW
 * when Android temporarily returns from a Microsoft account chooser/browser.
 * Instead, open the official login URL directly and receive its OAuth redirect
 * in our own manifest-declared activity. AppAuth still generates cryptographic
 * state + PKCE and exchanges tokens after verified return.
 */
object MicrosoftOAuthBrowser {
    private const val STORE = "purchase_sales_microsoft_pending_oauth"
    private const val ENTRY = "pending_v1"
    private const val KEY_ALIAS = "purchase_sales_microsoft_pending_aes_v1"
    private const val MAX_AGE_MS = 30L * 60L * 1000L
    private val configuration = AuthorizationServiceConfiguration(
        Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/authorize"),
        Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/token")
    )

    private fun key(): java.security.Key {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keystore.getKey(KEY_ALIAS, null)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return generator.generateKey()
    }

    private fun savePending(context: Context, request: AuthorizationRequest) {
        val item = JSONObject()
            .put("createdAt", System.currentTimeMillis())
            .put("request", request.jsonSerializeString())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(item.toString().toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(payload, Base64.NO_WRAP)
        check(context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putString(ENTRY, encoded).commit()) {
            "Unable to securely prepare Microsoft sign-in on this phone."
        }
    }

    @Synchronized
    private fun pending(context: Context): AuthorizationRequest {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val encoded = prefs.getString(ENTRY, null)
            ?: error("No pending Microsoft sign-in was found. Tap Connect Outlook to try again.")
        val request = try {
            val packed = Base64.decode(encoded, Base64.DEFAULT)
            require(packed.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, key(),
                GCMParameterSpec(128, packed.copyOfRange(0, 12))
            )
            val item = JSONObject(
                String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
            )
            val age = System.currentTimeMillis() - item.getLong("createdAt")
            require(age in 0..MAX_AGE_MS) {
                "Microsoft sign-in took too long. Please return and try again."
            }
            AuthorizationRequest.jsonDeserialize(item.getString("request"))
        } catch (ex: Exception) {
            clear(context)
            throw IllegalStateException(
                ex.message ?: "Saved sign-in session is no longer available. Try again."
            )
        }
        return request
    }

    @Synchronized
    private fun clear(context: Context) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().remove(ENTRY).commit()
    }

    @Synchronized
    fun start(activity: Activity) {
        val client = MailConnectionSettings.microsoftClientId(activity)
        require(MailConnectionSettings.isValidMicrosoftClientId(client)) {
            "The app owner must configure the Microsoft application client ID first."
        }
        // AppAuth generates random anti-CSRF state, nonce and S256 PKCE verifier.
        val request = AuthorizationRequest.Builder(
            configuration, client, ResponseTypeValues.CODE, Uri.parse(MailOAuth.MS_REDIRECT)
        ).setScope("User.Read Mail.Read offline_access")
            .setPrompt("select_account")
            .build()
        require(!request.state.isNullOrBlank() && !request.codeVerifier.isNullOrBlank()) {
            "Microsoft OAuth PKCE could not be generated."
        }
        savePending(activity, request)
        try {
            val external = Intent(Intent.ACTION_VIEW, request.toUri()).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            activity.startActivity(external)
        } catch (ex: Exception) {
            clear(activity)
            throw IllegalStateException("No browser could open Microsoft login: " +
                (ex.message ?: "Open a browser and try again."))
        }
    }

    /**
     * Only a direct custom-scheme callback from Microsoft's login is accepted.
     * Validate app URI and compare OAuth state BEFORE parsing the authorisation
     * code; never log or expose the code or PKCE verifier.
     */
    @Synchronized
    fun verifiedResponse(context: Context, callback: Uri): AuthorizationResponse {
        require(MicrosoftOAuthFlowValidation.validRedirect(
            callback.scheme, callback.host, callback.port, callback.path)) {
            "Unexpected redirect destination. Microsoft sign-in was not completed."
        }
        val request = pending(context)
        val returnedState = callback.getQueryParameter("state")
        require(MicrosoftOAuthFlowValidation.sameState(request.state, returnedState)) {
            "Microsoft sign-in security check failed (OAuth state mismatch). Please retry."
        }
        val providerError = callback.getQueryParameter("error")
        if (!providerError.isNullOrBlank()) {
            clear(context)
            val safeCode = providerError.filter { it.isLetterOrDigit() || it in "_-" }.take(80)
            val description = callback.getQueryParameter("error_description").orEmpty()
                .replace(Regex("\\s+"), " ").take(220)
            error("Microsoft denied sign-in ($safeCode): $description")
        }
        require(!callback.getQueryParameter("code").isNullOrBlank()) {
            "Microsoft returned without an authorization code. Please retry."
        }
        clear(context) // single-use authorization request, before token exchange
        return AuthorizationResponse.Builder(request).fromUri(callback).build()
    }
}

/** Pure validation, no Android dependencies: independently unit-testable. */
internal object MicrosoftOAuthFlowValidation {
    fun validRedirect(scheme: String?, host: String?, port: Int, path: String?): Boolean =
        scheme.equals("com.apingu.purchasesales", ignoreCase = true) &&
            host.equals("oauth2redirect", ignoreCase = true) &&
            port == -1 && (path.isNullOrEmpty() || path == "/")

    fun sameState(expected: String?, actual: String?): Boolean =
        !expected.isNullOrBlank() && !actual.isNullOrBlank() &&
            MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8),
                actual.toByteArray(Charsets.UTF_8)
            )
}
