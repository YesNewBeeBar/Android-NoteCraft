package com.dsh.notes.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.dsh.notes.data.GLASS_LIQUID
import com.dsh.notes.ui.components.CaptureBackdropEffect
import com.dsh.notes.ui.components.UpdateDialog
import com.dsh.notes.ui.components.captureBackdrop
import com.dsh.notes.ui.components.liquidGlassBackground
import com.dsh.notes.ui.components.rememberBackdropState
import com.dsh.notes.ui.components.rememberLiquidGlassBrush
import com.dsh.notes.ui.screens.FishAiScreen
import com.dsh.notes.ui.screens.NoteDetailScreen
import com.dsh.notes.ui.screens.NotesScreen
import com.dsh.notes.ui.screens.PromptSettingsScreen
import com.dsh.notes.ui.screens.RecordingScreen
import com.dsh.notes.ui.screens.SettingsScreen
import com.dsh.notes.ui.theme.NoteCraftTheme
import com.dsh.notes.util.WebLinks

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
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        // 设置页的路由是 "settings?focus={focus}"，比之前先剥掉查询串
        val showBottomBar = Destination.entries.any { it.route == currentRoute?.substringBefore('?') }
        val topLevelIndex = Destination.entries.indexOfFirst { it.route == currentRoute?.substringBefore('?') }
        val fishOpen by mainViewModel.fishOpen.collectAsStateWithLifecycle()

        fun goTab(destination: Destination) {
            navController.navigate(destination.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }

        // 底栏背后的画面：先把内容录进 GraphicsLayer，再截成位图交给着色器
        val backdrop = rememberBackdropState()
        CaptureBackdropEffect(backdrop, currentRoute)

        // 实验性：在**底部导航栏**上左右滑动切换三个主 tab。
        //
        // 之前是让页面内容左右滑，结果和笔记卡片的左滑菜单打架 —— 那个必须留给卡片。
        // 现在手势只挂在底栏这一条上：手指落在录音 / 笔记 / 设置这一行时才算数，
        // 页面里的任何滑动都不受影响。
        val barSwipeModifier = if (settings.experimentalSwipeTabs && topLevelIndex >= 0) {
            Modifier.pointerInput(topLevelIndex) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var totalX = 0f
                    var totalY = 0f
                    var fired = false
                    while (!fired) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        totalX += change.positionChangeIgnoreConsumed().x
                        totalY += change.positionChangeIgnoreConsumed().y
                        if (abs(totalX) > 48.dp.toPx() && abs(totalX) > abs(totalY) * 1.2f) {
                            val target = Destination.entries.getOrNull(
                                topLevelIndex + if (totalX < 0) 1 else -1
                            )
                            if (target != null) {
                                change.consume()
                                goTab(target)
                            }
                            fired = true
                        }
                        if (!change.pressed) break
                    }
                }
            }
        } else {
            Modifier
        }

        Box(modifier = Modifier.fillMaxSize().captureBackdrop(backdrop)) {
            // 玻璃配色先取出来：drawBehind 里不能调用 @Composable
            val glassTop = MaterialTheme.colorScheme.surface.copy(alpha = 0.42f)
            val glassBottom = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
            val glassEdge = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
            val density = LocalDensity.current

            // 两种玻璃走同一个着色器，只换参数：
            //   毛玻璃 = 大模糊 + 几乎不透明，看不出背后的细节
            //   液态玻璃 = 小模糊 + 强折射 + 强色散 + 高光，能认出背后的形状
            val isLiquid = settings.glassStyle == GLASS_LIQUID
            val glassTint = MaterialTheme.colorScheme.surface.copy(
                alpha = if (isLiquid) 0.18f else 0.72f
            )

            // 底栏在快照里的位置和尺寸（像素）
            var barLeftPx by remember { mutableFloatStateOf(0f) }
            var barTopPx by remember { mutableFloatStateOf(0f) }
            var barSize by remember { mutableStateOf(Size.Zero) }
            val barCorner = 30.dp
            val barShape = remember(barCorner) { RoundedCornerShape(barCorner) }

            val glassBrush = rememberLiquidGlassBrush(
                state = backdrop,
                offsetPx = Offset(barLeftPx, barTopPx),
                sizePx = barSize,
                cornerPx = with(density) { barCorner.toPx() },
                tint = glassTint,
                blurPx = with(density) { (if (isLiquid) 6.dp else 22.dp).toPx() },
                refraction = if (isLiquid) 2.4f else 0f,
                dispersion = if (isLiquid) 1f else 0f,
                highlight = if (isLiquid) 0.95f else 0.05f
            )

            Scaffold(
                snackbarHost = {
                    SnackbarHost(
                        snackbar,
                        modifier = Modifier.padding(bottom = if (showBottomBar) 96.dp else 0.dp)
                    )
                }
            ) { innerPadding ->
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
                            onSharedAudioConsumed = onSharedAudioConsumed,
                            // 底栏浮在内容之上，按钮要抬到它上面去，否则会被盖住
                            bottomInset = if (showBottomBar) 96.dp else 0.dp
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

            /*
             * 液态玻璃底栏。
             *
             * 不再交给 Scaffold 的 bottomBar —— 那样 Scaffold 会给它预留空间，
             * 内容根本不会滚到底栏下面，玻璃也就「透」不出任何东西。
             * 这里改成浮在内容之上：页面滚到底时，文字会从玻璃后面穿过去。
             */
            if (showBottomBar) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .then(barSwipeModifier)
                        .navigationBarsPadding()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .clip(barShape)
                            .onGloballyPositioned { coords ->
                                val position = coords.positionInRoot()
                                barLeftPx = position.x
                                barTopPx = position.y
                                barSize = Size(
                                    coords.size.width.toFloat(),
                                    coords.size.height.toFloat()
                                )
                            }
                            .liquidGlassBackground(
                                brush = if (settings.experimentalGlass) glassBrush else null,
                                fallbackTop = glassTop,
                                fallbackBottom = glassBottom,
                                edge = glassEdge
                            )
                    ) {
                        NavigationBar(
                            containerColor = Color.Transparent,
                            tonalElevation = 0.dp,
                            windowInsets = WindowInsets(0, 0, 0, 0)
                        ) {
                            Destination.entries.forEach { destination ->
                                // 设置页带 focus 参数，destination.route 里会多一段查询串，比之前先剥掉
                                val selected = backStackEntry?.destination?.hierarchy
                                    ?.any { it.route?.substringBefore('?') == destination.route } == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { goTab(destination) },
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

            /*
             * 大肥鱼覆盖层。
             *
             * 刻意不放进 NavHost：它只能从笔记页的机器人球进入，不是一个平级页面。
             * 盖在 Scaffold 外面，连底部导航一起挡住，视觉上才是真正的整页。
             * 进场从右下角（球的位置）扩散到全屏。
             */
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
