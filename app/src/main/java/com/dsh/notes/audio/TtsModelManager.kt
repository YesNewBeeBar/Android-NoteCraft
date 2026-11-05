package com.dsh.notes.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 本地朗读模型的下载与安装。
 *
 * 流程（顺序不能变，任何一步失败都要能回到干净状态）：
 *   1. 下载到 cacheDir 的 .part 文件，同时算 SHA-256
 *   2. 校验哈希；不一致就删掉重来
 *   3. 解压到 filesDir/tts/.tmp-xxx，成功后整体 rename 成正式目录
 *   4. 回调通知设置页刷新
 *
 * 用 filesDir 而不是 cacheDir 存模型：系统空间不足时会清 cache，
 * 模型被清掉之后运行时再去加载就会直接崩。
 */
class TtsModelManager(private val context: Context) {

    enum class Phase { IDLE, DOWNLOADING, VERIFYING, EXTRACTING, DONE, ERROR }

    data class Progress(
        val phase: Phase = Phase.IDLE,
        val percent: Int = 0,
        val message: String = ""
    )

    private val _state = MutableStateFlow(Progress())
    val state: StateFlow<Progress> = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val rootDir: File
        get() = File(context.filesDir, "tts").apply { if (!exists()) mkdirs() }

    /** 已安装的模型目录名；没装或残缺返回 null */
    fun installed(): String? {
        val dir = File(rootDir, MODEL_NAME)
        return if (modelFile(dir) != null) MODEL_NAME else null
    }

    /** 包里实际叫 model.int8.onnx，别写死文件名 */
    private fun modelFile(dir: File): File? =
        dir.listFiles()?.firstOrNull {
            it.isFile && it.name.startsWith("model") && it.name.endsWith(".onnx") && it.length() > 0
        }

    fun remove() {
        runCatching { File(rootDir, MODEL_NAME).deleteRecursively() }
        runCatching { File(rootDir, ".tmp-$MODEL_NAME").deleteRecursively() }
        _state.value = Progress()
    }

    /** 当前网络是不是计费的（移动数据/热点） */
    fun isMetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        return cm.isActiveNetworkMetered
    }

    fun describeNetwork(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        return when {
            caps == null -> "无网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            else -> "其他网络"
        }
    }

    /** 下载并安装；返回失败原因，成功返回 null */
    suspend fun install(): String? = withContext(Dispatchers.IO) {
        if (MODEL_PART_URLS.isEmpty()) {
            return@withContext "模型包还没有发布，暂时无法下载"
        }
        val startedAt = System.currentTimeMillis()
        val partFile = File(context.cacheDir, "$MODEL_NAME.zip.part")
        runCatching { partFile.delete() }

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastError: String? = null

            // 模型超过 Gitee 单附件 100MB 的限制，切成多卷；按顺序下载并追加
            for ((index, url) in MODEL_PART_URLS.withIndex()) {
                val ok = runCatching {
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                        val body = response.body ?: throw IllegalStateException("响应为空")
                        val partLength = body.contentLength().takeIf { it > 0 } ?: 0L
                        body.byteStream().use { input ->
                            FileOutputStream(partFile, true).use { output ->
                                val buffer = ByteArray(128 * 1024)
                                var inPart = 0L
                                var last = -1
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                    inPart += read
                                    done += read
                                    val pct = (done * 100 / MODEL_SIZE_BYTES).toInt().coerceIn(0, 100)
                                    if (pct != last) {
                                        last = pct
                                        _state.value = Progress(
                                            Phase.DOWNLOADING,
                                            pct,
                                            "正在下载第 ${index + 1}/${MODEL_PART_URLS.size} 卷 · " +
                                                "${done / 1024 / 1024} MB / ${MODEL_SIZE_BYTES / 1024 / 1024} MB"
                                        )
                                    }
                                }
                                if (partLength > 0 && inPart != partLength) {
                                    throw IllegalStateException("第 ${index + 1} 卷不完整")
                                }
                            }
                        }
                    }
                    true
                }.onFailure {
                    lastError = it.message ?: "网络错误"
                }.getOrDefault(false)

                if (!ok) {
                    partFile.delete()
                    _state.value = Progress(Phase.ERROR, 0, "下载失败：${lastError ?: "未知错误"}")
                    return@withContext "下载失败：${lastError ?: "未知错误"}"
                }
            }

            if (!partFile.exists() || partFile.length() < 1024) {
                _state.value = Progress(Phase.ERROR, 0, "下载失败：${lastError ?: "没有拿到文件"}")
                return@withContext "下载失败：${lastError ?: "没有拿到文件"}"
            }

            _state.value = Progress(Phase.VERIFYING, 100, "校验文件…")
            if (MODEL_SHA256.isNotBlank()) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(MODEL_SHA256, ignoreCase = true)) {
                    partFile.delete()
                    _state.value = Progress(Phase.ERROR, 0, "校验失败，请重试")
                    return@withContext "文件校验失败（可能下载不完整）"
                }
            }

            _state.value = Progress(Phase.EXTRACTING, 100, "正在解压…")
            val tmp = File(rootDir, ".tmp-$MODEL_NAME")
            tmp.deleteRecursively()
            if (!tmp.mkdirs()) return@withContext "创建临时目录失败"

            runCatching {
                ZipInputStream(partFile.inputStream().buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        // 防目录穿越
                        if (name.contains("..")) continue
                        val target = File(tmp, name)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out -> zip.copyTo(out) }
                        }
                        zip.closeEntry()
                    }
                }
            }.onFailure { error ->
                tmp.deleteRecursively()
                _state.value = Progress(Phase.ERROR, 0, "解压失败：${error.message}")
                return@withContext "解压失败：${error.message ?: "未知错误"}"
            }

            // 官方包可能多一层目录，摊平到 tmp 根上
            val nested = tmp.listFiles()?.singleOrNull()?.takeIf { it.isDirectory }
            val source = if (modelFile(nested ?: tmp) != null) (nested ?: tmp) else tmp
            if (modelFile(source) == null) {
                tmp.deleteRecursively()
                _state.value = Progress(Phase.ERROR, 0, "包里没有模型文件")
                return@withContext "模型包结构不对（缺少 model*.onnx）"
            }

            val finalDir = File(rootDir, MODEL_NAME)
            finalDir.deleteRecursively()
            if (source != tmp) {
                source.renameTo(finalDir)
                tmp.deleteRecursively()
            } else {
                tmp.renameTo(finalDir)
            }
            partFile.delete()

            _state.value = Progress(Phase.DONE, 100, "已安装")
            android.util.Log.i(
                "TtsModel",
                "安装完成，用时 ${(System.currentTimeMillis() - startedAt) / 1000}s"
            )
            null
        } catch (error: Exception) {
            partFile.delete()
            _state.value = Progress(Phase.ERROR, 0, "安装失败：${error.message}")
            error.message ?: "安装失败"
        }
    }

    companion object {
        const val MODEL_NAME = "kokoro-int8-multi-lang-v1_0"

        /**
         * 模型 127MB，超过 Gitee 单附件 100MB 的上限，切成三卷按顺序下载后拼接。
         * 任何一卷缺失或不完整都会直接失败，不会拿半个包去解压。
         */
        val MODEL_PART_URLS = listOf(
            "https://gitee.com/YesNewBeeBar/android-note-craft/releases/download/v1.5.1/kokoro-part.zip.00",
            "https://gitee.com/YesNewBeeBar/android-note-craft/releases/download/v1.5.1/kokoro-part.zip.01",
            "https://gitee.com/YesNewBeeBar/android-note-craft/releases/download/v1.5.1/kokoro-part.zip.02"
        )

        /** 拼接后整包的 SHA-256 */
        const val MODEL_SHA256 = "b667f216e5d92dec04620cc3e89c072aaaa8b9071ab4cc021ca7bffd29a6663d"

        /** 拼接后的总字节数，用于进度百分比 */
        const val MODEL_SIZE_BYTES = 132837398L
    }
}
