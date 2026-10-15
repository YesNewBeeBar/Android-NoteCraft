package com.dsh.notes.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dsh.notes.ui.screens.NoteDetailScreen
import com.dsh.notes.ui.screens.NotesScreen
import com.dsh.notes.ui.screens.PromptSettingsScreen
import com.dsh.notes.ui.screens.RecordingScreen
import com.dsh.notes.ui.screens.SettingsScreen
import com.dsh.notes.ui.theme.NoteCraftTheme

/** 底部导航的三个入口 */
/** 提示词页不在底部导航里，从设置页右上角进入 */
private const val ROUTE_PROMPTS = "prompts"

private enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    RECORD("record", "录音", Icons.Default.Mic),
    NOTES("notes", "笔记", Icons.Default.Notes),
    SETTINGS("settings", "设置", Icons.Default.Settings)
}

@Composable
fun NoteCraftApp(
    viewModelFactory: NoteCraftViewModelFactory,
    sharedAudioUris: List<Uri> = emptyList(),
    onSharedAudioConsumed: () -> Unit = {}
) {
    // 主题模式是全局状态，所以放在这层读一次，再发给各个页面
    val mainViewModel: MainViewModel = viewModel(factory = viewModelFactory)
    val settings by mainViewModel.settings.collectAsStateWithLifecycle()
    val toast by mainViewModel.toast.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            mainViewModel.clearToast()
        }
    }

    val onToggleTheme: () -> Unit = { mainViewModel.cycleThemeMode() }

    NoteCraftTheme(themeMode = settings.themeMode) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val showBottomBar = Destination.entries.any { it.route == currentRoute }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        Destination.entries.forEach { destination ->
                            val selected = backStackEntry?.destination?.hierarchy
                                ?.any { it.route == destination.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(destination.icon, contentDescription = destination.label)
                                },
                                label = { Text(destination.label) }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Destination.RECORD.route,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(Destination.RECORD.route) {
                    RecordingScreen(
                        viewModelFactory = viewModelFactory,
                        themeMode = settings.themeMode,
                        onToggleTheme = onToggleTheme,
                        onOpenNote = { noteId ->
                            navController.navigate("note/$noteId")
                        }
                    )
                }

                composable(Destination.NOTES.route) {
                    NotesScreen(
                        viewModelFactory = viewModelFactory,
                        themeMode = settings.themeMode,
                        onToggleTheme = onToggleTheme,
                        onOpenNote = { noteId -> navController.navigate("note/$noteId") },
                        sharedAudioUris = sharedAudioUris,
                        onSharedAudioConsumed = onSharedAudioConsumed
                    )
                }

                composable(Destination.SETTINGS.route) {
                    SettingsScreen(
                        viewModelFactory = viewModelFactory,
                        themeMode = settings.themeMode,
                        onToggleTheme = onToggleTheme,
                        onOpenPrompts = { navController.navigate(ROUTE_PROMPTS) }
                    )
                }

                composable(ROUTE_PROMPTS) {
                    PromptSettingsScreen(
                        viewModelFactory = viewModelFactory,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(
                    route = "note/{noteId}",
                    arguments = listOf(navArgument("noteId") { type = NavType.LongType })
                ) { entry ->
                    val noteId = entry.arguments?.getLong("noteId") ?: return@composable
                    NoteDetailScreen(
                        noteId = noteId,
                        viewModelFactory = viewModelFactory,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
