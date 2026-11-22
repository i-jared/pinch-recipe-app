package com.example.pinch.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.pinch.AppContainer
import com.example.pinch.sync.ServerGroup
import com.example.pinch.sync.SharedRecipe
import com.example.pinch.ui.theme.Pinch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ServerSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val state by container.server.state.collectAsState()
    var page by rememberSaveable { mutableStateOf(if (state.connected && !state.managed) "self-hosting" else "account") }
    val current = if (state.managed) "account" else page
    val back: () -> Unit = when {
        current == "reading" -> { { page = "self-hosting" } }
        current == "self-hosting" && !state.connected -> { { page = "account" } }
        else -> onBack
    }
    BackHandler(onBack = back)
    if (current == "reading") {
        SettingsPage("Reading recipes", back) { AIReadingCard(container.ai) }
    } else {
        ServerSettingsForm(container, back, selfHosted = current == "self-hosting", onSelfHost = { page = "self-hosting" }, onReading = { page = "reading" })
    }
}

@Composable
private fun ServerSettingsForm(container: AppContainer, onBack: () -> Unit, selfHosted: Boolean, onSelfHost: () -> Unit, onReading: () -> Unit) {
    val server = container.server
    val state by server.state.collectAsState()
    var address by remember { mutableStateOf(state.server) }
    var username by remember { mutableStateOf(state.username) }
    var password by remember { mutableStateOf("") }
    var registrationKey by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var disconnect by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deletionPassword by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    SettingsPage(if (selfHosted) "Self-hosting" else "Account", onBack) {
        if (state.connected) {
            if (selfHosted) Text(state.server, style = MaterialTheme.typography.titleMedium, color = Pinch.colors.ink)
            Text("Signed in as ${state.username}", color = Pinch.colors.inkSoft)
            state.lastSynced?.let { Text("Last backup ${DateUtils.getRelativeTimeSpanString(it.toEpochMilli())}", color = Pinch.colors.inkSoft) }
            if (selfHosted) Button(onClick = { server.syncIfConnected() }, enabled = !state.syncing) {
                Text(if (state.syncing) "Backing up…" else "Back up now")
            }
            if (state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, color = Pinch.colors.cherry) }
            Text("Your library syncs automatically.", color = Pinch.colors.inkSoft)
            TextButton(enabled = !busy, onClick = { disconnect = true }) { Text(if (selfHosted) "Disconnect" else "Sign out", color = Pinch.colors.cherry) }
            TextButton(enabled = !busy, onClick = { deleting = true }) { Text("Delete account", color = Pinch.colors.cherry) }
            error?.let { Text(it, color = Pinch.colors.cherry) }
        } else {
            Text(if (selfHosted) "Connecting merges this device’s library with your private server backup."
                else "Signing in syncs this device’s library to Pinch. Recipe imports and Cook Mode use OpenRouter to read recipes and match ingredients to steps.", color = Pinch.colors.inkSoft)
            if (selfHosted) ServerField("Server address", address, { address = it }, keyboard = KeyboardType.Uri, enabled = !busy)
            ServerField("Username", username, { username = it }, enabled = !busy)
            ServerField(if (register) "Password (at least 10 characters)" else "Password", password, { password = it }, secret = true, enabled = !busy)
            if (selfHosted) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = register, onCheckedChange = { register = it }, enabled = !busy)
                Text("Create an account", Modifier.padding(top = 14.dp), color = Pinch.colors.ink)
            }
            if (selfHosted && register) ServerField("Registration key from your host", registrationKey, { registrationKey = it }, secret = true, enabled = !busy)
            val ready = username.isNotBlank() && password.codePointCount(0, password.length) >= 10 &&
                (!selfHosted || (address.isNotBlank() && (!register || registrationKey.isNotBlank())))
            Button(enabled = !busy && ready, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        if (selfHosted) server.connect(address, username, password, registrationKey, register)
                        else server.connectManaged(username, password, register)
                        password = ""; registrationKey = ""
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) { error = e.message
                    } finally { busy = false }
                }
            }) { Text(if (busy) "Connecting…" else if (register) "Create account" else if (selfHosted) "Connect & back up" else "Sign in") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (selfHosted) {
                Text("Your server host can access what you back up. Only recipes in shared cookbooks are visible to people you invite.", style = MaterialTheme.typography.bodySmall, color = Pinch.colors.inkSoft)
            } else {
                if (!register) TextButton(enabled = !busy, onClick = {
                    error = null
                    runCatching { uriHandler.openUri(server.startManagedAuthorization()) }.onFailure { error = it.message }
                }) { Text("Sign in in browser") }
                TextButton(enabled = !busy, onClick = { register = !register; error = null }) {
                    Text(if (register) "I already have an account" else "Create an account")
                }
                TextButton(enabled = !busy, onClick = onSelfHost) { Text("Use your own server") }
            }
            (error ?: state.message)?.let { Text(it, color = Pinch.colors.cherry) }
        }
        if (selfHosted && !state.managed) TextButton(onClick = onReading) { Text("Reading recipes") }
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false; deletionPassword = "" }, title = { Text("Delete account?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This permanently removes your server backup, cookbooks you own. Recipes saved on devices stay.")
            ServerField("Confirm your password", deletionPassword, { deletionPassword = it }, secret = true)
        } },
        confirmButton = { TextButton(enabled = deletionPassword.codePointCount(0, deletionPassword.length) >= 10, onClick = {
            deleting = false; busy = true; error = null
            scope.launch {
                try { server.deleteAccount(deletionPassword) } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message } finally { busy = false; deletionPassword = "" }
            }
        }) { Text("Delete account") } },
        dismissButton = { TextButton(onClick = { deleting = false; deletionPassword = "" }) { Text("Cancel") } },
    )
    if (disconnect) AlertDialog(
        onDismissRequest = { disconnect = false }, title = { Text(if (selfHosted) "Disconnect from your server?" else "Sign out of Pinch?") },
        text = { Text("Your local recipes and server backup will be kept.") },
        confirmButton = { TextButton(onClick = { disconnect = false; server.disconnect() }) { Text(if (selfHosted) "Disconnect" else "Sign out") } },
        dismissButton = { TextButton(onClick = { disconnect = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ServerField(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false,
                        keyboard: KeyboardType = KeyboardType.Text, enabled: Boolean = true) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
}
