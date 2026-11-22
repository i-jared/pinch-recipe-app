package com.example.pinch.ui.settings

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AlarmOn
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import com.example.pinch.model.SampleRecipes
import com.example.pinch.ui.components.PrimaryButton
import com.example.pinch.ui.components.SoftButton
import com.example.pinch.ui.theme.Pinch
import kotlinx.coroutines.launch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf("home") }
    val state by container.server.state.collectAsState()
    val back = { page = "home" }
    BackHandler(page != "home", onBack = back)
    when (page) {
        "account" -> ServerSettingsScreen(container, back)
        "timers" -> TimerSettingsScreen(back)
        "about" -> SettingsPage("About & privacy", back) {
            Card { Text("Your recipes live on this device and work offline. Connecting a server backs up your library to that server. Its host can access your backup. People you invite see only recipes in shared cookbooks.", color = Pinch.colors.ink) }
            Card { Text("Pinch sends recipe imports, ingredients and directions to OpenRouter when it reads recipes or matches ingredients to cooking steps.", color = Pinch.colors.ink) }
            Text("Version ${BuildConfig.VERSION_NAME}", color = Pinch.colors.inkSoft)
        }
        else -> {
            val recipes by container.repository.recipes.collectAsState()
            var samples by remember { mutableStateOf(false) }
            SettingsPage("Settings", onBack) {
                val selfHosted = state.connected && !state.managed
                SettingsRow(if (selfHosted) "Self-hosting" else "Account", if (state.connected) state.username else "Sign in to Pinch", Icons.Rounded.CloudUpload) {
                    page = "account"
                }
                SettingsRow("Kitchen timers", "Background alerts", Icons.Rounded.AlarmOn) { page = "timers" }
                Label("Library")
                SettingsRow("Add sample recipes", "${recipes.size} recipes on this device", Icons.Rounded.AutoAwesome) { samples = true }
                SettingsRow("About & privacy", "Pinch ${BuildConfig.VERSION_NAME}", Icons.Rounded.Lock) { page = "about" }
            }
            if (samples) AlertDialog(
                onDismissRequest = { samples = false }, title = { Text("Add three sample recipes?") },
                confirmButton = { TextButton(onClick = { container.repository.saveAll(SampleRecipes.all()); samples = false }) { Text("Add recipes") } },
                dismissButton = { TextButton(onClick = { samples = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
internal fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Pinch.colors.canvas)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(Pinch.colors.sunken)
                .clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = "Back" }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Pinch.colors.ink)
            }
            Text(title, style = MaterialTheme.typography.titleLarge, color = Pinch.colors.ink, modifier = Modifier.padding(start = 14.dp).weight(1f))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun SettingsRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Pinch.colors.surface)
        .clickable(role = Role.Button, onClick = onClick).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Pinch.colors.jadeDeep)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Pinch.colors.ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Pinch.colors.inkSoft)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Pinch.colors.inkSoft)
    }
}

@Composable
private fun TimerSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        onPauseOrDispose { }
    }
    SettingsPage("Kitchen timers", onBack) {
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (allowed) "Timers ring on time" else "Allow on-time alerts", style = MaterialTheme.typography.titleMedium, color = Pinch.colors.ink)
                Text("Allow background alarms so a notification rings when your timer ends.", color = Pinch.colors.inkSoft)
                if (!allowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SoftButton("Allow alarms", onClick = {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                })
            }
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

/** Where the person adds their own OpenRouter key so the AI can read their recipes. */
@Composable
internal fun AIReadingCard(account: AIAccount) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val apiKey by account.apiKey.collectAsState()
    val model by account.model.collectAsState()
    val modelName = model.trim().let { if (it.isEmpty() || it == AIConfig.MODEL) AIConfig.MODEL_DISPLAY_NAME else it }
    val scope = rememberCoroutineScope()
    var keyText by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<OpenRouterClient.KeyInfo?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var askConsent by remember { mutableStateOf(false) }

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
                        if (apiKey != null) "Photos, pasted text and web pages are read by $modelName through OpenRouter."
                        else "Add your own OpenRouter key to enable AI recipe reading.",
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
                val cookModeEnabled by account.cookModeEnabled.collectAsState()
                androidx.compose.material3.OutlinedTextField(model, account::setModel, label = { Text("OpenRouter model ID") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                Row(
                    Modifier.fillMaxWidth().toggleable(cookModeEnabled, role = Role.Switch, onValueChange = account::setCookModeEnabled),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Match Cook Mode ingredients with AI", style = MaterialTheme.typography.titleSmall, color = Pinch.colors.ink)
                        Text(AIConfig.COOK_MODE_DISCLOSURE, style = MaterialTheme.typography.bodySmall, color = Pinch.colors.inkSoft)
                    }
                    Switch(checked = cookModeEnabled, onCheckedChange = null)
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
                        keyboardActions = KeyboardActions(onDone = { if (AIAccount.cleaned(keyText).isNotEmpty()) askConsent = true }),
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
                        if (checking) "Checking…" else "Turn on AI reading",
                        // Nothing is shared with the AI until the person agrees to it.
                        onClick = { askConsent = true },
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

    if (askConsent) {
        AlertDialog(
            onDismissRequest = { askConsent = false },
            containerColor = Pinch.colors.surface,
            title = { Text("Send recipes to OpenRouter?") },
            text = { Text(AIConfig.CONSENT_TEXT) },
            confirmButton = {
                TextButton(onClick = {
                    askConsent = false
                    save()
                }) { Text("Turn on", color = Pinch.colors.jadeDeep) }
            },
            dismissButton = { TextButton(onClick = { askConsent = false }) { Text("Cancel") } },
        )
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
