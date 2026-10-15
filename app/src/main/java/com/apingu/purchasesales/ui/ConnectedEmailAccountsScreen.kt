package com.apingu.purchasesales.ui

import android.app.Activity
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
import com.apingu.purchasesales.BuildConfig
import com.apingu.purchasesales.data.EmailAccountEntity
import com.apingu.purchasesales.mail.MailOAuth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedEmailAccountsScreen(vm: AppViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val accounts by vm.emailAccounts.collectAsStateWithLifecycle()
    var disconnect by remember { mutableStateOf<EmailAccountEntity?>(null) }
    val googleResult = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            runCatching { MailOAuth.googleTokenFromIntent(context, result.data!!) }
                .onSuccess(vm::connectGmailToken)
                .onFailure { vm.reportMailboxError(it.message ?: "Gmail sign-in failed") }
        } else vm.reportMailboxError("Gmail account authorisation was cancelled")
    }
    val microsoftResult = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.data != null) vm.completeMicrosoftConnection(result.data!!)
        else vm.reportMailboxError("Microsoft sign-in was cancelled")
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
                Button(
                    onClick = {
                        MailOAuth.googleAuthorize(activity,
                            resolution = { googleResult.launch(it) },
                            complete = vm::connectGmailToken,
                            error = vm::reportMailboxError
                        )
                    }, modifier = Modifier.fillMaxWidth()
                ) { Icon(Icons.Default.Add, null); Text(" Connect Gmail / Google Workspace") }
            }
            item {
                Button(
                    onClick = {
                        runCatching { MailOAuth.microsoftAuthIntent(activity) }
                            .onSuccess { microsoftResult.launch(it) }
                            .onFailure { vm.reportMailboxError(it.message ?: "Microsoft setup is incomplete") }
                    }, modifier = Modifier.fillMaxWidth(),
                    enabled = BuildConfig.MICROSOFT_CLIENT_ID.isNotBlank()
                ) { Icon(Icons.Default.Add, null); Text(" Connect Outlook / Microsoft 365") }
                if (BuildConfig.MICROSOFT_CLIENT_ID.isBlank()) Text(
                    "Microsoft Entra application registration is required before this button can be enabled. " +
                        "See the developer email integration setup guide.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
