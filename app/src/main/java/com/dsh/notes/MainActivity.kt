package com.dsh.notes

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.dsh.notes.ui.NoteCraftApp
import com.dsh.notes.ui.NoteCraftViewModelFactory

/**
 * 唯一的 Activity。
 *
 * 顺手处理两件事：
 *  1) 首次进入时申请麦克风 + 通知权限（Android 13+ 通知要单独申请）；
 *  2) 接收别的应用「分享音频到 NoteCraft」，直接进导入流程。
 */
class MainActivity : ComponentActivity() {

    private val container by lazy { (application as NoteCraftApp).container }

    /** 从 SEND / SEND_MULTIPLE 拿到的音频 Uri；界面消费后清空 */
    private val pendingAudioUris = mutableStateOf<List<Uri>>(emptyList())

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        // 用户拒了也不弹二次说明：录音页点开始时会再提示一次
        if (granted[Manifest.permission.POST_NOTIFICATIONS] == false) {
            // 通知权限被拒只影响前台服务的可见性，不影响录音本身
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        handleShareIntent(intent)
        requestNeededPermissions()

        setContent {
            val factory = remember { NoteCraftViewModelFactory(container) }
            val uris by pendingAudioUris
            NoteCraftApp(
                viewModelFactory = factory,
                sharedAudioUris = uris,
                onSharedAudioConsumed = { pendingAudioUris.value = emptyList() }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun requestNeededPermissions() {
        val needed = buildList {
            if (!com.dsh.notes.util.Permissions.hasAudio(this@MainActivity)) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !com.dsh.notes.util.Permissions.hasNotifications(this@MainActivity)
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            requestPermissions.launch(needed.toTypedArray())
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) pendingAudioUris.value = listOf(uri)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) pendingAudioUris.value = uris
            }

            else -> Unit
        }
    }
}
