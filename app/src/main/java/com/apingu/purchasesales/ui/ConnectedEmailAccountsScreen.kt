package com.apingu.purchasesales.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.apingu.purchasesales.data.EmailAccountEntity
import com.apingu.purchasesales.mail.MailOAuth
import com.apingu.purchasesales.mail.MailConnectionSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedEmailAccountsScreen(vm: AppViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val accounts by vm.emailAccounts.collectAsStateWithLifecycle()
    var disconnect by remember { mutableStateOf<EmailAccountEntity?>(null) }
    var connectionProblem by remember { mutableStateOf("") }
    fun showConnectionProblem(message: String) {
        connectionProblem = message
        vm.reportMailboxError(message)
    }
    var googleAddress by remember { mutableStateOf("") }
    var requestedGoogleAddress by remember { mutableStateOf("") }
    var microsoftClientId by remember { mutableStateOf(MailConnectionSettings.microsoftClientId(context)) }
    val installedSha1 = remember {
        runCatching { MailConnectionSettings.signingSha1(context) }
            .getOrElse { "Unavailable: " + (it.message ?: "cannot read signing certificate") }
    }
    val googleResult = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        // A failed OAuth handshake can also return RESULT_CANCELED.
        // Parse any provider response before guessing that the user cancelled.
        val data = result.data
        if (data != null) {
            runCatching { MailOAuth.googleTokenFromIntent(context, data) }
                .onSuccess { vm.connectGmailToken(it, requestedGoogleAddress) }
                .onFailure { showConnectionProblem(MailOAuth.googleAuthorizationError(it)) }
        } else if (result.resultCode == Activity.RESULT_CANCELED) {
            showConnectionProblem(
                "Google account selection ended without an authorization response. " +
                    "If you selected an account (rather than pressing Back), check the " +
                    "Google Cloud Android OAuth client, the APK signing SHA-1 shown below, " +
                    "and that this Gmail account is an authorized test user."
            )
        } else {
            showConnectionProblem("Google did not return an authorization result (Android result " +
                result.resultCode + "). Check Google Cloud configuration.")
        }
    }
    val microsoftResult = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.data != null) vm.completeMicrosoftConnection(result.data!!)
        else showConnectionProblem("Microsoft sign-in was cancelled")
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Connected Email Accounts") },
            navigationIcon = { IconButton({ nav.popBackStack() }) {
                Icon(Icons.Default.ArrowBack, "Back")
            } }) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { inset ->
        LazyColumn(
            Modifier.padding(inset).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (connectionProblem.isNotBlank()) item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Account connection diagnostic", fontWeight = FontWeight.SemiBold)
                        Text(connectionProblem, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { connectionProblem = "" }) {
                            Text("Dismiss")
                        }
                    }
                }
            }
            item {
                Text("Purchasing mailboxes", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    "Connect every Gmail or Outlook/Microsoft 365 account you use for purchasing. " +
                        "Only the exact connected email address saved on a purchase will be searched. " +
                        "No other mailbox is scanned.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            item {
                OutlinedTextField(
                    value = googleAddress, onValueChange = { googleAddress = it },
                    label = { Text("Gmail address to connect (optional)") },
                    supportingText = { Text("Enter the exact Gmail address when linking multiple Google accounts.") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Button(
                    onClick = {
                        requestedGoogleAddress = googleAddress.trim()
                        MailOAuth.googleAuthorize(activity,
                            resolution = {
                                requestedGoogleAddress = googleAddress.trim()
                                googleResult.launch(it)
                            },
                            complete = { vm.connectGmailToken(it, googleAddress.trim()) },
                            error = ::showConnectionProblem,
                            accountEmail = googleAddress.trim().ifBlank { null }
                        )
                    }, modifier = Modifier.fillMaxWidth()
                ) { Icon(Icons.Default.Add, null); Text(" Connect Gmail / Google Workspace") }
            }
            item {
                HorizontalDivider()
                Text("Google OAuth setup", style = MaterialTheme.typography.titleMedium)
                Text("If choosing an email closes the Google sign-in screen, verify your Google Cloud Android OAuth client matches the installed APK. Both values below are required.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Android package: " + context.packageName,
                    style = MaterialTheme.typography.bodySmall)
                Text("Installed APK certificate SHA-1:\n" + installedSha1,
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Android APK SHA-1", installedSha1))
                    showConnectionProblem("APK signing SHA-1 copied")
                }) { Icon(Icons.Default.ContentCopy, null); Text(" Copy APK SHA-1") }
                Text("Gmail API must be enabled and gmail.readonly consent configured. If your Google OAuth app is in testing, add each purchasing Google account as a test user.",
                    style = MaterialTheme.typography.bodySmall)
            }
            item {
                HorizontalDivider()
                Text("Microsoft / Outlook OAuth setup", style = MaterialTheme.typography.titleMedium)
                Text("Create a Microsoft Entra app registration (Mobile and desktop public client), then paste its Application (client) ID below. You do not need a client secret.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = microsoftClientId, onValueChange = { microsoftClientId = it.trim() },
                    label = { Text("Microsoft Application (client) ID") },
                    placeholder = { Text("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx") },
                    supportingText = { Text("Redirect URI: " + MailOAuth.MS_REDIRECT) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedButton(onClick = {
                    runCatching { MailConnectionSettings.saveMicrosoftClientId(context, microsoftClientId) }
                        .onSuccess { showConnectionProblem("Microsoft client ID saved on this device") }
                        .onFailure { showConnectionProblem(it.message ?: "Invalid Microsoft client ID") }
                }, enabled = MailConnectionSettings.isValidMicrosoftClientId(microsoftClientId)) {
                    Icon(Icons.Default.Save, null); Text(" Save client ID")
                }
                Button(
                    onClick = {
                        runCatching {
                            MailConnectionSettings.saveMicrosoftClientId(context, microsoftClientId)
                            MailOAuth.microsoftAuthIntent(activity)
                        }.onSuccess { microsoftResult.launch(it) }
                            .onFailure { showConnectionProblem(it.message ?: "Microsoft registration is incomplete") }
                    }, modifier = Modifier.fillMaxWidth()
                ) { Icon(Icons.Default.Add, null); Text(" Connect Outlook / Microsoft 365") }
                if (!MailConnectionSettings.isValidMicrosoftClientId(microsoftClientId)) {
                    Text("Enter a valid Application (client) ID to activate Microsoft sign-in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                HorizontalDivider()
                Text("Accounts", style = MaterialTheme.typography.titleMedium)
            }
            if (accounts.isEmpty()) item { Text("No connected purchasing mailboxes yet.") }
            else items(accounts, key = { it.id }) { account ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(account.emailAddress, fontWeight = FontWeight.SemiBold)
                        Text(account.provider + " • " + account.connectionState,
                            style = MaterialTheme.typography.bodySmall)
                        if (account.isEnabled) OutlinedButton({ disconnect = account }) {
                            Icon(Icons.Default.LinkOff, null); Text(" Disconnect")
                        } else Text("Disconnected — use the Connect button to authorise again.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Text(
                    "Authorisations are read-only. Microsoft refresh credentials are encrypted in the " +
                        "Android Keystore; Gmail access is controlled by Google Play Services. " +
                        "Disconnecting an account blocks further searches for that mailbox.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
    disconnect?.let { account ->
        AlertDialog(
            onDismissRequest = { disconnect = null },
            title = { Text("Disconnect " + account.emailAddress + "?") },
            text = { Text("This prevents future invoice searches from using this mailbox. " +
                "Previously approved invoice files remain attached to purchases.") },
            confirmButton = { TextButton({ vm.disconnectMailbox(account); disconnect = null }) { Text("Disconnect") } },
            dismissButton = { TextButton({ disconnect = null }) { Text("Cancel") } }
        )
    }
}
