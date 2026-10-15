package com.dsh.notes.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dsh.notes.MainActivity
import com.dsh.notes.NoteCraftApp
import com.dsh.notes.R
import com.dsh.notes.data.AppSettings
import com.dsh.notes.data.AudioSource
import com.dsh.notes.data.Note
import com.dsh.notes.data.NoteStatus
import com.dsh.notes.data.StorageFormat

import com.dsh.notes.pipeline.PipelineProgress
import com.dsh.notes.pipeline.languageTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

enum class RecorderPhase { IDLE, RECORDING, PAUSED, PROCESSING }

/**
 * 录音状态。UI 通过 [RecordingService.state] 订阅；
 * 前台服务是唯一写这份状态的地方。
 */
data class RecorderState(
    val phase: RecorderPhase = RecorderPhase.IDLE,
    val noteId: Long = 0L,
    val elapsedMs: Long = 0L,
    val finalizedText: String = "",
    val partialText: String = "",
    val muted: Boolean = false,
    /** 当前这次录音用的是压缩格式还是 WAV，界面上要如实告诉用户 */
    val compressed: Boolean = false,
    /** 压缩引擎不支持静音，界面据此禁用静音按钮 */
    val muteSupported: Boolean = true,
    val liveCaptionActive: Boolean = false,
    val level: Float = 0f,
    val error: String? = null
) {
    val isRecording: Boolean
        get() = phase == RecorderPhase.RECORDING || phase == RecorderPhase.PAUSED

    val hint: String
        get() = when (phase) {
            RecorderPhase.IDLE -> "点下面的按钮开始录音"
            RecorderPhase.RECORDING -> "正在录音，可以熄屏"
            RecorderPhase.PAUSED -> "已暂停，点继续接着录"
            RecorderPhase.PROCESSING -> "正在转写与整理，完成后通知你"
        }
}

/**
 * 录音前台服务。
 *
 * 职责：
 *  - 选一个录音引擎（见 [AudioRecorder]）把音频落到私有目录；
 *  - 引擎支持 PCM 时，把 PCM 切片交给 [LiveCaptioner] 做实时上屏；
 *  - 常驻通知栏：切后台、熄屏、锁屏都能继续录；
 *  - 结束后**不立刻停掉自己**，而是继续前台挂着跑完「转写 + 整理」。
 *    这点很关键：否则用户一锁屏，系统就可能回收进程，转写跑到一半就没了。
 *
 * 引擎选择规则（这是「省空间」与「实时上屏」之间的取舍）：
 *  - 实时上屏开着 → 必须用 WAV（切片需要原始 PCM）；
 *  - 实时上屏关着 → 用户选的格式生效，默认 m4a，比 WAV 小 8 倍。
 */
class RecordingService : Service() {

    private val container by lazy { (application as NoteCraftApp).container }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var recorder: AudioRecorder? = null
    private var captioner: LiveCaptioner? = null
    private var ticker: Job? = null
    private var processingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var audioFile: File? = null
    private var sampleRate = 16_000
    private var startedAt = 0L

    /** 启动流程可能跨线程，用 @Volatile 保证可见性 */
    @Volatile
    private var starting = false

    private var lastText = ""

    /** 录音线程写、计时协程读 */
    @Volatile
    private var levelValue = 0f

    /** 通知栏大约每秒刷一次就够 */
    private var lastNotificationAt = 0L
    private var lastNotificationText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_TOGGLE_MUTE -> handleToggleMute()
            ACTION_STOP -> handleStop()
            ACTION_CANCEL -> handleCancel()
            else -> Unit
        }
        // 不被系统重启：录音是用户主动行为，重启一个「幽灵录音」比丢掉它更糟
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- 开始

    private fun handleStart(intent: Intent) {
        if (recorder != null || starting) {
            Log.w(TAG, "已经在录音或正在启动，忽略重复的 START")
            return
        }

        if (!hasAudioPermission()) {
            pushState(_state.value.copy(error = "没有录音权限，请先授权麦克风"))
            broadcast()
            stopSelf()
            return
        }

        // startForeground 必须在收到命令后几秒内调用，所以先挂通知再干别的
        if (!startForegroundSafely(buildNotification("准备录音…", "正在初始化麦克风"))) {
            pushState(_state.value.copy(error = "系统不允许启动录音前台服务（通知权限可能被关闭）"))
            broadcast()
            stopSelf()
            return
        }

        starting = true
        val rateExtra = intent.getIntExtra(EXTRA_SAMPLE_RATE, 0)
        val titleHint = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        scope.launch { beginRecording(rateExtra, titleHint) }
    }

    /** 真正开始录音；跑在 IO 线程上，可以放心做数据库与文件操作 */
    private suspend fun beginRecording(rateExtra: Int, titleHint: String) {
        val settings = container.settings.current()
        sampleRate = if (rateExtra > 0) rateExtra else settings.sampleRate
        acquireWakeLock()

        val noteId = container.repository.create(
            Note(
                title = titleHint,
                status = NoteStatus.RECORDING,
                audioSource = AudioSource.RECORDED,
                createdAt = System.currentTimeMillis()
            )
        )
        container.beginNote(noteId)

        try {
            val dir = File(filesDir, "audio").apply { if (!exists()) mkdirs() }

            // 实时上屏必须要有 PCM 流，所以它和「压缩格式」只能二选一
            val useLiveCaption = settings.liveCaptionEnabled && settings.cloudAsrReady
            val compressed = settings.storageFormat == StorageFormat.M4A && !useLiveCaption
            val extension = if (compressed) "m4a" else "wav"
            val file = File(dir, "note-$noteId.$extension")

            val engine: AudioRecorder = if (compressed) {
                M4aAudioRecorder(context = this, targetFile = file)
            } else {
                WavAudioRecorder(sampleRate = sampleRate, channelCount = 1, file = file)
            }

            audioFile = file
            recorder = engine

            val caption = if (useLiveCaption) createCaptioner(settings) else null
            captioner = caption
            caption?.start()

            engine.start(onPcm = { buffer, length -> caption?.feed(buffer, length) })

            container.repository.patch(noteId) {
                it.copy(audioPath = file.absolutePath, audioSizeBytes = 0L)
            }
            startTicker()

            startedAt = System.currentTimeMillis()
            starting = false
            pushState(
                RecorderState(
                    phase = RecorderPhase.RECORDING,
                    noteId = noteId,
                    compressed = compressed,
                    muteSupported = !compressed,
                    liveCaptionActive = caption != null
                )
            )
            updateNotification(force = true)
            broadcast()
            Log.i(
                TAG,
                "开始录音：note=$noteId rate=$sampleRate file=${file.name} " +
                    "压缩=$compressed 上屏=${caption != null}"
            )
        } catch (error: Throwable) {
            Log.e(TAG, "启动录音失败", error)
            starting = false
            cleanupAfterFailure()
            container.repository.patch(noteId) {
                it.copy(status = NoteStatus.FAILED, errorMessage = error.message ?: "启动录音失败")
            }
            pushState(
                RecorderState(phase = RecorderPhase.IDLE, error = error.message ?: "启动录音失败")
            )
            broadcast()
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun createCaptioner(settings: AppSettings): LiveCaptioner = LiveCaptioner(
        scope = scope,
        asr = container.ai.asr,
        baseUrl = settings.asrBaseUrl,
        apiKey = settings.asrApiKey,
        model = settings.asrModel,
        language = settings.languageTag(),
        sampleRate = sampleRate,
        chunkMs = settings.liveCaptionChunkMs,
        onSegment = { text -> onCaptionSegment(text) },
        onError = { message ->
            pushState(_state.value.copy(error = "实时上屏出错：$message"))
            broadcast()
        }
    )

    private fun onCaptionSegment(text: String) {
        val merged = if (lastText.isBlank()) text else "$lastText\n$text"
        lastText = merged
        pushState(_state.value.copy(finalizedText = merged, partialText = ""))
        updateNotification()
        broadcast()
    }

    // ---------------------------------------------------------------- 暂停 / 继续 / 静音

    private fun handlePause() {
        val current = recorder ?: return
        if (current.isPaused) return
        current.pause()
        pushState(_state.value.copy(phase = RecorderPhase.PAUSED, level = 0f))
        updateNotification(force = true)
        broadcast()
    }

    private fun handleResume() {
        val current = recorder ?: return
        if (!current.isPaused) return
        current.resume()
        pushState(_state.value.copy(phase = RecorderPhase.RECORDING))
        updateNotification(force = true)
        broadcast()
    }

    private fun handleToggleMute() {
        val current = recorder ?: return
        if (current !is WavAudioRecorder) {
            // 压缩引擎在采样层拿不到数据，做不了静音，如实告诉用户而不是假装成功
            pushState(_state.value.copy(error = "压缩录音格式不支持静音，可改用 WAV 或直接暂停"))
            broadcast()
            return
        }
        val next = !current.isMuted
        current.setMuted(next)
        pushState(_state.value.copy(muted = next))
        updateNotification(force = true)
        broadcast()
    }

    // ---------------------------------------------------------------- 结束

    private fun handleStop() {
        if (processingJob?.isActive == true) return
        processingJob = scope.launch { finishRecordingAndProcess() }
    }

    private suspend fun finishRecordingAndProcess() {
        val noteId = _state.value.noteId
        val engine = recorder ?: return

        pushState(_state.value.copy(phase = RecorderPhase.PROCESSING, level = 0f))
        updateNotification(force = true)
        broadcast()

        // 1) 让实时上屏把最后一段尾巴发完（最多等 30 秒，避免卡死）
        withTimeoutOrNull(30_000) { captioner?.finishAndDrain() }
        captioner = null

        // 2) 停采集：WAV 引擎会回填头，m4a 引擎会写完 moov（不写文件就播不了）
        val durationMs = engine.elapsedMs
        runCatching { engine.stop() }
        recorder = null

        stopTicker()

        val file = audioFile
        val finalSize = file?.takeIf { it.exists() }?.length() ?: 0L
        val duration = durationMs.takeIf { it > 0L }
            ?: file?.let { probeDurationMs(it, sampleRate) }
            ?: 0L

        if (noteId > 0) {
            container.repository.patch(noteId) {
                it.copy(
                    status = NoteStatus.SAVED,
                    audioPath = file?.absolutePath,
                    audioDurationMs = duration,
                    audioSizeBytes = finalSize,
                    liveDraft = lastText
                )
            }
        }

        Log.i(TAG, "录音结束：note=$noteId 时长=${duration}ms 大小=${finalSize}B 文件=${file?.name}")

        val settings = container.settings.current()
        if (noteId > 0 && settings.autoProcess) {
            // 3) 继续挂在前台把转写与整理跑完 —— 用户锁屏也不会被系统杀掉
            container.pipeline.process(
                noteId = noteId,
                forceTranscribe = true,
                onProgress = { progress -> onPipelineProgress(progress) }
            )
        }

        if (noteId > 0) container.clearActiveNote()

        stopTicker()
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
        pushState(RecorderState(phase = RecorderPhase.IDLE))
        broadcast()
    }

    private fun onPipelineProgress(progress: PipelineProgress) {
        if (progress.message != lastNotificationText) {
            lastNotificationText = progress.message
            updateNotification(force = true)
        }
    }

    private fun handleCancel() {
        val noteId = _state.value.noteId
        scope.launch {
            captioner?.cancel()
            captioner = null
            runCatching { recorder?.stop() }
            recorder = null
            stopTicker()
            releaseWakeLock()
            audioFile?.let { runCatching { it.delete() } }
            if (noteId > 0) container.repository.deleteById(noteId)
            container.clearActiveNote()
            clearPersistedState(this@RecordingService)
            pushState(RecorderState(phase = RecorderPhase.IDLE))
            broadcast()
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun cleanupAfterFailure() {
        captioner?.cancel()
        captioner = null
        runCatching { recorder?.stop() }
        recorder = null
        stopTicker()
        releaseWakeLock()
        audioFile?.let { runCatching { it.delete() } }
    }

    // ---------------------------------------------------------------- 通知

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RECORDING,
                getString(R.string.notif_channel_recording_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_recording_desc)
                setShowBadge(false)
                enableVibration(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TASK,
                getString(R.string.notif_channel_task_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_task_desc)
                setShowBadge(false)
                enableVibration(false)
            }
        )
    }

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            pendingIntentFlags()
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setWhen(System.currentTimeMillis())
            .setUsesChronometer(_state.value.isRecording)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        val phase = _state.value.phase
        if (phase == RecorderPhase.RECORDING || phase == RecorderPhase.PAUSED) {
            val pauseLabel = if (phase == RecorderPhase.PAUSED) {
                getString(R.string.notif_recording_action_resume)
            } else {
                getString(R.string.notif_recording_action_pause)
            }
            builder.addAction(
                0,
                pauseLabel,
                servicePendingIntent(
                    if (phase == RecorderPhase.PAUSED) ACTION_RESUME else ACTION_PAUSE,
                    1
                )
            )
            builder.addAction(
                0,
                getString(R.string.notif_recording_action_stop),
                servicePendingIntent(ACTION_STOP, 2)
            )
        }
        return builder.build()
    }

    private fun updateNotification(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationAt < 900) return
        lastNotificationAt = now

        val state = _state.value
        val notification = when (state.phase) {
            RecorderPhase.IDLE -> return

            RecorderPhase.PROCESSING -> {
                val message = lastNotificationText.ifBlank { "正在转写与整理…" }
                NotificationCompat.Builder(this, CHANNEL_TASK)
                    .setSmallIcon(R.drawable.ic_notification_mic)
                    .setContentTitle("正在处理这条笔记")
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(0, 0, true)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            this,
                            0,
                            Intent(this, MainActivity::class.java),
                            pendingIntentFlags()
                        )
                    )
                    .build()
            }

            else -> {
                val time = formatDuration(state.elapsedMs)
                val format = if (state.compressed) "m4a" else "WAV"
                val preview = state.finalizedText.replace(Regex("\\s+"), " ").takeLast(60)
                val text = when {
                    state.muted -> "已静音 · $time · $format"
                    preview.isNotBlank() -> "$time · $format · …$preview"
                    else -> "$time · 正在录音（$format）"
                }
                buildNotification(getString(R.string.notif_recording_title), text)
            }
        }

        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "更新通知失败（可能没有通知权限）", it) }
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, RecordingService::class.java).setAction(action),
            pendingIntentFlags()
        )

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun startForegroundSafely(notification: Notification): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    }.getOrElse { error ->
        Log.e(TAG, "startForeground 失败", error)
        false
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    // ---------------------------------------------------------------- 计时 / 唤醒锁 / 状态

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (true) {
                val engine = recorder ?: break
                val state = _state.value
                if (state.phase == RecorderPhase.RECORDING || state.phase == RecorderPhase.PAUSED) {
                    pushState(
                        state.copy(
                            elapsedMs = engine.elapsedMs,
                            level = if (engine.isPaused || engine !is WavAudioRecorder) 0f else levelValue
                        )
                    )
                    updateNotification()
                    broadcast()
                }
                delay(500)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        runCatching {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                ?.apply {
                    setReferenceCounted(false)
                    // 最长 3 小时，超时自动释放，避免漏掉 release
                    acquire(3 * 60 * 60 * 1000L)
                }
        }.onFailure { Log.w(TAG, "申请唤醒锁失败", it) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun pushState(state: RecorderState) {
        _state.value = state
        // 顺手落一份：进程被杀后能判断「上次是不是录到一半」
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong(KEY_ACTIVE_NOTE, state.noteId)
            .putLong(KEY_ACTIVE_SINCE, startedAt)
            .putBoolean(KEY_RECORDING, state.isRecording || state.phase == RecorderPhase.PROCESSING)
            .apply()
    }

    private fun broadcast() {
        runCatching {
            sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
        }
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun formatDuration(ms: Long): String {
        val total = (ms / 1000).toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    override fun onDestroy() {
        captioner?.cancel()
        captioner = null
        runCatching { recorder?.stop() }
        recorder = null
        stopTicker()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingService"
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_TASK = "task_progress"
        const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "NoteCraft:recording"
        private const val PREFS = "recording_state"
        private const val KEY_ACTIVE_NOTE = "active_note_id"
        private const val KEY_ACTIVE_SINCE = "active_since"
        private const val KEY_RECORDING = "was_recording"

        const val ACTION_START = "com.dsh.notes.action.START"
        const val ACTION_PAUSE = "com.dsh.notes.action.PAUSE"
        const val ACTION_RESUME = "com.dsh.notes.action.RESUME"
        const val ACTION_TOGGLE_MUTE = "com.dsh.notes.action.TOGGLE_MUTE"
        const val ACTION_STOP = "com.dsh.notes.action.STOP"
        const val ACTION_CANCEL = "com.dsh.notes.action.CANCEL"
        const val ACTION_STATE_CHANGED = "com.dsh.notes.action.STATE_CHANGED"

        const val EXTRA_SAMPLE_RATE = "sample_rate"
        const val EXTRA_TITLE = "title"

        private val _state = MutableStateFlow(RecorderState())

        val state: StateFlow<RecorderState> = _state.asStateFlow()

        fun start(context: Context, sampleRate: Int, title: String? = null) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SAMPLE_RATE, sampleRate)
                if (!title.isNullOrBlank()) putExtra(EXTRA_TITLE, title)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun send(context: Context, action: String) {
            runCatching {
                context.startService(Intent(context, RecordingService::class.java).setAction(action))
            }
        }

        fun stop(context: Context) = send(context, ACTION_STOP)

        fun pause(context: Context) = send(context, ACTION_PAUSE)

        fun resume(context: Context) = send(context, ACTION_RESUME)

        fun toggleMute(context: Context) = send(context, ACTION_TOGGLE_MUTE)

        fun cancel(context: Context) = send(context, ACTION_CANCEL)

        /** 进程重启后清理「上次录到一半」的残留状态 */
        fun clearPersistedState(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            val phase = _state.value.phase
            if (phase == RecorderPhase.RECORDING || phase == RecorderPhase.PAUSED) {
                _state.value = RecorderState(phase = RecorderPhase.IDLE)
            }
        }

        fun wasInterrupted(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_RECORDING, false)
    }
}
