package com.apingu.purchasesales.mail

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.apingu.purchasesales.MainActivity
import com.apingu.purchasesales.PurchaseSalesApplication
import com.apingu.purchasesales.data.EmailAccountEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Explicit non-exported callback for AppAuth browser sign-in. PendingIntent
 * completion/cancellation returns here even if the original Compose screen
 * was recreated while an external Microsoft login page was visible.
 */
class MicrosoftOAuthResultActivity : ComponentActivity() {
    companion object {
        const val ACTION_COMPLETE = "com.apingu.purchasesales.MICROSOFT_OAUTH_COMPLETE"
        const val ACTION_CANCEL = "com.apingu.purchasesales.MICROSOFT_OAUTH_CANCEL"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[MicrosoftOAuthResultModel::class.java]
        model.process(intent)
        setContent {
            val state by model.status.collectAsStateWithLifecycle()
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (state.pending) {
                                    CircularProgressIndicator()
                                } else {
                                    Icon(
                                        if (state.success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = if (state.success) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error
                                    )
                                }
                                Text("Outlook account connection",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold)
                                Text(state.message, style = MaterialTheme.typography.bodyMedium)
                                if (!state.pending) {
                                    Button(
                                        onClick = {
                                            startActivity(Intent(this@MicrosoftOAuthResultActivity,
                                                MainActivity::class.java).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                            })
                                            finish()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Return to Purchase & Sales") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal data class OutlookConnectionResult(
    val pending: Boolean = true,
    val success: Boolean = false,
    val message: String = "Finishing Microsoft sign-in securely..."
)

internal class MicrosoftOAuthResultModel(app: Application) : AndroidViewModel(app) {
    private val mutableStatus = MutableStateFlow(OutlookConnectionResult())
    val status = mutableStatus.asStateFlow()
    private var started = false

    /** Only exchange a one-time Microsoft authorisation code once per callback. */
    fun process(data: Intent) {
        if (started) return
        started = true
        viewModelScope.launch {
            try {
                if (data.action != Intent.ACTION_VIEW || data.data == null) {
                    mutableStatus.value = OutlookConnectionResult(
                        pending = false,
                        message = "No OAuth redirect was received from Microsoft. " +
                            "The login browser may have been closed before completing sign-in. " +
                            "Open Connect Outlook again; if you reached a Microsoft error page, " +
                            "note its error code."
                    )
                    return@launch
                }
                val context = getApplication<Application>().applicationContext
                val email = withContext(Dispatchers.IO) {
                    val callback = requireNotNull(data.data)
                    val response = MicrosoftOAuthBrowser.verifiedResponse(context, callback)
                    val (address, provider) = MailOAuth.completeMicrosoft(
                        context, response.toIntent()
                    )
                    val database = (getApplication<Application>() as PurchaseSalesApplication).database
                    val dao = database.dao()
                    val existing = dao.getEmailAccount(address)
                    require(existing == null || existing.provider == "MICROSOFT") {
                        "This email is already linked using another provider."
                    }
                    val linked = EmailAccountEntity(
                        id = existing?.id ?: 0, emailAddress = address, provider = provider,
                        displayName = address, isEnabled = true, connectionState = "CONNECTED"
                    )
                    if (existing == null) dao.insertEmailAccount(linked)
                    else dao.updateEmailAccount(linked)
                    address
                }
                mutableStatus.value = OutlookConnectionResult(
                    pending = false, success = true,
                    message = "Connected " + email + ". It is now available to Invoice Finder."
                )
            } catch (error: Exception) {
                mutableStatus.value = OutlookConnectionResult(
                    pending = false,
                    message = "Microsoft sign-in could not be completed: " +
                        (error.message ?: "Unknown error").take(300) +
                        ". Your existing Gmail accounts and purchase data were not changed."
                )
            }
        }
    }
}
