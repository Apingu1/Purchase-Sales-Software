package com.apingu.purchasesales.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.apingu.purchasesales.data.EmailAccountEntity
import com.apingu.purchasesales.mail.GmailImapCredentials
import com.apingu.purchasesales.mail.MailConnectionSettings
import com.apingu.purchasesales.mail.MailOAuth

/**
 * Everyday onboarding never requests Google Cloud credentials or email account passwords.
 * Gmail uses a dedicated Google-generated App Password; Microsoft opens official OAuth portal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedEmailAccountsScreen(vm: AppViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val urls = LocalUriHandler.current
    val accounts by vm.emailAccounts.collectAsStateWithLifecycle()
    val connectingImap by vm.connectingImap.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf("") }
    var appPassword by remember { mutableStateOf("") }
    var showGmailHelp by remember { mutableStateOf(false) }
    var showOutlookHelp by remember { mutableStateOf(false) }
    var showOwnerSetup by remember { mutableStateOf(false) }
    var disconnect by remember { mutableStateOf<EmailAccountEntity?>(null) }
    var diagnostic by remember { mutableStateOf("") }
    var microsoftClientId by remember {
        mutableStateOf(MailConnectionSettings.microsoftClientId(context))
    }

    Scaffold(
        topBar = { TopAppBar(
            title = { Text("Connected Email Accounts") },
            navigationIcon = { IconButton({ nav.popBackStack() }) {
                Icon(Icons.Default.ArrowBack, "Back")
            } }
        ) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Connect purchasing mailboxes", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                Text("Each email account is authorised separately and stays on this phone. " +
                    "Invoice Finder searches only the exact mailbox and order number saved on a purchase.",
                    style = MaterialTheme.typography.bodySmall)
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Icon(Icons.Default.Mail, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Gmail — App Password", Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium)
                            IconButton(onClick = { showGmailHelp = true }) {
                                Icon(Icons.Default.HelpOutline, "How to get a Gmail App Password")
                            }
                        }
                        Text("Free Gmail IMAP connection. No Google API keys or developer setup. " +
                            "You must first enable 2-Step Verification on that Google account.",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = address, onValueChange = { address = it.trim() },
                            label = { Text("Gmail email address") },
                            placeholder = { Text("example@gmail.com") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = appPassword, onValueChange = { appPassword = it },
                            label = { Text("16-character Google App Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                vm.connectGmailAppPassword(address, appPassword) {
                                    address = ""
                                    appPassword = ""
                                }
                            },
                            enabled = !connectingImap && address.isNotBlank() &&
                                GmailImapCredentials.normalizePassword(appPassword).length == 16,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Link, null)
                            Text(if (connectingImap) " Testing secure connection..." else " Connect Gmail")
                        }
                        TextButton(onClick = { showGmailHelp = true }) {
                            Icon(Icons.Default.HelpOutline, null)
                            Text(" How do I create an App Password?")
                        }
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Icon(Icons.Default.Email, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Outlook / Hotmail / Microsoft 365", Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium)
                            IconButton({ showOutlookHelp = true }) {
                                Icon(Icons.Default.HelpOutline, "How to connect Outlook")
                            }
                        }
                        Text("Sign in through Microsoft's official portal and approve read-only email access.",
                            style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = {
                                runCatching { MailOAuth.startMicrosoftAuthorization(activity) }
                                    .onFailure {
                                        diagnostic = it.message ?: "Microsoft sign-in could not start."
                                        vm.reportMailboxError(diagnostic)
                                    }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = MailConnectionSettings.isValidMicrosoftClientId(
                                MailConnectionSettings.microsoftClientId(context))
                        ) {
                            Icon(Icons.Default.Login, null)
                            Text(" Connect Outlook")
                        }
                        Text(
                            "Microsoft sign-in opens your browser and returns to the app after " +
                                "completion. If Microsoft displays an error, note its code; " +
                                "do not share passwords or authorisation URLs.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (!MailConnectionSettings.isValidMicrosoftClientId(
                                MailConnectionSettings.microsoftClientId(context))) {
                            Text("Outlook requires the app owner to configure a Microsoft sign-in registration once. " +
                                "Individual mailbox users do not need keys or developer accounts.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (MailConnectionSettings.hasEmbeddedMicrosoftClientId()) {
                            Text(
                                "Ready to connect. Your Microsoft sign-in configuration is " +
                                    "included in this app — no developer settings or API keys needed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            TextButton(onClick = { showOwnerSetup = !showOwnerSetup }) {
                                Text(if (showOwnerSetup) "Hide app-owner setup" else "App-owner configuration")
                            }
                            if (showOwnerSetup) {
                                Text(
                                    "Only the app owner needs the public Microsoft Application " +
                                        "(client) ID. Other users never need account-specific API keys.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                OutlinedTextField(
                                    value = microsoftClientId,
                                    onValueChange = { microsoftClientId = it.trim() },
                                    label = { Text("Microsoft Application (client) ID") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true
                                )
                                Text("Redirect URI: " + MailOAuth.MS_REDIRECT,
                                    style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(
                                    onClick = {
                                        runCatching {
                                            MailConnectionSettings.saveMicrosoftClientId(context, microsoftClientId)
                                        }.onSuccess {
                                            diagnostic = "Microsoft configuration saved on this device."
                                        }.onFailure {
                                            diagnostic = it.message ?: "Invalid client ID"
                                        }
                                    },
                                    enabled = MailConnectionSettings.isValidMicrosoftClientId(microsoftClientId)
                                ) {
                                    Icon(Icons.Default.Save, null)
                                    Text(" Save configuration")
                                }
                            }
                        }
                    }
                }
            }
            if (diagnostic.isNotBlank()) item {
                Text(diagnostic, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            item {
                HorizontalDivider()
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Connected accounts", Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium)
                    Text(accounts.count { it.isEnabled }.toString() + " active",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (accounts.isEmpty()) item {
                Text("No accounts connected. Connect Gmail with an App Password or sign in to Outlook.")
            }
            items(accounts, key = { it.id }) { account ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(account.emailAddress, fontWeight = FontWeight.SemiBold)
                            Text(when (account.provider) {
                                "GMAIL_IMAP" -> "Gmail IMAP / App Password"
                                "GMAIL" -> "Gmail / Google OAuth"
                                "MICROSOFT" -> "Outlook / Microsoft"
                                else -> account.provider
                            } + " · " + account.connectionState,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (account.isEnabled) {
                            IconButton({ disconnect = account }) {
                                Icon(Icons.Default.LinkOff, "Disconnect")
                            }
                        } else {
                            Icon(Icons.Default.LinkOff, "Disconnected")
                        }
                    }
                }
            }
            item {
                Text("Gmail App Passwords are encrypted using Android Keystore and never synced to Dropbox. " +
                    "Only invoice candidates you explicitly approve become permanent purchase documents.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showGmailHelp) {
        AlertDialog(
            onDismissRequest = { showGmailHelp = false },
            title = { Text("How to create a Google App Password") },
            text = {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1. Sign in to the Gmail account you want to connect.")
                    Text("2. Enable 2-Step Verification (2FA): Google Account → Security → 2-Step Verification. This is required.")
                    Text("3. Open Google's official App Passwords page below.")
                    Text("4. Create an app password and name it 'Purchase Sales Software'.")
                    Text("5. Copy the generated 16-character password into this app along with your Gmail address.")
                    Text("6. Tap Connect Gmail. We'll verify it securely over IMAP (port 993, TLS) and remember it on this phone.")
                    Text("Use the APP PASSWORD, not your normal Google account password. " +
                        "Google may revoke App Passwords when you change your main Google password.")
                    TextButton(onClick = {
                        urls.openUri("https://myaccount.google.com/signinoptions/two-step-verification")
                    }) { Icon(Icons.Default.OpenInNew, null); Text(" Enable Google 2-Step Verification") }
                    TextButton(onClick = {
                        urls.openUri("https://myaccount.google.com/apppasswords")
                    }) { Icon(Icons.Default.OpenInNew, null); Text(" Open Google App Passwords") }
                }
            },
            confirmButton = { TextButton(onClick = { showGmailHelp = false }) { Text("Done") } }
        )
    }

    if (showOutlookHelp) {
        AlertDialog(
            onDismissRequest = { showOutlookHelp = false },
            title = { Text("Connect an Outlook account") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Tap Connect Outlook.")
                    Text("2. Sign in on the official Microsoft login page using your Outlook, Hotmail or Microsoft 365 account.")
                    Text("3. Approve read-only access, then return to this app.")
                    Text("4. Repeat for other accounts. Each account is linked independently.")
                    Text("Microsoft OAuth requires a single public application registration configured by the app owner. " +
                        "Your friends do not need individual client IDs or developer keys.")
                }
            },
            confirmButton = { TextButton({ showOutlookHelp = false }) { Text("Done") } }
        )
    }
    disconnect?.let { account ->
        AlertDialog(
            onDismissRequest = { disconnect = null },
            title = { Text("Disconnect " + account.emailAddress + "?") },
            text = {
                Text("Future invoice searches will no longer use this mailbox. " +
                    "Previously approved invoices are not deleted. For Gmail, the saved App Password is removed.")
            },
            confirmButton = { TextButton({
                vm.disconnectMailbox(account)
                disconnect = null
            }) { Text("Disconnect") } },
            dismissButton = { TextButton({ disconnect = null }) { Text("Cancel") } }
        )
    }
}
