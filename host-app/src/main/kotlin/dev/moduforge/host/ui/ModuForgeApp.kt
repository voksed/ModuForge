package dev.moduforge.host.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.StateFlow
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.moduforge.host.R
import dev.moduforge.host.consent.ConsentCoordinator
import dev.moduforge.host.consent.InputCoordinator
import dev.moduforge.host.ui.consent.InputDialog
import dev.moduforge.host.ui.theme.LocalAppearance
import dev.moduforge.host.ui.audit.AuditScreen
import dev.moduforge.host.ui.consent.ConsentDialog
import dev.moduforge.host.ui.modules.ModuleDetailScreen
import dev.moduforge.host.ui.modules.ModuleDetailViewModel
import dev.moduforge.host.ui.modules.ModulesScreen
import dev.moduforge.host.ui.settings.SettingsScreen

/** Asks for the system notification permission once a module was allowed to notify or to work in the background. */
@Composable
private fun NotificationPermission(wantsNotifications: StateFlow<Boolean>) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val active = wantsNotifications.collectAsStateWithLifecycle().value
    LaunchedEffect(active) {
        if (active) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private const val ROUTE_MODULES = "modules"
private const val ROUTE_AUDIT = "audit"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_MODULE = "module/{${ModuleDetailViewModel.ARG_MODULE_ID}}"

private data class TopLevel(val route: String, @StringRes val labelRes: Int, val icon: ImageVector)

private val topLevel = listOf(
    TopLevel(ROUTE_MODULES, R.string.nav_modules, Icons.Filled.Build),
    TopLevel(ROUTE_AUDIT, R.string.nav_audit, Icons.AutoMirrored.Filled.List),
    TopLevel(ROUTE_SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

/** Root of the host UI: navigation between screens plus the consent prompt, which overlays any screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuForgeApp(consent: ConsentCoordinator, input: InputCoordinator, wantsNotifications: StateFlow<Boolean>) {
    NotificationPermission(wantsNotifications)
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val route = navController.currentBackStackEntryAsState().value?.destination?.route
    val current = topLevel.firstOrNull { it.route == route }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(current?.labelRes ?: R.string.module_title)) },
                navigationIcon = {
                    if (current == null && route != null) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (current != null) {
                NavigationBar {
                    topLevel.forEach { item ->
                        NavigationBarItem(
                            selected = item == current,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                // Without a visible label the icon has to carry the name for screen readers.
                                Icon(
                                    item.icon,
                                    contentDescription = stringResource(item.labelRes).takeUnless { LocalAppearance.current.navigationLabels },
                                )
                            },
                            label = { Text(stringResource(item.labelRes)) },
                            alwaysShowLabel = LocalAppearance.current.navigationLabels,
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        NavHost(navController, startDestination = ROUTE_MODULES, modifier = Modifier.padding(padding)) {
            composable(ROUTE_MODULES) {
                ModulesScreen(
                    onOpenModule = { id -> navController.navigate("module/${Uri.encode(id)}") },
                    onMessage = { snackbar.showSnackbar(it) },
                )
            }
            composable(
                ROUTE_MODULE,
                arguments = listOf(navArgument(ModuleDetailViewModel.ARG_MODULE_ID) { type = NavType.StringType }),
            ) {
                ModuleDetailScreen(onGone = { navController.popBackStack(ROUTE_MODULES, inclusive = false) })
            }
            composable(ROUTE_AUDIT) { AuditScreen() }
            composable(ROUTE_SETTINGS) { SettingsScreen() }
        }
    }

    val prompt by consent.pending.collectAsStateWithLifecycle()
    prompt?.let { pending ->
        ConsentDialog(pending, onDecision = { consent.answer(pending, it) })
    }

    // A consent prompt takes precedence; the question waits underneath until it is answered.
    val question by input.pending.collectAsStateWithLifecycle()
    question?.takeIf { prompt == null }?.let { pending ->
        InputDialog(pending, onAnswer = { input.answer(pending, it) })
    }
}
