package com.dsh.notes.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dsh.notes.MainActivity
import com.dsh.notes.NoteCraftApp
import com.dsh.notes.R
import com.dsh.notes.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * 悬浮球：在任何界面都能一键开始 / 暂停 / 结束录音。
 *
 * 全部原生 API：
 *  - 悬浮窗 = WindowManager + TYPE_APPLICATION_OVERLAY（要「显示在其他应用上层」权限）
 *  - 常驻   = 前台服务（否则进程被回收，球就没了）
 *  - 状态   = 直接订阅 RecordingService.state，和 App 内是同一份数据
 *
 * 没用 Compose：悬浮窗挂在 Service 上，没有 Activity 的 Lifecycle / ViewModelStore，
 * 塞 ComposeView 得自己造宿主。这里只有三个按钮，原生 View 更省事也更稳。
 */
class FloatingBallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateJob: Job? = null
    private var visibilityJob: Job? = null

    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var panel: LinearLayout? = null
    private var bubble: ImageView? = null
    private var btnPlay: ImageButton? = null
    private var btnPause: ImageButton? = null
    private var btnStop: ImageButton? = null
    private var params: WindowManager.LayoutParams? = null

    private var panelWidth = 0
    private var expanded = false

    /** 当前已加载的自定义图案的「文件修改时间」；0 表示当前是内置麦克风图标 */
    private var loadedIconStamp = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundSafely()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent
        if (command == null) {
            showBall()
            return START_STICKY
        }

        when (command.action) {
            ACTION_HIDE -> {
                stopSelf()
                return START_NOT_STICKY
            }

            // 改大小 / 透明度 / 图案后走这里。
            // 千万别用「先 stopSelf 再 start」—— stopSelf() 不带 startId 是无条件停止，
            // 会和紧随其后的 start 打架，表现就是悬浮球直接消失、要关开一次才回来。
            // 已经挂上了就原地改属性，连一帧都不会闪。
            ACTION_REFRESH -> {
                // 拖滑块时把目标值直接带过来，服务就不必等磁盘写完再读，跟手得多
                val sizeOverride = command.getIntExtra(EXTRA_SIZE, -1).takeIf { it > 0 }
                val alphaOverride = if (command.hasExtra(EXTRA_ALPHA)) {
                    command.getFloatExtra(EXTRA_ALPHA, -1f).takeIf { it >= 0f }
                } else {
                    null
                }
                scope.launch {
                    val base = runCatching {
                        (application as NoteCraftApp).container.settings.current()
                    }.getOrNull() ?: AppSettings()
                    val effective = base.copy(
                        floatingBallSizeDp = sizeOverride ?: base.floatingBallSizeDp,
                        floatingBallAlpha = alphaOverride ?: base.floatingBallAlpha
                    )
                    if (root == null) buildBall(effective) else applySettingsInPlace(effective)
                }
                return START_STICKY
            }

            else -> showBall()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeBall()
        stateJob?.cancel()
        visibilityJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 悬浮窗

    private fun showBall() {
        if (root != null) return
        if (!canDrawOverlays()) {
            // 没权限就别硬挂，设置页会引导去授权
            stopSelf()
            return
        }
        scope.launch {
            val settings = runCatching {
                (application as NoteCraftApp).container.settings.current()
            }.getOrNull()
            buildBall(settings)
        }
    }

    private fun buildBall(settings: AppSettings?) {
        if (root != null) return

        val sizePx = dp((settings?.floatingBallSizeDp ?: 56).coerceIn(40, 96))
        val alpha = (settings?.floatingBallAlpha ?: 0.9f).coerceIn(0.3f, 1f)
        val iconPath = settings?.floatingBallIconPath.orEmpty()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // [操作条][球]：展开时操作条出现在球的左边
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val actionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_bubble_panel)
            val pad = dp(6)
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
        }

        val actionSize = dp(40)
        val play = actionButton(R.drawable.ic_bubble_play, "开始或继续", actionSize) {
            if (RecordingService.state.value.phase == RecorderPhase.PAUSED) {
                RecordingService.resume(this)
            } else {
                scope.launch {
                    val rate = runCatching {
                        (application as NoteCraftApp).container.settings.current().sampleRate
                    }.getOrDefault(16000)
                    RecordingService.start(this@FloatingBallService, rate)
                }
            }
        }
        val pause = actionButton(R.drawable.ic_bubble_pause, "暂停", actionSize) {
            RecordingService.pause(this)
        }
        val stop = actionButton(R.drawable.ic_bubble_stop, "结束", actionSize) {
            RecordingService.stop(this)
        }

        listOf(play, pause, stop).forEach { button ->
            val margin = dp(4)
            actionPanel.addView(
                button,
                LinearLayout.LayoutParams(actionSize, actionSize).apply {
                    setMargins(margin, 0, margin, 0)
                }
            )
        }

        val ball = ImageView(this).apply {
            setBackgroundResource(R.drawable.bg_bubble)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "NoteCraft 悬浮球"
            setImageResource(R.drawable.ic_bubble_mic)
        }

        container.addView(
            actionPanel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            ball,
            LinearLayout.LayoutParams(sizePx, sizePx).apply { setMargins(dp(6), 0, 0, 0) }
        )

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val metrics = resources.displayMetrics
            x = (metrics.widthPixels - sizePx - dp(12)).coerceAtLeast(0)
            y = metrics.heightPixels / 3
        }

        container.alpha = alpha
        attachDrag(ball, container)

        val added = runCatching {
            windowManager?.addView(container, layoutParams)
            true
        }.getOrDefault(false)

        if (!added) {
            stopSelf()
            return
        }

        root = container
        panel = actionPanel
        bubble = ball
        params = layoutParams
        btnPlay = play
        btnPause = pause
        btnStop = stop

        // 自定义图案要等 bubble 赋值之后才能设（loadCustomIcon 读的是这个字段）
        val iconFile = if (iconPath.isNotBlank()) File(iconPath) else null
        val stamp = if (iconFile != null && iconFile.exists()) iconFile.lastModified() else 0L
        if (stamp == 0L || !loadCustomIcon(iconPath)) {
            ball.setImageResource(R.drawable.ic_bubble_mic)
            loadedIconStamp = 0L
        } else {
            loadedIconStamp = stamp
        }

        observeState()
        observeAppVisibility()
    }

    /**
     * App 在前台就把球藏起来，切出去再显示。
     *
     * 用 visibility 而不是拆掉视图：从「切出去」到「看到球」没有延迟，
     * 也不用反复调度窗口。
     */
    private fun observeAppVisibility() {
        visibilityJob?.cancel()
        visibilityJob = scope.launch {
            AppVisibility.foreground.collect { foreground ->
                val container = root ?: return@collect
                if (foreground) {
                    if (expanded) collapse()
                    container.visibility = View.GONE
                } else {
                    container.visibility = View.VISIBLE
                }
            }
        }
    }

    /** 只改属性，不重搭视图：拖滑块时球一直在，不会闪 */
    private fun applySettingsInPlace(settings: AppSettings?) {
        val container = root ?: return
        val ball = bubble ?: return

        val sizePx = dp((settings?.floatingBallSizeDp ?: 56).coerceIn(40, 96))
        (ball.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            if (lp.width != sizePx || lp.height != sizePx) {
                lp.width = sizePx
                lp.height = sizePx
                ball.layoutParams = lp
            }
        }

        container.alpha = (settings?.floatingBallAlpha ?: 0.9f).coerceIn(0.3f, 1f)

        /*
         * 图案刷新靠「文件修改时间」判断，不能靠 drawable 类型。
         * 用户重新挑一张图时，写进去的路径还是同一个文件，
         * 只拿「是不是 BitmapDrawable」判断会一直为真，于是永远不重载 —— 换图不生效。
         */
        val path = settings?.floatingBallIconPath.orEmpty()
        val file = if (path.isNotBlank()) File(path) else null
        val stamp = if (file != null && file.exists()) file.lastModified() else 0L

        if (stamp == 0L) {
            if (loadedIconStamp != 0L) {
                ball.scaleType = ImageView.ScaleType.CENTER_INSIDE
                ball.clipToOutline = false
                ball.setImageResource(R.drawable.ic_bubble_mic)
                loadedIconStamp = 0L
            }
        } else if (stamp != loadedIconStamp) {
            if (loadCustomIcon(path)) loadedIconStamp = stamp
        }

        clampInto()
        params?.let { runCatching { windowManager?.updateViewLayout(container, it) } }
    }

    private fun removeBall() {
        root?.let { view -> runCatching { windowManager?.removeView(view) } }
        root = null
        panel = null
        bubble = null
        params = null
        expanded = false
        loadedIconStamp = 0L
    }

    /** 拖动移动；轻点展开 / 收起操作条 */
    private fun attachDrag(handle: View, windowView: View) {
        val slop = dp(6).toFloat()
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        handle.setOnTouchListener { _, event ->
            val current = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = current.x
                    startY = current.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        if (expanded) collapse()
                    }
                    if (moved) {
                        current.x = startX + dx.toInt()
                        current.y = startY + dy.toInt()
                        clampInto()
                        runCatching { windowManager?.updateViewLayout(windowView, current) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    true
                }

                else -> false
            }
        }
    }

    private fun togglePanel() {
        if (expanded) collapse() else expand()
    }

    private fun expand() {
        val actionPanel = panel ?: return
        val current = params ?: return
        actionPanel.visibility = View.VISIBLE
        expanded = true
        actionPanel.post {
            panelWidth = actionPanel.width
            // 窗口整体左移，球本身留在原地
            current.x -= panelWidth
            clampInto()
            root?.let { runCatching { windowManager?.updateViewLayout(it, current) } }
        }
    }

    private fun collapse() {
        val actionPanel = panel ?: return
        val current = params ?: return
        actionPanel.visibility = View.GONE
        expanded = false
        current.x += panelWidth
        clampInto()
        root?.let { runCatching { windowManager?.updateViewLayout(it, current) } }
    }

    private fun clampInto() {
        val current = params ?: return
        val metrics = resources.displayMetrics
        val width = root?.width ?: 0
        if (current.x < 0) current.x = 0
        if (current.x + width > metrics.widthPixels) {
            current.x = (metrics.widthPixels - width).coerceAtLeast(0)
        }
        if (current.y < 0) current.y = 0
        if (current.y > metrics.heightPixels - dp(48)) {
            current.y = metrics.heightPixels - dp(48)
        }
    }

    /** 订阅录音状态，让三个按钮的可用状态跟着变 */
    private fun observeState() {
        stateJob?.cancel()
        stateJob = scope.launch {
            RecordingService.state.collect { state ->
                val idle = state.phase == RecorderPhase.IDLE || state.phase == RecorderPhase.PROCESSING
                val recording = state.phase == RecorderPhase.RECORDING
                val paused = state.phase == RecorderPhase.PAUSED

                // 不可用的按钮压暗但保留位置，避免图标跳来跳去
                btnPlay?.apply {
                    isEnabled = idle || paused
                    alpha = if (isEnabled) 1f else 0.35f
                }
                btnPause?.apply {
                    isEnabled = recording
                    alpha = if (isEnabled) 1f else 0.35f
                }
                btnStop?.apply {
                    isEnabled = recording || paused
                    alpha = if (isEnabled) 1f else 0.35f
                }
            }
        }
    }

    private fun loadCustomIcon(path: String): Boolean {
        if (path.isBlank()) return false
        if (!File(path).exists()) return false
        val bitmap = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return false
        val view = bubble ?: return false
        view.setImageBitmap(bitmap)
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setOval(0, 0, v.width, v.height)
            }
        }
        return true
    }

    private fun actionButton(
        icon: Int,
        description: String,
        size: Int,
        onClick: () -> Unit
    ): ImageButton = ImageButton(this).apply {
        setImageResource(icon)
        setBackgroundResource(R.drawable.bg_bubble_action)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = dp(10)
        setPadding(pad, pad, pad, pad)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(size, size)
    }

    // ---------------------------------------------------------------- 通知

    private fun startForegroundSafely() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "悬浮球",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "保持悬浮球可用的常驻通知，已压到最低优先级"
                    setShowBadge(false)
                }
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bubble_mic)
            .setContentTitle("悬浮球已开启")
            .setContentText("点击回到 NoteCraft")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()

        runCatching { startForeground(NOTIFICATION_ID, notification) }
    }

    // ---------------------------------------------------------------- 工具

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    companion object {
        private const val CHANNEL_ID = "notecraft_floating_ball"
        private const val NOTIFICATION_ID = 4102

        const val ACTION_SHOW = "com.dsh.notes.action.SHOW_BALL"
        const val ACTION_HIDE = "com.dsh.notes.action.HIDE_BALL"
        const val ACTION_REFRESH = "com.dsh.notes.action.REFRESH_BALL"

        /** 拖滑块时的即时预览值：带上就不用等服务从磁盘读 */
        const val EXTRA_SIZE = "extra_size_dp"
        const val EXTRA_ALPHA = "extra_alpha"

        fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun show(context: Context) {
            if (!canShow(context)) return
            val intent = Intent(context, FloatingBallService::class.java).setAction(ACTION_SHOW)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun hide(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java).setAction(ACTION_HIDE)
            runCatching { context.startService(intent) }
        }

        /**
         * 设置里改了图案 / 大小 / 透明度之后重建悬浮窗。
         * 只发一条 REFRESH，服务自己把属性改掉 —— 不重启服务，球不会消失。
         *
         * @param sizeDp 拖动时的即时预览值；传 null 则用磁盘上的设置
         */
        fun refresh(context: Context, sizeDp: Int? = null, alpha: Float? = null) {
            if (!canShow(context)) return
            val intent = Intent(context, FloatingBallService::class.java).setAction(ACTION_REFRESH)
            if (sizeDp != null) intent.putExtra(EXTRA_SIZE, sizeDp)
            if (alpha != null) intent.putExtra(EXTRA_ALPHA, alpha)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
