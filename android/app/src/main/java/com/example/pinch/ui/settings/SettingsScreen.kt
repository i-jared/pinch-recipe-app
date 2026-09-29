package com.example.pinch.ui.settings

import android.app.Activity
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AlarmOn
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import android.content.ClipboardManager
import android.content.Context
import java.text.NumberFormat
import java.util.Currency
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.pinch.AppContainer
import com.example.pinch.ai.AIAccount
import com.example.pinch.ai.AIConfig
import com.example.pinch.ai.OpenRouterClient
import com.example.pinch.ai.OpenRouterFailure
import com.example.pinch.BuildConfig
import com.example.pinch.drive.DriveSync
import com.example.pinch.model.SampleRecipes
import com.example.pinch.ui.components.PrimaryButton
import com.example.pinch.ui.components.SoftButton
import com.example.pinch.ui.theme.Pinch
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val recipes by container.repository.recipes.collectAsState()
    val scope = rememberCoroutineScope()
    var exactAlarmsAllowed by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        exactAlarmsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize().background(Pinch.colors.canvas)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(Pinch.colors.sunken).clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Pinch.colors.ink) }
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = Pinch.colors.ink, modifier = Modifier.padding(start = 14.dp))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Label("Reading recipes")
            AIReadingCard(container.ai)

            Label("Backup and sync")
            DriveCard(container.drive)

            Label("Kitchen timers")
            Card {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.AlarmOn, null, tint = Pinch.colors.jadeDeep)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (exactAlarmsAllowed) "Timers ring on time" else "Allow on-time timer alerts", style = MaterialTheme.typography.titleMedium, color = Pinch.colors.ink)
                        Text(
                            if (exactAlarmsAllowed) "When Pinch is in the background, a notification rings the moment a timer ends."
                            else "Android may delay alerts from background apps by a few minutes. Allow alarms so timers ring right on time.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Pinch.colors.inkSoft,
                        )
                        if (!exactAlarmsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Spacer(Modifier.padding(top = 10.dp))
                            SoftButton("Allow alarms", onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                                )
                            })
                        }
                    }
                }
            }

            Label("Recipes")
            Card {
                Row(Modifier.fillMaxWidth().clickable { container.repository.saveAll(SampleRecipes.all()) }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Pinch.colors.jadeDeep)
                    Spacer(Modifier.width(12.dp))
                    Text("Add three sample recipes", style = MaterialTheme.typography.titleMedium, color = Pinch.colors.jadeDeep)
                }
            }
            Text(
                "${recipes.size} recipe${if (recipes.size == 1) "" else "s"} saved on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = Pinch.colors.inkSoft,
                modifier = Modifier.padding(start = 4.dp),
            )

            Label("About")
            Card {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Lock, null, tint = Pinch.colors.ink)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Your recipes live on this device", style = MaterialTheme.typography.titleMedium, color = Pinch.colors.ink)
                        Text(
                            "Pinch saves your recipes on this device and works without an internet connection. If you add an OpenRouter key, the photos, text and web pages you add are sent to OpenRouter to be read, and nothing else. If you connect Google Drive, your recipes are backed up to a Pinch Recipes folder in your own Drive.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Pinch.colors.inkSoft,
                        )
                    }
                }
            }
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = Pinch.colors.inkSoft, modifier = Modifier.padding(start = 4.dp, bottom = 24.dp))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Pinch.colors.inkSoft, modifier = Modifier.padding(start = 4.dp, top = 14.dp))
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Pinch.colors.surface).padding(18.dp)) { content() }
}

@Composable
private fun DriveCard(drive: DriveSync) {
    val state by drive.state.collectAsState()
    val email by drive.accountEmail.collectAsState()
    val lastSynced by drive.lastSynced.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmDisconnect by remember { mutableStateOf(false) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) drive.completeConnect(result.data) else drive.cancelConnect()
    }
    val connected = email != null

    Card {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(50.dp).clip(RoundedCornerShape(14.dp)).background(Pinch.colors.jade.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                    Icon(if (connected) Icons.Rounded.CloudDone else Icons.Rounded.CloudUpload, null, tint = Pinch.colors.jadeDeep)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(if (connected) "Google Drive is connected" else "Google Drive", style = MaterialTheme.typography.titleMedium, color = Pinch.colors.ink)
                    val status = when (val s = state) {
                        DriveSync.State.Disconnected -> "Back up your recipes to your own Drive and use them on your other phones and tablets, Android or iPhone."
                        DriveSync.State.Connecting -> "Finish signing in with Google…"
                        DriveSync.State.Syncing -> "Syncing with ${email ?: "Google Drive"}…"
                        DriveSync.State.Offline -> "You're offline. Changes will sync when you're back online."
                        is DriveSync.State.Failed -> s.message
                        DriveSync.State.Idle -> buildString {
                            append(email ?: "Google Drive")
                            lastSynced?.let { append("\nLast synced ${DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}") }
                        }
                    }
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = if (state is DriveSync.State.Failed) Pinch.colors.cherry else Pinch.colors.inkSoft)
                }
            }
            if (!connected) {
                PrimaryButton(
                    if (state == DriveSync.State.Connecting) "Connecting…" else "Connect Google Drive",
                    enabled = state != DriveSync.State.Connecting,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            drive.beginConnect()?.let { consent.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
                        }
                    },
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state == DriveSync.State.Syncing) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Pinch.colors.jadeDeep, strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Syncing…", style = MaterialTheme.typography.labelMedium, color = Pinch.colors.ink)
                    } else {
                        SoftButton("Sync now", icon = Icons.Rounded.Sync, onClick = { scope.launch { drive.sync() } })
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { confirmDisconnect = true }) { Text("Disconnect", color = Pinch.colors.cherry) }
                }
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            containerColor = Pinch.colors.surface,
            title = { Text("Disconnect Google Drive?") },
            text = { Text("Your recipes stay on this phone and in your Drive. New changes just stop syncing.") },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; drive.disconnect() }) { Text("Disconnect", color = Pinch.colors.cherry) } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
}

/** Where the person adds their own OpenRouter key so the AI can read their recipes. */
@Composable
private fun AIReadingCard(account: AIAccount) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val apiKey by account.apiKey.collectAsState()
    val scope = rememberCoroutineScope()
    var keyText by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<OpenRouterClient.KeyInfo?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(apiKey) {
        val key = apiKey ?: return@LaunchedEffect
        try {
            info = OpenRouterClient.checkKey(key)
        } catch (e: OpenRouterFailure.InvalidKey) {
            message = "OpenRouter no longer accepts this key. Remove it and add a new one."
            messageIsError = true
        } catch (e: OpenRouterFailure) {
            // Offline: nothing to show.
        }
    }

    fun save() {
        val key = AIAccount.cleaned(keyText)
        if (key.isEmpty() || checking) return
        checking = true
        message = null
        scope.launch {
            fun store(checked: OpenRouterClient.KeyInfo?) {
                if (!account.save(key)) {
                    message = "Pinch couldn't store the key securely on this phone. Try again."
                    messageIsError = true
                    return
                }
                keyText = ""
                info = checked
                message = if (checked != null) "Key saved. New recipes will be read with AI."
                else "Key saved. Pinch couldn't check it with OpenRouter just now, and will try when you next add a recipe."
                messageIsError = false
            }
            try {
                store(OpenRouterClient.checkKey(key))
            } catch (e: OpenRouterFailure.InvalidKey) {
                message = "OpenRouter didn't recognize that key. Copy it again from openrouter.ai/keys."
                messageIsError = true
            } catch (e: OpenRouterFailure) {
                // Offline or OpenRouter hiccup: keep the key and check it later.
                store(null)
            } finally {
                checking = false
            }
        }
    }

    Card {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(50.dp).clip(RoundedCornerShape(14.dp)).background(Pinch.colors.butter.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Pinch.colors.ink)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(if (apiKey != null) "AI reading is on" else "Read recipes with AI", style = MaterialTheme.typography.titleMedium, color = Pinch.colors.ink)
                    Text(
                        if (apiKey != null) "Photos, pasted text and web pages are read by ${AIConfig.MODEL_DISPLAY_NAME} through OpenRouter."
                        else "Add your own OpenRouter key and ${AIConfig.MODEL_DISPLAY_NAME} reads your photos and web pages. It's far more accurate than reading on the phone, and costs well under a cent a recipe.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Pinch.colors.inkSoft,
                    )
                }
            }
            val key = apiKey
            if (key != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Key, null, tint = Pinch.colors.ink, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        AIAccount.masked(key),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                        color = Pinch.colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { confirmRemove = true }) { Text("Remove key", color = Pinch.colors.cherry) }
                }
                info?.let {
                    Text(usageText(it), style = MaterialTheme.typography.bodySmall, color = Pinch.colors.inkSoft)
                }
            } else {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pinch.colors.sunken).padding(horizontal = 14.dp, vertical = 14.dp),
                ) {
                    if (keyText.isEmpty()) Text("Paste your OpenRouter key", style = MaterialTheme.typography.bodyLarge, color = Pinch.colors.inkSoft.copy(alpha = 0.7f))
                    BasicTextField(
                        value = keyText,
                        onValueChange = { keyText = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Pinch.colors.ink),
                        cursorBrush = SolidColor(Pinch.colors.jadeDeep),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SoftButton("Paste", icon = Icons.Rounded.ContentPaste, onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { keyText = AIAccount.cleaned(it) }
                    })
                    Spacer(Modifier.width(10.dp))
                    PrimaryButton(
                        if (checking) "Checking…" else "Save key",
                        onClick = ::save,
                        enabled = AIAccount.cleaned(keyText).isNotEmpty() && !checking,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { runCatching { uriHandler.openUri(AIConfig.KEYS_PAGE) } }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = Pinch.colors.jadeDeep, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Get a key at openrouter.ai", style = MaterialTheme.typography.labelLarge, color = Pinch.colors.jadeDeep)
                }
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = if (messageIsError) Pinch.colors.cherry else Pinch.colors.jadeDeep)
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = Pinch.colors.surface,
            title = { Text("Remove your OpenRouter key?") },
            text = { Text("New recipes will be read on this phone instead. You can add the key again any time.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    account.remove()
                    info = null
                    message = null
                }) { Text("Remove key", color = Pinch.colors.cherry) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

private fun usageText(info: OpenRouterClient.KeyInfo): String {
    fun dollars(amount: Double) = NumberFormat.getCurrencyInstance(java.util.Locale.US).apply {
        currency = Currency.getInstance("USD")
        maximumFractionDigits = if (amount < 1) 3 else 2
        minimumFractionDigits = if (amount < 1) 3 else 2
    }.format(amount)
    var text = "Spent with this key so far: ${dollars(info.usage)}"
    info.limitRemaining?.let { text += " · ${dollars(it)} left on its limit" }
    return text
}
