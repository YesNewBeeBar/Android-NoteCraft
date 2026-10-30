package com.dsh.notes.audio

import android.animation.ObjectAnimator
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
 * 悬浮球：在任何界面一键开始 / 暂停 / 结束录音。
 *
 * 球和操作条是**两个独立的悬浮窗**。
 * 早先把它们塞进同一个窗口里重排子视图，展开时窗口宽度变化会让球看起来跳一下，
 * 左边放不下时还得整体挪窗口 —— 又抽又容易误触。拆成两个窗口之后：
 * 球的位置只由它自己那个窗口决定，展开只是「在旁边多挂一个窗口」，球一点不动。
 */
class FloatingBallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateJob: Job? = null
    private var visibilityJob: Job? = null

    private var windowManager: WindowManager? = null

    private var ball: ImageView? = null
    private var ballParams: WindowManager.LayoutParams? = null

    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var btnPlay: ImageButton? = null
    private var btnPause: ImageButton? = null
    private var btnStop: ImageButton? = null

    private var expanded = false

    /** 当前已经应用到视图上的值，预览时以它为基准，避免每次去磁盘读设置 */
    private var appliedSizeDp = 56
    private var appliedAlpha = 0.9f

    /** 录音时的呼吸动画 */
    private var pulse: android.animation.ObjectAnimator? = null

    /** 自定义图案的文件修改时间；0 表示当前是内置麦克风图标 */
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

            ACTION_REFRESH -> {
                scope.launch {
                    val base = runCatching {
                        (application as NoteCraftApp).container.settings.current()
                    }.getOrNull() ?: AppSettings()
                    val size = command.getIntExtra(EXTRA_SIZE, -1).takeIf { it > 0 }
                        ?: base.floatingBallSizeDp
                    val alpha = if (command.hasExtra(EXTRA_ALPHA)) {
                        command.getFloatExtra(EXTRA_ALPHA, -1f).takeIf { it >= 0f }
                    } else {
                        null
                    } ?: base.floatingBallAlpha

                    if (ball == null) {
                        buildBall(base)
                    } else {
                        applyInPlace(size, alpha)
                    }
                    // 图案可能变了：只有这次带了新路径才重载，不做时间戳推断
                    val path = base.floatingBallIconPath
                    if (ball != null && (path.isBlank() || File(path).lastModified() != loadedIconStamp)) {
                        if (!loadCustomIcon(path)) {
                            ball?.scaleType = ImageView.ScaleType.CENTER_INSIDE
                            ball?.clipToOutline = false
                            ball?.setImageResource(R.drawable.ic_bubble_mic)
                            loadedIconStamp = 0L
                        }
                    }
                    if (expanded) expand()
                }
                return START_STICKY
            }

            else -> showBall()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeAll()
        stateJob?.cancel()
        visibilityJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 悬浮窗

    private fun showBall() {
        if (ball != null) return
        if (!canDrawOverlays()) {
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
        if (ball != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        appliedSizeDp = (settings?.floatingBallSizeDp ?: 56).coerceIn(40, 96)
        appliedAlpha = (settings?.floatingBallAlpha ?: 0.9f).coerceIn(0.3f, 1f)
        val iconPath = settings?.floatingBallIconPath.orEmpty()

        val sizePx = dp(appliedSizeDp)
        val view = ImageView(this).apply {
            setBackgroundResource(R.drawable.bg_bubble)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "NoteCraft 悬浮球"
            setImageResource(R.drawable.ic_bubble_mic)
            alpha = appliedAlpha
        }

        val layoutParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 默认放左侧：展开时操作条往右伸，球不用挪
            x = dp(12)
            y = resources.displayMetrics.heightPixels / 3
        }

        attachDrag(view)

        val added = runCatching {
            windowManager?.addView(view, layoutParams)
            true
        }.getOrDefault(false)

        if (!added) {
            stopSelf()
            return
        }

        ball = view
        ballParams = layoutParams

        if (iconPath.isNotBlank() && loadCustomIcon(iconPath)) {
            loadedIconStamp = File(iconPath).lastModified()
        } else {
            view.setImageResource(R.drawable.ic_bubble_mic)
            loadedIconStamp = 0L
        }

        observeState()
        observeAppVisibility()
    }

    /** 操作条窗口：只在展开时挂上，位置由球的位置算 */
    private fun buildPanel(): LinearLayout {
        val actionSize = dp(ACTION_SIZE_DP)
        val margin = dp(ACTION_MARGIN_DP)

        val panelView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_bubble_panel)
            val pad = dp(PANEL_PAD_DP)
            setPadding(pad, pad, pad, pad)
        }

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
            panelView.addView(
                button,
                LinearLayout.LayoutParams(actionSize, actionSize).apply {
                    setMargins(margin, 0, margin, 0)
                }
            )
        }

        btnPlay = play
        btnPause = pause
        btnStop = stop
        applyStateToButtons(RecordingService.state.value)
        return panelView
    }

    private fun expand() {
        val ballView = ball ?: return
        val bp = ballParams ?: return
        val wm = windowManager ?: return

        val panelView = panel ?: buildPanel().also { panel = it }
        val panelW = panelWidthPx()
        val panelH = panelHeightPx()

        val metrics = resources.displayMetrics
        val ballW = ballView.width.coerceAtLeast(dp(40))
        val ballH = ballView.height.coerceAtLeast(dp(40))

        // 右边放得下就放右边，否则放左边 —— 无论哪种，球本身都不动
        val roomRight = metrics.widthPixels - (bp.x + ballW)
        val onRight = roomRight >= panelW + dp(6)

        val x = if (onRight) {
            bp.x + ballW + dp(6)
        } else {
            bp.x - panelW - dp(6)
        }.coerceIn(0, (metrics.widthPixels - panelW).coerceAtLeast(0))

        val y = (bp.y + ballH / 2 - panelH / 2)
            .coerceIn(0, (metrics.heightPixels - panelH).coerceAtLeast(0))

        panelView.alpha = appliedAlpha
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        if (panelView.parent == null) {
            runCatching { wm.addView(panelView, lp) }
            panelParams = lp
        } else {
            runCatching { wm.updateViewLayout(panelView, lp) }
            panelParams = lp
        }

        /*
         * 「伸出来」：从球贴着的那条边往外长，不是原地淡入。
         * 贴右边就从左端长出去，贴左边就从右端长出去 —— 和球的方向一致。
         */
        panelView.post {
            panelView.pivotX = if (onRight) 0f else panelView.width.toFloat()
            panelView.pivotY = panelView.height / 2f
            panelView.scaleX = 0.12f
            panelView.alpha = 0f
            panelView.animate()
                .scaleX(1f)
                .alpha(appliedAlpha)
                .setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
        expanded = true
    }

    private fun collapse() {
        val panelView = panel ?: return
        if (panelView.parent != null) {
            // 收回去：往球的方向缩，缩完再真正移除
            panelView.animate()
                .scaleX(0.12f)
                .alpha(0f)
                .setDuration(160)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    runCatching { windowManager?.removeView(panelView) }
                }
                .start()
        }
        expanded = false
    }

    /** 录音时不再做「呼吸」脉动：球只是变红，保持静止 */
    private fun updatePulse(recording: Boolean) {
        val view = ball ?: return
        pulse?.cancel()
        pulse = null
        view.scaleX = 1f
        view.scaleY = 1f
        if (recording) view.alpha = 1f
    }

    private fun removeAll() {
        collapse()
        ball?.let { view -> runCatching { windowManager?.removeView(view) } }
        pulse?.cancel()
        pulse = null
        ball = null
        ballParams = null
        panel = null
        panelParams = null
        loadedIconStamp = 0L
    }

    /** 拖动移动；轻点展开 / 收起操作条。只拖球那个窗口，操作条不会被拖着走 */
    private fun attachDrag(handle: View) {
        val slop = dp(6).toFloat()
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        handle.setOnTouchListener { _, event ->
            val current = ballParams ?: return@setOnTouchListener false
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
                        // 一开始拖就收起操作条，避免一边拖一边挂着一个窗口
                        if (expanded) collapse()
                    }
                    if (moved) {
                        current.x = startX + dx.toInt()
                        current.y = startY + dy.toInt()
                        clampBall()
                        runCatching { ball?.let { windowManager?.updateViewLayout(it, current) } }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (expanded) collapse() else expand()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun clampBall() {
        val current = ballParams ?: return
        val metrics = resources.displayMetrics
        val size = ball?.width ?: dp(appliedSizeDp)
        current.x = current.x.coerceIn(0, (metrics.widthPixels - size).coerceAtLeast(0))
        current.y = current.y.coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))
    }

    private fun applyInPlace(sizeDp: Int, alpha: Float) {
        val ballView = ball ?: return
        appliedSizeDp = sizeDp.coerceIn(40, 96)
        appliedAlpha = alpha.coerceIn(0.3f, 1f)

        val sizePx = dp(appliedSizeDp)
        ballParams?.let { lp ->
            if (lp.width != sizePx || lp.height != sizePx) {
                lp.width = sizePx
                lp.height = sizePx
                runCatching { windowManager?.updateViewLayout(ballView, lp) }
            }
        }
        ballView.alpha = appliedAlpha
        panel?.alpha = appliedAlpha
    }

    private fun observeState() {
        stateJob?.cancel()
        stateJob = scope.launch {
            RecordingService.state.collect { state ->
                applyStateToButtons(state)
                // 录音中把默认皮肤染红。用相册自定义图案的不染色
                updatePulse(state.phase == RecorderPhase.RECORDING)

                if (loadedIconStamp == 0L) {
                    ball?.setBackgroundResource(
                        if (state.phase == RecorderPhase.RECORDING) {
                            R.drawable.bg_bubble_recording
                        } else {
                            R.drawable.bg_bubble
                        }
                    )
                }
            }
        }
    }

    private fun applyStateToButtons(state: RecorderState) {
        val idle = state.phase == RecorderPhase.IDLE || state.phase == RecorderPhase.PROCESSING
        val recording = state.phase == RecorderPhase.RECORDING
        val paused = state.phase == RecorderPhase.PAUSED

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

    /** App 在前台就把球藏起来，切出去再显示 */
    private fun observeAppVisibility() {
        visibilityJob?.cancel()
        visibilityJob = scope.launch {
            AppVisibility.foreground.collect { foreground ->
                if (foreground && expanded) collapse()
                ball?.visibility = if (foreground) View.GONE else View.VISIBLE
            }
        }
    }

    private fun loadCustomIcon(path: String): Boolean {
        if (path.isBlank() || !File(path).exists()) return false
        val bitmap = decodeScaled(path, dp(appliedSizeDp).coerceAtLeast(48)) ?: return false
        val view = ball ?: return false
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

    private fun panelWidthPx(): Int =
        dp(3 * ACTION_SIZE_DP + 3 * 2 * ACTION_MARGIN_DP + 2 * PANEL_PAD_DP)

    private fun panelHeightPx(): Int =
        dp(ACTION_SIZE_DP + 2 * PANEL_PAD_DP)

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

        private const val ACTION_SIZE_DP = 44
        private const val ACTION_MARGIN_DP = 4
        private const val PANEL_PAD_DP = 6

        const val ACTION_SHOW = "com.dsh.notes.action.SHOW_BALL"
        const val ACTION_HIDE = "com.dsh.notes.action.HIDE_BALL"
        const val ACTION_REFRESH = "com.dsh.notes.action.REFRESH_BALL"
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

        fun refresh(context: Context, sizeDp: Int? = null, alpha: Float? = null) {
            if (!canShow(context)) return
            val intent = Intent(context, FloatingBallService::class.java).setAction(ACTION_REFRESH)
            if (sizeDp != null) intent.putExtra(EXTRA_SIZE, sizeDp)
            if (alpha != null) intent.putExtra(EXTRA_ALPHA, alpha)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
