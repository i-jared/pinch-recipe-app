package com.example.pinch.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.pinch.AppContainer
import com.example.pinch.ui.capture.CaptureScreen
import com.example.pinch.ui.cook.CookModeScreen
import com.example.pinch.ui.detail.DetailScreen
import com.example.pinch.ui.editor.EditScreen
import com.example.pinch.ui.library.LibraryScreen
import com.example.pinch.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

@Serializable object LibraryRoute
@Serializable data class RecipeRoute(val id: String)
@Serializable data class CookRoute(val id: String)
@Serializable data class EditRoute(val id: String)
@Serializable object CaptureRoute
@Serializable object SettingsRoute

/** Shared-element scopes for the tin → hero transition. */
@OptIn(ExperimentalSharedTransitionApi::class)
class TransitionScopes(val shared: SharedTransitionScope, val animated: AnimatedVisibilityScope)

val LocalTransitionScopes = compositionLocalOf<TransitionScopes?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PinchNavHost(container: AppContainer, openCapture: MutableStateFlow<Boolean>) {
    val nav = rememberNavController()
    val shouldOpenCapture by openCapture.collectAsState()
    LaunchedEffect(shouldOpenCapture) {
        if (shouldOpenCapture) {
            openCapture.value = false
            nav.navigate(CaptureRoute) { launchSingleTop = true }
        }
    }

    SharedTransitionLayout {
        NavHost(
            navController = nav,
            startDestination = LibraryRoute,
            enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 6 } },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(260)) { it / 6 } },
        ) {
            composable<LibraryRoute> {
                CompositionLocalProvider(LocalTransitionScopes provides TransitionScopes(this@SharedTransitionLayout, this)) {
                    LibraryScreen(
                        container = container,
                        onOpenRecipe = { nav.navigate(RecipeRoute(it)) },
                        onOpenSettings = { nav.navigate(SettingsRoute) },
                        onOpenCapture = { nav.navigate(CaptureRoute) { launchSingleTop = true } },
                    )
                }
            }
            composable<RecipeRoute> { entry ->
                val id = entry.toRoute<RecipeRoute>().id
                CompositionLocalProvider(LocalTransitionScopes provides TransitionScopes(this@SharedTransitionLayout, this)) {
                    DetailScreen(
                        container = container,
                        recipeId = id,
                        onBack = { nav.popBackStack() },
                        onCook = { nav.navigate(CookRoute(id)) },
                        onEdit = { nav.navigate(EditRoute(id)) },
                    )
                }
            }
            composable<CookRoute> { entry ->
                CookModeScreen(container, entry.toRoute<CookRoute>().id, onClose = { nav.popBackStack() })
            }
            composable<EditRoute> { entry ->
                EditScreen(container, entry.toRoute<EditRoute>().id, onDone = { nav.popBackStack() })
            }
            composable<CaptureRoute> {
                CaptureScreen(
                    container = container,
                    onDone = { savedId ->
                        nav.popBackStack(LibraryRoute, inclusive = false)
                        if (savedId != null) nav.navigate(RecipeRoute(savedId))
                    },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(container, onBack = { nav.popBackStack() })
            }
        }
    }
}
