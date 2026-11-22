package com.example.pinch.ui.groups

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.pinch.AppContainer
import com.example.pinch.model.*
import com.example.pinch.sync.*
import com.example.pinch.ui.components.*
import com.example.pinch.ui.library.RecipeTin
import com.example.pinch.ui.theme.Pinch
import kotlinx.coroutines.*

@Stable
internal class GroupWork(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { action() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Couldn’t reach your cookbook. Try again." }
            finally { busy = false }
        }
    }
}
@Composable internal fun rememberGroupWork(): GroupWork {
    val scope = rememberCoroutineScope()
    return remember(scope) { GroupWork(scope) }
}
@Composable internal fun Feedback(work: GroupWork) {
    if (work.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Pinch.colors.jadeDeep)
    work.error?.let { Text(it, color = Pinch.colors.cherry, style = MaterialTheme.typography.bodyMedium) }
}
@Composable internal fun CookbookHeader(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Pinch.colors.ink) }
        Text(title, style = if (onBack == null) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge,
            color = Pinch.colors.ink, modifier = Modifier.weight(1f).padding(start = if (onBack == null) 4.dp else 8.dp))
        actions()
    }
}
@Composable internal fun CookbookSearch(query: String, onChange: (String) -> Unit, hint: String = "Search recipes and ingredients") {
    OutlinedTextField(query, onChange, placeholder = { Text(hint) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), shape = CircleShape,
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "Clear search") } },
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Pinch.colors.sunken, focusedContainerColor = Pinch.colors.sunken,
            unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent, focusedBorderColor = Pinch.colors.jadeDeep))
}
@Composable internal fun GroupCover(group: ServerGroup) {
    Row(Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { index ->
            val recipe = group.previews.getOrNull(index)
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                CoverArt(recipe?.palette ?: listOf(CoverPalette.JADE, CoverPalette.BUTTER, CoverPalette.TOMATO)[index], recipe?.pattern ?: CoverPattern.GINGHAM, Modifier.fillMaxSize())
                Text(recipe?.emoji ?: listOf("🥣", "🍋", "🥕")[index], style = MaterialTheme.typography.headlineLarge)
            }
        }
    }
}

@Composable
internal fun cookbookList(container: AppContainer): List<ServerGroup> {
    val remote by container.server.groups.collectAsState()
    val local by container.repository.cookbooks.collectAsState()
    val recipes by container.repository.recipes.collectAsState()
    val account by container.server.state.collectAsState()
    return remember(remote, local, recipes, account.username) { container.server.allCookbooks() }
}

@Composable
fun GroupsScreen(container: AppContainer, onOpen: (ServerGroup) -> Unit, onConnect: () -> Unit) {
    val groups = cookbookList(container)
    val work = rememberGroupWork()
    var creating by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { if (container.server.state.value.connected) work.run { container.server.loadGroups() } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your cookbooks", style = MaterialTheme.typography.headlineSmall, color = Pinch.colors.ink, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.Add, "Create or join a cookbook", tint = Pinch.colors.ink) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("New cookbook") }, onClick = { menu = false; creating = true })
                    DropdownMenuItem(text = { Text("Join a cookbook") }, onClick = { menu = false; joining = true })
                }
            }
        }
        if (groups.isEmpty()) {
            EmptyCookbooks("Make room for your favorites", "Organize recipes into cookbooks. Keep them private or invite people to view or contribute.")
            PrimaryButton("Create a cookbook", { creating = true }, Modifier.fillMaxWidth())
            SoftButton("Join an invitation", { joining = true }, Modifier.fillMaxWidth())
        } else CookbookSearch(query, { query = it }, "Find a cookbook")
        groups.filter { query.isBlank() || it.name.contains(query, true) }.forEach { group ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Pinch.colors.surface).clickable(role = Role.Button) { onOpen(group) }) {
                GroupCover(group)
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(group.name, style = MaterialTheme.typography.titleLarge, color = Pinch.colors.ink)
                    Text("${group.recipeCount} recipes · " + (if (group.isOwner) (if (group.memberCount > 1) "Shared by you" else "Private") else "${group.ownerName}’s cookbook"), color = Pinch.colors.inkSoft)
                    if (!group.isOwner) Text(if (group.canEdit) "Editor · You can add recipes" else "Viewer · Read and cook", color = Pinch.colors.jadeDeep, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Feedback(work)
        if (work.error != null) SoftButton("Try again", { work.run { container.server.loadGroups() } })
    }
    if (creating) CreateGroupSheet(container, { creating = false }) { creating = false; onOpen(it) }
    if (joining) JoinGroupSheet(container, onDismiss = { joining = false })
}

@Composable private fun EmptyCookbooks(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("📖", style = MaterialTheme.typography.displayLarge)
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Pinch.colors.ink)
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Pinch.colors.inkSoft)
    }
}

@Composable
fun GroupScreen(container: AppContainer, groupId: String, onBack: () -> Unit, onRecipe: (String) -> Unit, onOwnRecipe: (String) -> Unit) {
    val groups = cookbookList(container)
    val library by container.repository.recipes.collectAsState()
    val group = groups.firstOrNull { it.id == groupId }
    val work = rememberGroupWork()
    var recipes by remember(groupId) { mutableStateOf<List<SharedRecipe>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var alphabetical by remember { mutableStateOf(false) }
    var contributor by remember { mutableStateOf<String?>(null) }
    var peopleMenu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var inviting by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<SharedRecipe?>(null) }
    val context = LocalContext.current
    fun refresh() = work.run {
        container.server.loadGroups()
        val current = container.server.allCookbooks().firstOrNull { it.id == groupId }
        if (current == null) { onBack(); return@run }
        recipes = container.server.sharedRecipes(current); loaded = true
        context.getSharedPreferences("group-seen", Context.MODE_PRIVATE).edit().putLong(groupId, kotlin.math.ceil((recipes.maxOfOrNull { it.sharedAt } ?: 0.0) * 1000).toLong()).apply()
    }
    LaunchedEffect(groupId) { refresh() }
    LaunchedEffect(group?.latestActivity, group?.recipeCount, group?.role) {
        if (group != null && !group.isOwner) work.run { recipes = container.server.sharedRecipes(group); loaded = true }
    }
    if (group == null) {
        Column(Modifier.fillMaxSize().background(Pinch.colors.canvas).padding(20.dp)) { CookbookHeader("Cookbook", onBack); Feedback(work); if (!work.busy) SoftButton("Try again", ::refresh) }
        return
    }
    val available = if (group.isOwner) library.filter { it.id in group.document?.recipeIds.orEmpty() }.map { SharedRecipe(it.id, it.origin?.sharedBy ?: "You", true, it, sharedAt = (group.document?.entries?.firstOrNull { entry -> entry.recipeId == it.id }?.updatedAt?.toEpochMilli() ?: 0) / 1000.0, savedRecipeId = it.id) } else recipes
    val filtered = available.filter { item -> (contributor == null || item.sharedBy == contributor) && (query.isBlank() || (listOf(item.recipe.title, item.recipe.summary) + item.recipe.ingredients.map { it.text } + item.recipe.tags).joinToString(" ").contains(query, true)) }
    val shown = if (alphabetical) filtered.sortedBy { it.recipe.displayTitle.lowercase() } else filtered.sortedByDescending { it.sharedAt }
    Column(Modifier.fillMaxSize().background(Pinch.colors.canvas)) {
        CookbookHeader(group.name, onBack) {
            IconButton(onClick = ::refresh, enabled = !work.busy) { Icon(Icons.Rounded.Refresh, "Refresh recipes", tint = Pinch.colors.ink) }
            if (group.isOwner) IconButton(onClick = { if (container.server.state.value.connected) inviting = true else details = true }) { Icon(Icons.Rounded.PersonAdd, "Invite", tint = Pinch.colors.ink) }
        }
        LazyVerticalGrid(GridCells.Adaptive(158.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = { details = true }) { Icon(Icons.Rounded.Groups, null); Spacer(Modifier.width(8.dp)); Text("${if (group.memberCount == 1) "Only you" else "${group.memberCount} people"} · Cookbook details") }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Feedback(work) }
            if (loaded && available.isEmpty() && work.error == null) {
                item(span = { GridItemSpan(maxLineSpan) }) { Column(verticalArrangement = Arrangement.spacedBy(18.dp)) { GroupCover(group); Text("Every cookbook starts with a favorite.", style = MaterialTheme.typography.headlineSmall, color = Pinch.colors.ink); Text(if (group.isOwner) "Add a few recipes, then invite your people to bring theirs." else if (group.canEdit) "Add a favorite from your library to get things started." else "Recipes will appear here when the owner or an editor adds them.", color = Pinch.colors.inkSoft) } }
            } else if (available.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { CookbookSearch(query, { query = it }) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { alphabetical = !alphabetical }) { Icon(Icons.Rounded.Sort, null); Text(if (alphabetical) "A to Z" else "Recently added") }
                        Spacer(Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { peopleMenu = true }) { Text(contributor ?: "Everyone"); Icon(Icons.Rounded.ExpandMore, null) }
                            DropdownMenu(peopleMenu, { peopleMenu = false }) {
                                DropdownMenuItem(text = { Text("Everyone") }, onClick = { contributor = null; peopleMenu = false })
                                available.map { it.sharedBy }.distinct().sorted().forEach { person -> DropdownMenuItem(text = { Text(person) }, onClick = { contributor = person; peopleMenu = false }) }
                            }
                        }
                    }
                }
                if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("No recipes match. Try another ingredient or person.", color = Pinch.colors.inkSoft) }
            }
            items(shown, key = { it.id }) { item ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecipeTin(item.recipe, onClick = { if (group.isOwner) onOwnRecipe(item.recipe.id) else onRecipe(item.id) },
                        photos = container.photos.takeIf { group.isOwner }, onDelete = if (item.canRemove) ({ removing = item }) else null)
                    Text("Added by ${item.sharedBy}", style = MaterialTheme.typography.bodySmall, color = Pinch.colors.inkSoft)
                }
            }
        }
        if (group.canEdit) PrimaryButton("Add recipes", { adding = true }, Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp), icon = Icons.Rounded.Add)
    }
    if (adding) AddGroupRecipesSheet(container, group, recipes) { adding = false; refresh() }
    if (details) GroupDetailsSheet(container, group, onDismiss = { details = false; refresh() }, onLeft = { details = false; onBack() })
    if (inviting) InviteGroupSheet(container, group) { inviting = false }
    removing?.let { item -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove from this cookbook?") }, text = { Text("The recipe stays in your library and in any other cookbooks.") }, confirmButton = { TextButton(onClick = { removing = null; work.run { container.server.remove(item, group); recipes = container.server.sharedRecipes(group); container.server.loadGroups() } }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }) }
}
