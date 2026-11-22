package com.example.pinch.ui.groups

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pinch.AppContainer
import com.example.pinch.model.Recipe
import com.example.pinch.sync.SharedRecipe
import com.example.pinch.ui.cook.CookModeScreen
import com.example.pinch.ui.detail.DetailScreen
import com.example.pinch.ui.components.SoftButton
import com.example.pinch.ui.theme.Pinch
import kotlinx.coroutines.CancellationException

@Composable
fun SharedRecipeScreen(container: AppContainer, groupId: String, shareId: String, onBack: () -> Unit, onOpenCopy: (String) -> Unit) {
    val groups by container.server.groups.collectAsState()
    val group = groups.firstOrNull { it.id == groupId }
    var preview by remember { mutableStateOf<Pair<SharedRecipe, Recipe>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var cooking by remember { mutableStateOf(false) }
    LaunchedEffect(groupId, shareId, retry) {
        error = null
        try {
            container.server.loadGroups()
            val current = container.server.groups.value.firstOrNull { it.id == groupId } ?: error("You no longer have access to this cookbook.")
            preview = container.server.preview(shareId, current)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
    val loaded = preview
    DisposableEffect(loaded) { onDispose { loaded?.second?.photoFiles?.forEach(container.photos::delete) } }
    if (loaded != null && group != null) {
        if (cooking) {
            BackHandler { cooking = false }
            CookModeScreen(container, loaded.second.id, onClose = { cooking = false }, previewRecipe = loaded.second)
        } else DetailScreen(container, loaded.second.id, onBack, onCook = { cooking = true }, onEdit = {}, previewRecipe = loaded.second, sharedItem = loaded.first, sharedGroup = group, onOpenCopy = onOpenCopy)
    } else Column(Modifier.fillMaxSize().background(Pinch.colors.canvas), horizontalAlignment = Alignment.CenterHorizontally) {
        CookbookHeader("Shared recipe", onBack)
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (error != null) { Text(error.orEmpty(), color = Pinch.colors.inkSoft); SoftButton("Try again", { retry++ }) }
            else { CircularProgressIndicator(color = Pinch.colors.jadeDeep); Text("Opening recipe…", color = Pinch.colors.inkSoft) }
        }
    }
}
