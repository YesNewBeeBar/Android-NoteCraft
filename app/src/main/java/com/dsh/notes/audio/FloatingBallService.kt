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

    /** 当前已经应用到视图上的值。预览时以它为基准，免得每次去磁盘读设置 */
    private var appliedSize = 56
    private var appliedAlpha = 0.9f

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

            /*
             * 拖滑块时的即时预览。这条路径「不碰磁盘、不读设置、不起协程」：
             * 值直接从 intent 带过来，落到已记住的 applied 字段上，同步改视图。
             * 之前这里每次都 await settings.current()，相当于每拖一格就做一次
             * DataStore I/O + 协程调度，手指当然追不上。
             */
            ACTION_PREVIEW -> {
                if (root == null) {
                    // 球根本没挂上，预览没有意义；顺手把误启动的服务收掉，别留一条空通知
                    stopSelf()
                    return START_NOT_STICKY
                }
                val sizeOverride = command.getIntExtra(EXTRA_SIZE, -1).takeIf { it > 0 }
                val alphaOverride = if (command.hasExtra(EXTRA_ALPHA)) {
                    command.getFloatExtra(EXTRA_ALPHA, -1f).takeIf { it >= 0f }
                } else {
                    null
                }
                applyInPlace(
                    sizeDp = sizeOverride ?: appliedSize,
                    alpha = alphaOverride ?: appliedAlpha
                )
                return START_STICKY
            }

            /*
             * 图案变了 / 恢复默认：走完整重建。
             * 不再依赖「文件 mtime 有没有变」这种推断 —— 直接拆了重搭，
             * 和用户手动关开一次等价，那条路是确定能刷新的。
             */
            ACTION_RELOAD -> {
                scope.launch {
                    val latest = runCatching {
                        (application as NoteCraftApp).container.settings.current()
                    }.getOrNull() ?: AppSettings()
                    removeBall()
                    buildBall(latest)
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

        appliedSize = (settings?.floatingBallSizeDp ?: 56).coerceIn(40, 96)
        appliedAlpha = (settings?.floatingBallAlpha ?: 0.9f).coerceIn(0.3f, 1f)
        val sizePx = dp(appliedSize)
        val alpha = appliedAlpha
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
        if (iconPath.isBlank() || !loadCustomIcon(iconPath)) {
            ball.setImageResource(R.drawable.ic_bubble_mic)
        }

        observeState()
    }

    /**
     * 同步改尺寸 / 透明度。
     *
     * 不读磁盘、不起协程、不做任何 binder 之外的等待 —— 拖滑块时每一帧都会调到这里，
     * 中间多一次 DataStore 读就会明显发黏。
     */
    private fun applyInPlace(sizeDp: Int, alpha: Float) {
        val container = root ?: return
        val ball = bubble ?: return

        appliedSize = sizeDp.coerceIn(40, 96)
        appliedAlpha = alpha.coerceIn(0.3f, 1f)

        val sizePx = dp(appliedSize)
        (ball.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            if (lp.width != sizePx || lp.height != sizePx) {
                lp.width = sizePx
                lp.height = sizePx
                ball.layoutParams = lp
            }
        }

        if (container.alpha != appliedAlpha) container.alpha = appliedAlpha

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
        if (path.isBlank() || !File(path).exists()) return false
        // 相册里的图动辄四五千像素，按悬浮球实际尺寸降采样，别整张解进来
        val bitmap = decodeScaled(path, dp(appliedSize).coerceAtLeast(48)) ?: return false
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

    /** 只解出需要的大小，避免大图解码慢 / OOM */
    private fun decodeScaled(path: String, targetPx: Int): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(path, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx &&
            bounds.outHeight / (sample * 2) >= targetPx
        ) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull()
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

        /** 拖滑块时的即时预览：同步改视图，不碰磁盘 */
        const val ACTION_PREVIEW = "com.dsh.notes.action.PREVIEW_BALL"

        /** 图案变化 / 恢复默认：拆掉重搭，等价于手动关开一次 */
        const val ACTION_RELOAD = "com.dsh.notes.action.RELOAD_BALL"

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
         * 拖动滑块时的即时预览。
         *
         * 刻意不做权限检查、不起前台服务、不读设置：这条路径每一帧都会被调用，
         * 任何一次 binder 往返都会变成手指上的黏滞感。
         */
        fun preview(context: Context, sizeDp: Int? = null, alpha: Float? = null) {
            val intent = Intent(context, FloatingBallService::class.java)
                .setAction(ACTION_PREVIEW)
            if (sizeDp != null) intent.putExtra(EXTRA_SIZE, sizeDp)
            if (alpha != null) intent.putExtra(EXTRA_ALPHA, alpha)
            runCatching { context.startService(intent) }
        }

        /**
         * 图案变了 / 恢复默认：整块重建。
         * 不做「文件时间戳有没有变」这类推断 —— 直接把视图拆了重搭，
         * 和用户手动关开一次是同一件事，确定能刷新。
         */
        fun reload(context: Context) {
            if (!canShow(context)) return
            val intent = Intent(context, FloatingBallService::class.java).setAction(ACTION_RELOAD)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
