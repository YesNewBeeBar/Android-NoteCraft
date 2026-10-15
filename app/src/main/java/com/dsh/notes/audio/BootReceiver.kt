package com.dsh.notes.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dsh.notes.NoteCraftApp
import com.dsh.notes.data.NoteStatus
import kotlinx.coroutines.launch

/**
 * 开机 / 应用更新后收尾。
 *
 * 设备重启会把正在录的进程直接干掉，数据库里却留着 status=RECORDING 的行。
 * 这个接收器负责把它们标成「被中断」，免得界面上永远显示一个转圈的录音。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val app = context.applicationContext as? NoteCraftApp ?: return
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                val interrupted = app.container.repository.listRecording()
                interrupted.forEach { note ->
                    app.container.repository.patch(note.id) {
                        it.copy(
                            status = NoteStatus.FAILED,
                            errorMessage = "录音被系统中断（重启或强制停止），音频可能不完整"
                        )
                    }
                }
                RecordingService.clearPersistedState(app)
                if (interrupted.isNotEmpty()) {
                    Log.i(TAG, "标记 ${interrupted.size} 条被中断的录音")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
