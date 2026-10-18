package com.dsh.notes.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.dsh.notes.BuildConfig
import com.dsh.notes.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 应用内自更新。
 *
 * 版本源就是本仓库的 GitHub Releases：查 `releases/latest`，比版本号，
 * 有新版本就把 APK 下到 cache 里、交给系统安装器。用户不用再自己去仓库翻。
 *
 * 做成 AppContainer 里的单例：设置页的「检查更新」和启动时的自动检查
 * 必须共用同一个状态，否则两边会各弹各的。
 */
class UpdateManager(
    private val context: Context,
    private val settingsStore: SettingsStore
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * 查一次最新版本。
     *
     * @param manual 手动点的：无论结果如何都给一句话反馈；自动检查则限流且静默失败
     * @return 需要弹给用户的一句话（没有就返回 null）
     */
    suspend fun check(manual: Boolean): String? {
        val settings = settingsStore.current()

        // 自动检查限流：否则每次切回前台都要打一次 GitHub，白白消耗接口配额
        if (!manual && System.currentTimeMillis() - settings.lastUpdateCheckAt < AUTO_CHECK_GAP_MS) {
            return null
        }

        // 正在下载 / 正等安装器时绝不重查。
        // 锁屏后回到前台会触发一次自动检查，如果这时把状态打成 Checking / Available，
        // 界面上的进度条就会「来回拉扯」—— 这是那个横跳 bug 的根因。
        when (_state.value) {
            is UpdateState.Downloading, is UpdateState.Installer ->
                return if (manual) "正在更新，请稍候" else null
            else -> Unit
        }

        _state.value = UpdateState.Checking
        val result = runCatching { fetchNewestRelease() }
        settingsStore.setLastUpdateCheckAt(System.currentTimeMillis())

        return result.fold(
            onSuccess = { info ->
                when {
                    info == null -> {
                        _state.value = UpdateState.UpToDate
                        if (manual) "仓库里还没有发布版本" else null
                    }

                    // 以「最新发布的那个 release」为准，而不是比版本号大小。
                    // 版本号怎么编排是人的事（1.2.11 之后又发 1.2.2 这种情况真的发生过），
                    // 数字大不代表发布得晚。只要最新 release 和手上的版本不是同一个，就提示。
                    info.versionName == BuildConfig.VERSION_NAME -> {
                        _state.value = UpdateState.UpToDate
                        if (manual) "已经是最新版本（v${BuildConfig.VERSION_NAME}）" else null
                    }

                    // 用户点过「稍后」的版本，自动检查就别再烦他了；手动点仍然要弹
                    !manual && info.versionName == settings.ignoredUpdateVersion -> {
                        _state.value = UpdateState.UpToDate
                        null
                    }

                    else -> {
                        _state.value = UpdateState.Available(info)
                        null
                    }
                }
            },
            onFailure = { error ->
                Log.w(TAG, "检查更新失败", error)
                // 自动检查失败不打扰用户：可能只是没网
                _state.value = UpdateState.Idle
                if (manual) "检查更新失败：${error.message ?: "网络错误"}" else null
            }
        )
    }

    /**
     * 下载更新包并交给系统安装器。
     * @return 出错时的一句话；已经交给安装器（或已把用户送去开权限）时返回 null
     */
    suspend fun downloadAndInstall(): String? {
        // 防止重复触发：两次下载并发会让百分比互相覆盖，进度条就会左右横跳
        if (_state.value is UpdateState.Downloading) return null

        val info = (_state.value as? UpdateState.Available)?.info
            ?: return "没有待安装的更新"
        val url = info.apkUrl ?: return "这一版没有附带 APK，去发布页看看"

        val file = try {
            downloadApk(url, info)
        } catch (error: Exception) {
            Log.w(TAG, "下载更新包失败", error)
            _state.value = UpdateState.Available(info) // 回到可重试的状态
            return error.message ?: "下载失败"
        }

        _state.value = UpdateState.Installer(file)
        val failure = launchInstaller(file)
        _state.value = UpdateState.Idle
        return failure
    }

    /** 用户点了「稍后」：记住这个版本，自动检查时不再弹 */
    suspend fun dismiss(info: UpdateInfo) {
        settingsStore.setIgnoredUpdateVersion(info.versionName)
        _state.value = UpdateState.Idle
    }

    /** 手动检查时把状态清干净，免得旧的「稍后」记录把新版本也吞掉 */
    suspend fun forgetIgnored() {
        settingsStore.setIgnoredUpdateVersion("")
    }

    // ---------------------------------------------------------------- 内部实现

    /**
     * 取「最新发布的那个 release」。
     *
     * 不用 `/releases/latest`：GitHub 对它的排序是按版本标签（semver）来的，
     * 于是 1.2.11 会被排到 1.2.2 后面 —— 数字大就当成「更新」，
     * 可实际上后发布的才是新的。这里改成拉列表、按 published_at 倒序，
     * 自己挑第一个非草稿、非预发布的，这才是真正意义上的「最新」。
     */
    private suspend fun fetchNewestRelease(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases?per_page=30")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "NoteCraft-Android")
            .build()

        client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null // 仓库还没发过 release，不是错误
                !response.isSuccessful -> throw IOException("HTTP ${response.code}")
                else -> {
                    val array = JSONArray(response.body?.string().orEmpty())
                    val candidates = buildList {
                        for (i in 0 until array.length()) {
                            val item = array.optJSONObject(i) ?: continue
                            if (item.optBoolean("draft") || item.optBoolean("prerelease")) continue
                            add(item)
                        }
                    }
                    // published_at 是 ISO8601（2026-10-18T23:00:00Z），字典序最大即最新
                    val newest = candidates.maxByOrNull { it.optString("published_at") }
                        ?: return@withContext null
                    parseRelease(newest)
                }
            }
        }
    }

    private fun parseRelease(json: JSONObject): UpdateInfo {
        val tag = json.optString("tag_name")

        var apkUrl: String? = null
        var apkSize = 0L
        json.optJSONArray("assets")?.let { assets ->
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (!asset.optString("name").endsWith(".apk", ignoreCase = true)) continue
                apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                apkSize = asset.optLong("size")
                break
            }
        }

        return UpdateInfo(
            versionName = tag.removePrefix("v").removePrefix("V").ifBlank { tag },
            tagName = tag,
            notes = json.optString("body").trim(),
            apkUrl = apkUrl,
            apkSizeBytes = apkSize,
            releaseUrl = json.optString("html_url").ifBlank { RELEASES_PAGE }
        )
    }

    private suspend fun downloadApk(url: String, info: UpdateInfo): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "update").apply { if (!exists()) mkdirs() }
            // 之前下过的残包一律清掉，别把缓存越堆越大
            dir.listFiles()?.forEach { it.delete() }

            val safeVersion = info.versionName.replace(Regex("[^\\w.\\-]"), "_")
            val target = File(dir, "NoteCraft-$safeVersion.apk")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NoteCraft-Android")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("更新包响应为空")
                val total = body.contentLength()

                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                // 只往前进：即使出现并发/重试，进度条也不会往回跳
                                if (percent > lastPercent) {
                                    lastPercent = percent
                                    _state.value = UpdateState.Downloading(info, percent)
                                }
                            }
                        }
                        output.flush()
                    }
                }
            }
            target
        }

    /**
     * 拉起系统安装器。
     * @return 出错/需要用户先去开权限时的一句话
     */
    private fun launchInstaller(file: File): String? {
        // Android 8 起，装自家下载的 APK 需要用户单独授权「安装未知应用」
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return "已打开授权页：允许「安装未知应用」后，再点一次更新"
        }

        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.fold(
            onSuccess = { null },
            onFailure = { error ->
                Log.w(TAG, "拉起安装器失败", error)
                error.message ?: "拉起安装器失败"
            }
        )
    }

    companion object {
        private const val TAG = "UpdateManager"

        const val REPO = "YesNewBeeBar/Android-NoteCraft"
        const val RELEASES_PAGE =
            "https://github.com/YesNewBeeBar/Android-NoteCraft/releases"

        /**
         * 自动检查的间隔。取 10 分钟：每次打开 App 都会真查一次，
         * 又不会在反复切前后台时把 GitHub 的每小时配额打光。
         */
        private const val AUTO_CHECK_GAP_MS = 10 * 60 * 1000L
    }
}
