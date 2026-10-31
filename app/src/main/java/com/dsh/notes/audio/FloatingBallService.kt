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

    /**
     * 正在播「收回去」的动画。
     *
     * 这 160ms 里任何把 alpha/scale 恢复成展开态的刷新都要跳过，
     * 否则最后一帧会闪一下完整的面板 —— 看起来像收起来又蹦出来。
     */
    private var collapsing = false

    /** 面板这次展开在哪一侧：收起的圆弧要从同一侧收回去 */
    private var panelOnRight = true

    /** 当前已经应用到视图上的值，预览时以它为基准，避免每次去磁盘读设置 */
    private var appliedSizeDp = 56
    private var appliedAlpha = 0.9f

    /**
     * 悬浮球实际画出来的大小，比设置值小一圈。
     *
     * 设置里的数字是「直径」的语义，但圆形的视觉重量比同尺寸的方形大得多，
     * 按原值画会显得笨重；这里统一收 0.86，设置项的含义不变。
     */
    private fun scaledSizeDp(): Int =
        // 上限收到 64：设置拉到最大时那颗球会大得像块膏药，参考实现里它只有屏幕宽度的
        // 一小块。这里把实际尺寸和设置值解耦，设置只负责「相对大小」的意图。
        (appliedSizeDp.coerceAtMost(64) * 0.9f).toInt().coerceAtLeast(30)

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

        val sizePx = dp(scaledSizeDp())
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
        panelOnRight = onRight

        val x = if (onRight) {
            bp.x + ballW + dp(6)
        } else {
            bp.x - panelW - dp(6)
        }.coerceIn(0, (metrics.widthPixels - panelW).coerceAtLeast(0))

        val y = (bp.y + ballH / 2 - panelH / 2)
            .coerceIn(0, (metrics.heightPixels - panelH).coerceAtLeast(0))

        // 又被打开了：取消「正在收起」状态，否则动画回调会把面板删掉
        collapsing = false
        panelView.animate().cancel()
        /*
         * 关键：**加进窗口之前**就要设成「缩到最小 + 全透明」。
         *
         * 之前是在 post{} 里设的，而 post 要等到下一帧布局之后才跑 ——
         * 中间那一帧面板是以完整尺寸、完整不透明度画出来的，
         * 就是你看到的「闪一下变成展开的样子」。
         */
        panelView.scaleX = 1f
        panelView.alpha = 0f
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
        /*
         * 「伸出来」：圆形揭开。
         *
         * 圆点放在球贴着的那条边（贴右 → 左端，贴左 → 右端），半径 0 长到盖住整条，
         * 于是推进的边界天然是**一段圆弧**，视觉上就是从球里往右（或往左）擦出来。
         * 半径从 0 开始，意味着加进窗口的第一帧就是「什么都没有」——不会再闪。
         */
        panelView.post {
            val w = panelView.width.takeIf { it > 0 } ?: panelW
            val h = panelView.height.takeIf { it > 0 } ?: panelH
            val cx = if (onRight) 0 else w
            val cy = h / 2
            val maxRadius = kotlin.math.hypot(w.toDouble(), h.toDouble()).toFloat()
            runCatching {
                panelView.alpha = appliedAlpha
                android.view.ViewAnimationUtils
                    .createCircularReveal(panelView, cx, cy, 0f, maxRadius)
                    .apply {
                        duration = 200
                        interpolator = android.view.animation.DecelerateInterpolator()
                    }
                    .start()
            }.onFailure {
                // 极少数机型不支持，退回淡入，至少不会完全不显示
                panelView.alpha = appliedAlpha
            }
        }
        expanded = true
    }

    private fun collapse() {
        val panelView = panel ?: return
        if (panelView.parent != null) {
            collapsing = true
            panelView.animate().cancel()
            val w = panelView.width
            val h = panelView.height
            val cx = if (panelOnRight) 0 else w
            val maxRadius = kotlin.math.hypot(w.toDouble(), h.toDouble()).toFloat()
            // 收回：圆形揭开倒放 —— 圆弧边界从右往左（或反方向）擦回去
            val started = runCatching {
                android.view.ViewAnimationUtils
                    .createCircularReveal(panelView, cx, h / 2, maxRadius, 0f)
                    .apply {
                        duration = 170
                        interpolator = android.view.animation.AccelerateInterpolator()
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                if (!expanded) {
                                    runCatching { windowManager?.removeView(panelView) }
                                }
                                collapsing = false
                            }
                        })
                    }
                    .start()
            }.isSuccess
            if (!started) {
                runCatching { windowManager?.removeView(panelView) }
                collapsing = false
            }
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
        val size = ball?.width ?: dp(scaledSizeDp())
        current.x = current.x.coerceIn(0, (metrics.widthPixels - size).coerceAtLeast(0))
        current.y = current.y.coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))
    }

    private fun applyInPlace(sizeDp: Int, alpha: Float) {
        val ballView = ball ?: return
        appliedSizeDp = sizeDp.coerceIn(40, 96)
        appliedAlpha = alpha.coerceIn(0.3f, 1f)

        val sizePx = dp(scaledSizeDp())
        ballParams?.let { lp ->
            if (lp.width != sizePx || lp.height != sizePx) {
                lp.width = sizePx
                lp.height = sizePx
                runCatching { windowManager?.updateViewLayout(ballView, lp) }
            }
        }
        ballView.alpha = appliedAlpha
        // 正在收起时别把面板的 alpha 顶回去，否则最后一帧会闪出完整面板
        if (!collapsing) panel?.alpha = appliedAlpha
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
