package com.dsh.notes.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import com.dsh.notes.ui.components.UpdateDialog
import com.dsh.notes.ui.screens.FishAiScreen
import com.dsh.notes.ui.screens.NoteDetailScreen
import com.dsh.notes.ui.screens.NotesScreen
import com.dsh.notes.ui.screens.PromptSettingsScreen
import com.dsh.notes.ui.screens.RecordingScreen
import com.dsh.notes.ui.screens.SettingsScreen
import com.dsh.notes.ui.theme.NoteCraftTheme
import com.dsh.notes.util.WebLinks

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
    val updateState by mainViewModel.updateState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            mainViewModel.clearToast()
        }
    }

    // 启动时查一次新版本；回到前台时也再查一次
    // （内部有 10 分钟限流，反复切前后台不会打光 GitHub 的配额）
    LaunchedEffect(Unit) { mainViewModel.checkForUpdate(manual = false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mainViewModel.checkForUpdate(manual = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val onToggleTheme: () -> Unit = { mainViewModel.cycleThemeMode() }

    // 更新窗挂在根上：不管当前在哪个页面，发现新版本都能弹出来
    UpdateDialog(
        state = updateState,
        onInstall = { mainViewModel.installUpdate() },
        onLater = { mainViewModel.postponeUpdate() },
        onSkipVersion = { mainViewModel.skipUpdateVersion() },
        onOpenRelease = { url -> WebLinks.open(context, url) }
    )

    NoteCraftTheme(themeMode = settings.themeMode) {
      Box(modifier = Modifier.fillMaxSize()) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        // 设置页的路由是 "settings?focus={focus}"，比之前先剥掉查询串
        val showBottomBar = Destination.entries.any { it.route == currentRoute?.substringBefore('?') }

        // 玻璃配色先取出来：drawBehind 里不能调用 @Composable
        val glassTop = MaterialTheme.colorScheme.surface.copy(alpha = 0.42f)
        val glassBottom = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
        val glassEdge = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (showBottomBar) {
                    /*
                     * 液态玻璃底栏。
                     *
                     * 只把 containerColor 调半透明几乎看不出来 —— 真正让它像玻璃的是：
                     *  · 明显的上下渐变（顶部更透、底部更实）
                     *  · 一条极细的高光边
                     *  · 去掉 Material 默认的实色阴影
                     */
                    Box(
                        modifier = if (settings.experimentalGlass) {
                            Modifier
                                .fillMaxWidth()
                                .drawBehind {
                                    drawRect(
                                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                            listOf(glassTop, glassBottom)
                                        )
                                    )
                                    // 顶边高光
                                    val stroke = 1.dp.toPx()
                                    drawLine(
                                        color = glassEdge,
                                        start = androidx.compose.ui.geometry.Offset(0f, stroke / 2),
                                        end = androidx.compose.ui.geometry.Offset(size.width, stroke / 2),
                                        strokeWidth = stroke
                                    )
                                }
                        } else {
                            Modifier
                        }
                    ) {
                        NavigationBar(
                            containerColor = if (settings.experimentalGlass) {
                                androidx.compose.ui.graphics.Color.Transparent
                            } else {
                                NavigationBarDefaults.containerColor
                            },
                            tonalElevation = if (settings.experimentalGlass) 0.dp else 3.dp
                        ) {
                        Destination.entries.forEach { destination ->
                            // 设置页带 focus 参数，destination.route 里会多一段查询串，比之前先剥掉
                            val selected = backStackEntry?.destination?.hierarchy
                                ?.any { it.route?.substringBefore('?') == destination.route } == true
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
            }
        ) { innerPadding ->
            // 实验性：在三个主 tab 之间横滑切换。
            //
            // 不能在主通道里用 detectHorizontalDragGestures：页面里的竖向滚动
            // （LazyColumn / verticalScroll）会把指针事件消费掉，父级永远收不到。
            // 这里在 Initial 阶段旁路观察位移：只有横向明显占优才接管，
            // 竖向拖动直接放行，滚动和点击都不受影响。
            val topLevelIndex = Destination.entries
                .indexOfFirst { it.route == currentRoute?.substringBefore('?') }

            fun switchTab(delta: Int) {
                val target = topLevelIndex + delta
                Destination.entries.getOrNull(target)?.let { destination ->
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }

            val swipeModifier = if (settings.experimentalSwipeTabs &&
                showBottomBar &&
                topLevelIndex >= 0
            ) {
                Modifier.pointerInput(topLevelIndex) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var totalX = 0f
                        var totalY = 0f
                        var decided = false
                        var horizontal = false

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            totalX += change.positionChange().x
                            totalY += change.positionChange().y

                            if (!decided && (abs(totalX) > 28f || abs(totalY) > 28f)) {
                                decided = true
                                horizontal = abs(totalX) > abs(totalY) * 1.4f
                                if (!horizontal) return@awaitEachGesture
                            }

                            if (horizontal && abs(totalX) > 150f) {
                                switchTab(if (totalX < 0) 1 else -1)
                                return@awaitEachGesture
                            }

                            if (!change.pressed) break
                        }
                    }
                }
            } else {
                Modifier
            }

            Box(modifier = Modifier.fillMaxSize().then(swipeModifier)) {
            NavHost(
                navController = navController,
                startDestination = Destination.RECORD.route,
                modifier = Modifier.padding(innerPadding),
                enterTransition = {
                    if (settings.experimentalMotion) fadeIn(tween(260)) + slideInHorizontally(tween(260)) { it / 6 } else EnterTransition.None
                },
                exitTransition = {
                    if (settings.experimentalMotion) fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { -it / 8 } else ExitTransition.None
                },
                popEnterTransition = {
                    if (settings.experimentalMotion) fadeIn(tween(260)) + slideInHorizontally(tween(260)) { it / 6 } else EnterTransition.None
                },
                popExitTransition = {
                    if (settings.experimentalMotion) fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { -it / 8 } else ExitTransition.None
                }
            ) {
                composable(Destination.RECORD.route) {
                    RecordingScreen(
                        viewModelFactory = viewModelFactory,
                        themeMode = settings.themeMode,
                        onToggleTheme = onToggleTheme,
                        onOpenSettings = { problems ->
                            val first = problems.first()
                            mainViewModel.notify(
                                if (problems.size == 1) {
                                    first.message
                                } else {
                                    "${first.message}（一共 ${problems.size} 项待补）"
                                }
                            )
                            navController.navigate("${Destination.SETTINGS.route}?focus=${first.area.routeKey}")
                        },
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
                        onOpenFish = { mainViewModel.openFish() },
                        sharedAudioUris = sharedAudioUris,
                        onSharedAudioConsumed = onSharedAudioConsumed
                    )
                }

                // 设置页带一个 focus 参数：录音前被拦下时会带上出问题的那一块，
                // 进去直接滑过去。平时从底部导航进就是空串，行为不变。
                composable(
                    route = "${Destination.SETTINGS.route}?focus={focus}",
                    arguments = listOf(
                        navArgument("focus") {
                            type = NavType.StringType
                            defaultValue = ""
                        }
                    )
                ) { entry ->
                    SettingsScreen(
                        viewModelFactory = viewModelFactory,
                        themeMode = settings.themeMode,
                        onToggleTheme = onToggleTheme,
                        focusArea = entry.arguments?.getString("focus").orEmpty(),
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
                        onBack = { navController.popBackStack() },
                        onOpenFishFull = { mainViewModel.openFish() }
                    )
                }
            }
            }
        }

        /*
         * 大肥鱼覆盖层。
         *
         * 刻意不放进 NavHost：它只能从笔记页的机器人球进入，不是一个平级页面。
         * 盖在 Scaffold 外面，连底部导航一起挡住，视觉上才是真正的整页。
         * 进场从右下角（球的位置）扩散到全屏。
         */
        val fishOpen by mainViewModel.fishOpen.collectAsStateWithLifecycle()
        val fishViewModel: FishAiViewModel = viewModel(factory = viewModelFactory)
        BackHandler(enabled = fishOpen) { mainViewModel.closeFish() }

        AnimatedVisibility(
            visible = fishOpen,
            enter = scaleIn(
                animationSpec = tween(260),
                initialScale = 0.12f,
                transformOrigin = TransformOrigin(0.84f, 0.86f)
            ) + fadeIn(tween(180)),
            exit = scaleOut(
                animationSpec = tween(200),
                targetScale = 0.12f,
                transformOrigin = TransformOrigin(0.84f, 0.86f)
            ) + fadeOut(tween(140))
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                FishAiScreen(
                    viewModel = fishViewModel,
                    onClose = { mainViewModel.closeFish() }
                )
            }
        }
      }
    }
}
