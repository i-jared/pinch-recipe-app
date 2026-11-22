package com.example.pinch.ui.groups

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.pinch.AppContainer
import com.example.pinch.model.Recipe
import com.example.pinch.sync.*
import com.example.pinch.ui.components.PrimaryButton
import com.example.pinch.ui.components.SoftButton
import com.example.pinch.ui.settings.ServerSettingsScreen
import com.example.pinch.ui.theme.Pinch

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun GroupSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Pinch.colors.canvas) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.95f).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Pinch.colors.ink, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close", tint = Pinch.colors.ink) }
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable internal fun CreateGroupSheet(container: AppContainer, onDismiss: () -> Unit, onCreated: (ServerGroup) -> Unit) {
    var name by remember { mutableStateOf("") }
    val work = rememberGroupWork()
    GroupSheet("New cookbook", { if (!work.busy) onDismiss() }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Give your cookbook a name", style = MaterialTheme.typography.headlineSmall, color = Pinch.colors.ink)
            Text("Family favorites, Sunday dinner, or something entirely your own.", color = Pinch.colors.inkSoft)
            OutlinedTextField(name, { name = it }, label = { Text("Cookbook name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            PrimaryButton("Create cookbook", { work.run { onCreated(container.server.createGroup(name)) } }, Modifier.fillMaxWidth(), enabled = !work.busy && name.isNotBlank() && name.length <= 80)
            Feedback(work)
        }
    }
}

@Composable
fun AddToCookbooksSheet(container: AppContainer, recipe: Recipe, onDismiss: () -> Unit, onAdded: (String) -> Unit = {}) {
    val groups = cookbookList(container).filter { it.canEdit }
    val work = rememberGroupWork()
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var creating by remember { mutableStateOf(false) }
    GroupSheet("Add to cookbook", { if (!work.busy) onDismiss() }) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) { Text(recipe.emoji, style = MaterialTheme.typography.headlineLarge); Text(recipe.displayTitle, style = MaterialTheme.typography.titleLarge, color = Pinch.colors.ink) } }
            items(groups, key = { it.id }) { group ->
                val already = recipe.id in group.mySharedRecipeIds
                Row(Modifier.fillMaxWidth().clickable(enabled = !work.busy && !already) { selected = if (group.id in selected) selected - group.id else selected + group.id }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CollectionsBookmark, null, tint = Pinch.colors.jadeDeep)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(group.name, style = MaterialTheme.typography.titleMedium, color = Pinch.colors.ink)
                        Text(if (already) "Already in this cookbook" else if (group.isOwner) (if (group.memberCount > 1) "Shared by you" else "Private") else "Adds to ${group.ownerName}’s library", color = Pinch.colors.inkSoft, style = MaterialTheme.typography.bodySmall)
                    }
                    Checkbox(already || group.id in selected, onCheckedChange = null)
                }
            }
            item { TextButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, null); Text("New cookbook") } }
            item { Text("A recipe can belong to more than one cookbook.", color = Pinch.colors.inkSoft, style = MaterialTheme.typography.bodySmall) }
            item { Feedback(work) }
        }
        PrimaryButton(if (selected.isEmpty()) "Choose a cookbook" else "Add to cookbook${if (selected.size > 1) "s" else ""}", {
            work.run {
                val names = mutableListOf<String>()
                for (group in groups.filter { it.id in selected }) { container.server.share(recipe, group); names += group.name; selected = selected - group.id }
                onAdded("Added to ${names.joinToString(", ")}"); onDismiss()
            }
        }, Modifier.fillMaxWidth().padding(vertical = 16.dp), enabled = !work.busy && selected.isNotEmpty())
    }
    if (creating) CreateGroupSheet(container, { creating = false }) { selected = selected + it.id; creating = false }
}

@Composable internal fun AddGroupRecipesSheet(container: AppContainer, group: ServerGroup, shared: List<SharedRecipe>, onDismiss: () -> Unit) {
    val recipes by container.repository.recipes.collectAsState()
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var completed by remember { mutableStateOf<Set<String>>(emptySet()) }
    val work = rememberGroupWork()
    GroupSheet("Add recipes", { if (!work.busy) onDismiss() }) {
        CookbookSearch(query, { query = it })
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (recipes.isEmpty()) item { Text("Add a recipe to your library, then put it in this cookbook.", color = Pinch.colors.inkSoft, modifier = Modifier.padding(vertical = 24.dp)) }
            items(recipes.filter { query.isBlank() || it.displayTitle.contains(query, true) }, key = { it.id }) { recipe ->
                val already = recipe.id in completed || recipe.id in group.mySharedRecipeIds
                Row(Modifier.fillMaxWidth().clickable(enabled = !work.busy && !already) { selected = if (recipe.id in selected) selected - recipe.id else selected + recipe.id }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(recipe.emoji, style = MaterialTheme.typography.headlineMedium)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(recipe.displayTitle, style = MaterialTheme.typography.titleMedium, color = Pinch.colors.ink)
                        if (already) Text("Already in this cookbook", color = Pinch.colors.inkSoft, style = MaterialTheme.typography.bodySmall)
                    }
                    Checkbox(already || recipe.id in selected, onCheckedChange = null)
                }
            }
            item { Feedback(work) }
        }
        PrimaryButton("Add ${selected.size} recipe${if (selected.size == 1) "" else "s"}", {
            work.run { for (recipe in recipes.filter { it.id in selected }) { container.server.share(recipe, group); completed = completed + recipe.id; selected = selected - recipe.id }; container.server.loadGroups(); onDismiss() }
        }, Modifier.fillMaxWidth().padding(vertical = 16.dp), enabled = !work.busy && selected.isNotEmpty())
    }
}

@Composable internal fun InviteGroupSheet(container: AppContainer, group: ServerGroup, onDismiss: () -> Unit) {
    val work = rememberGroupWork()
    var role by remember { mutableStateOf("viewer") }
    var link by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(group.id, role) { link = null; work.run { link = container.server.invitationLink(group, role) } }
    GroupSheet("Share this cookbook", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            GroupCover(group)
            Text("Send a link to ${group.name}. They can join with an existing account or create one from the invitation.", color = Pinch.colors.inkSoft, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(role == "viewer", { role = "viewer" }, enabled = !work.busy, label = { Text("Viewer") })
                FilterChip(role == "editor", { role = "editor" }, enabled = !work.busy, label = { Text("Editor") })
            }
            Text(if (role == "editor") "Editors can add and remove recipes. Their additions also appear in your library." else "Viewers can read, cook and save their own copies.", color = Pinch.colors.inkSoft)
            PrimaryButton(if (link != null) "Share invitation" else "Create invitation", {
                val url = link
                if (url != null) context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Invite to ${group.name}"))
                else work.run { link = container.server.invitationLink(group, role) }
            }, Modifier.fillMaxWidth(), enabled = !work.busy, icon = Icons.Rounded.Share)
            Feedback(work)
            Text("Anyone with this link can join. Replace invitation links in Cookbook details.", color = Pinch.colors.inkSoft, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable internal fun GroupDetailsSheet(container: AppContainer, group: ServerGroup, onDismiss: () -> Unit, onLeft: () -> Unit) {
    val groups = cookbookList(container)
    val state by container.server.state.collectAsState()
    var connecting by remember { mutableStateOf(false) }
    val current = groups.firstOrNull { it.id == group.id } ?: group
    var members by remember { mutableStateOf<List<GroupMember>>(emptyList()) }
    var name by remember { mutableStateOf(group.name) }
    var action by remember { mutableStateOf<String?>(null) }
    var person by remember { mutableStateOf<GroupMember?>(null) }
    var inviting by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val work = rememberGroupWork()
    LaunchedEffect(group.id, state.connected) { if (state.connected) { connecting = false; work.run { container.server.sync(); members = container.server.members(group) } } }
    GroupSheet("Cookbook details", { if (!work.busy) onDismiss() }) {
        if (connecting) ServerSettingsScreen(container, { connecting = false })
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (current.isOwner) item {
                OutlinedTextField(name, { name = it }, label = { Text("Cookbook name") }, modifier = Modifier.fillMaxWidth(), enabled = !work.busy, singleLine = true)
                TextButton(enabled = !work.busy && name.isNotBlank() && name != current.name && name.length <= 80, onClick = { work.run { container.server.rename(group, name); notice = "Name updated." } }) { Text("Save name") }
            }
            item { Text("Your people", style = MaterialTheme.typography.titleLarge, color = Pinch.colors.ink) }
            items(members, key = { it.id }) { member ->
                var menu by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).background(Pinch.colors.sunken, CircleShape), contentAlignment = Alignment.Center) { Text(member.username.take(1).uppercase(), color = Pinch.colors.ink, style = MaterialTheme.typography.titleMedium) }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(member.username, style = MaterialTheme.typography.titleMedium, color = Pinch.colors.ink); Text(if (member.isOwner) "Owner" else member.role.replaceFirstChar { it.uppercase() } + if (member.isYou) " · You" else "", color = Pinch.colors.inkSoft, style = MaterialTheme.typography.bodySmall) }
                    if (current.isOwner && !member.isYou) Box {
                        IconButton(onClick = { menu = true }, enabled = !work.busy) { Icon(Icons.Rounded.MoreVert, "Manage ${member.username}", tint = Pinch.colors.ink) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(if (member.role == "editor") "Change to viewer" else "Change to editor") }, onClick = { person = member; action = if (member.role == "editor") "viewer" else "editor"; menu = false })
                            DropdownMenuItem(text = { Text("Remove member") }, onClick = { person = member; action = "remove"; menu = false })
                        }
                    }
                }
            }
            if (current.isOwner) {
                item { SoftButton(if (state.connected) "Invite someone" else "Connect an account to invite people", { if (state.connected) inviting = true else connecting = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.PersonAdd) }
                if (state.connected) item { TextButton(enabled = !work.busy, onClick = { action = "rotate" }) { Text("Replace invitation links") } }
            }
            item { TextButton(enabled = !work.busy, onClick = { action = "leave" }) { Text(if (current.isOwner) "Delete cookbook" else "Leave cookbook", color = Pinch.colors.cherry) } }
            item { Feedback(work); notice?.let { Text(it, color = Pinch.colors.jadeDeep) } }
        }
    }
    if (inviting) InviteGroupSheet(container, current) { inviting = false }
    action?.let { chosen ->
        val title = when (chosen) { "remove" -> "Remove ${person?.username}?"; "editor", "viewer" -> "Give ${person?.username} $chosen access?"; "rotate" -> "Replace invitation links?"; else -> if (current.isOwner) "Delete this cookbook?" else "Leave this cookbook?" }
        val message = when (chosen) {
            "remove" -> "Their access will be removed and current invitations will expire. Recipes they contributed stay in your library and cookbook."
            "editor" -> "They will be able to add and remove recipes. Their additions appear in your library. Current invitation links will expire."
            "viewer" -> "They will be able to read and cook recipes. Current invitation links will expire."
            "rotate" -> "Existing members keep access. All previously sent invitations will stop working."
            else -> if (current.isOwner) "The cookbook will be removed for everyone. All recipes stay in personal libraries." else "You will lose access to this cookbook. Recipes you contributed and copies you saved stay in their libraries."
        }
        AlertDialog(onDismissRequest = { action = null }, title = { Text(title) }, text = { Text(message) }, confirmButton = { TextButton(onClick = {
            action = null; work.run {
                when (chosen) {
                    "remove" -> person?.let { container.server.removeMember(current, it) }
                    "editor", "viewer" -> person?.let { container.server.changeRole(current, it, chosen) }
                    "rotate" -> { container.server.invite(current); notice = "Old invitations have expired. Create a new link to invite someone." }
                    "leave" -> { container.server.leave(current); onLeft(); return@run }
                }
                members = container.server.members(current)
            }
        }) { Text("Confirm") } }, dismissButton = { TextButton(onClick = { action = null }) { Text("Cancel") } })
    }
}

@Composable
fun JoinGroupSheet(container: AppContainer, invitation: GroupInvitation? = null, onDismiss: () -> Unit) {
    val server = container.server
    val state by server.state.collectAsState()
    val work = rememberGroupWork()
    var link by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(invitation) }
    var details by remember { mutableStateOf<InvitationDetails?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(true) }
    var joined by remember { mutableStateOf(false) }
    LaunchedEffect(invitation) { if (invitation != null) { link = invitation.server + "/join#" + invitation.code; work.run { details = server.invitationDetails(invitation) } } }
    GroupSheet("Join a cookbook", { if (!work.busy) onDismiss() }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            val info = details
            if (joined) {
                Text("You’re in!", style = MaterialTheme.typography.headlineLarge, color = Pinch.colors.ink)
                Text("${info?.name ?: "Your cookbook"} is ready in Cookbooks.", color = Pinch.colors.inkSoft)
                PrimaryButton("Done", onDismiss, Modifier.fillMaxWidth())
            } else if (info != null) {
                Text("${info.invitedBy} invited you to", color = Pinch.colors.inkSoft)
                Text(info.name, style = MaterialTheme.typography.headlineLarge, color = Pinch.colors.ink)
                Text(if (info.role == "editor") "You’re invited as an editor. Additions also go into the owner’s library." else "You’re invited as a viewer. Read, cook and save your own copies.", color = Pinch.colors.inkSoft)
                if (state.connected && pending?.server != state.server.trimEnd('/')) {
                    Text("This invitation uses a different account server. Disconnect your current account in Backup & sync before joining.", color = Pinch.colors.ink)
                    Text(pending?.server.orEmpty(), color = Pinch.colors.inkSoft)
                } else {
                    if (!state.connected) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(register, { register = true }, label = { Text("Create account") }); FilterChip(!register, { register = false }, label = { Text("Sign in") }) }
                        OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it }, label = { Text("Password (at least 10 characters)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        Text("Your private recipes will back up to ${pending?.server}. People you invite see only recipes in shared cookbooks.", color = Pinch.colors.inkSoft, style = MaterialTheme.typography.bodySmall)
                    }
                    PrimaryButton("Join cookbook", { work.run {
                        val value = pending ?: return@run
                        if (!state.connected) server.connect(value.server, username, password, "", register, value.code)
                        server.joinGroup(value.code); password = ""; joined = true
                    } }, Modifier.fillMaxWidth(), enabled = !work.busy && (state.connected || (username.isNotBlank() && password.codePointCount(0, password.length) >= 10)))
                }
            } else {
                Text("Open your invitation to find your people.", style = MaterialTheme.typography.headlineSmall, color = Pinch.colors.ink)
                OutlinedTextField(link, { link = it }, label = { Text("Paste invitation link") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                PrimaryButton("Open invitation", { work.run {
                    val value = if (state.connected && !link.contains(':')) GroupInvitation(state.server, link.trim()) else GroupInvitation.parse(link)
                    pending = value; details = server.invitationDetails(value)
                } }, Modifier.fillMaxWidth(), enabled = !work.busy && link.isNotBlank())
            }
            Feedback(work)
        }
    }
}
