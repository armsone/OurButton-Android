package com.armsone.button.update

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import com.armsone.button.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, ERROR }

data class DirectUpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val automaticDownload: Boolean = true,
    val version: String? = null,
    val build: Long? = null,
    val notes: String? = null,
    val size: Long? = null,
    val downloaded: Long = 0,
    val message: String = "업데이트를 확인할 수 있어요.",
)

private data class ReleaseAsset(
    val tag: String,
    val version: String,
    val build: Long,
    val notes: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val url: String,
)

class DirectUpdateManager private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("direct_updates", Context.MODE_PRIVATE)
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val _state = MutableStateFlow(
        DirectUpdateState(automaticDownload = prefs.getBoolean(KEY_AUTOMATIC, true))
    )
    val state: StateFlow<DirectUpdateState> = _state.asStateFlow()
    private var release: ReleaseAsset? = null
    private var downloadId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
    private var observer: Job? = null

    fun start() {
        if (downloadId >= 0) observeDownload(downloadId) else check(automatic = true)
    }

    fun setAutomaticDownload(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOMATIC, enabled).apply()
        _state.value = _state.value.copy(automaticDownload = enabled)
        if (enabled && _state.value.phase == UpdatePhase.AVAILABLE && canAutoDownload()) download(automatic = true)
    }

    fun check(automatic: Boolean = false) {
        if (_state.value.phase == UpdatePhase.CHECKING || _state.value.phase == UpdatePhase.DOWNLOADING) return
        _state.value = _state.value.copy(phase = UpdatePhase.CHECKING, message = "새 버전을 확인하는 중…")
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { fetchLatestRelease() } }
                .onSuccess { candidate ->
                    if (candidate == null) {
                        release = null
                        _state.value = _state.value.copy(phase = UpdatePhase.CURRENT, message = "최신 버전을 사용 중이에요.")
                    } else {
                        release = candidate
                        _state.value = _state.value.copy(
                            phase = UpdatePhase.AVAILABLE,
                            version = candidate.version,
                            build = candidate.build.takeIf { it > 0 },
                            notes = candidate.notes,
                            size = candidate.size,
                            downloaded = 0,
                            message = "새 버전 ${candidate.version}을 받을 수 있어요.",
                        )
                        if (automatic && _state.value.automaticDownload) {
                            if (canAutoDownload()) download(automatic = true)
                            else _state.value = _state.value.copy(message = "Wi‑Fi와 충분한 배터리에서 자동으로 다운로드해요.")
                        }
                    }
                }
                .onFailure { fail("업데이트를 확인하지 못했어요. 다시 시도해 주세요.") }
        }
    }

    fun download(automatic: Boolean = false) {
        val candidate = release ?: run { check(automatic = false); return }
        if (_state.value.phase == UpdatePhase.DOWNLOADING) return
        if (automatic && !canAutoDownload()) return
        val request = DownloadManager.Request(Uri.parse(candidate.url))
            .setTitle("Button ${candidate.version}")
            .setDescription("업데이트를 다운로드하는 중")
            .setMimeType(APK_MIME)
            .setAllowedOverMetered(!automatic)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, candidate.name)
        downloadId = downloads.enqueue(request)
        prefs.edit().putLong(KEY_DOWNLOAD_ID, downloadId).apply()
        _state.value = _state.value.copy(phase = UpdatePhase.DOWNLOADING, downloaded = 0, message = "다운로드 중…")
        observeDownload(downloadId)
    }

    fun cancel() {
        if (downloadId >= 0) downloads.remove(downloadId)
        clearPending(deleteFile = true)
        _state.value = _state.value.copy(phase = UpdatePhase.AVAILABLE, downloaded = 0, message = "다운로드를 취소했어요.")
    }

    fun retry() {
        if (release == null) check(automatic = false) else download(automatic = false)
    }

    fun handoffInstaller(activity: Activity) {
        val candidate = release ?: return
        val file = destination(candidate)
        if (!file.isFile) return fail("설치 파일을 찾지 못했어요. 다시 다운로드해 주세요.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            _state.value = _state.value.copy(message = "이 앱의 설치 허용을 켠 뒤 설치를 다시 눌러 주세요.")
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun observeDownload(id: Long) {
        observer?.cancel()
        observer = scope.launch {
            while (true) {
                val snapshot = withContext(Dispatchers.IO) { query(id) }
                when (snapshot.status) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_RUNNING -> {
                        _state.value = _state.value.copy(
                            phase = UpdatePhase.DOWNLOADING,
                            downloaded = snapshot.downloaded.coerceAtLeast(0),
                            size = snapshot.total.takeIf { it > 0 } ?: _state.value.size,
                            message = if (snapshot.status == DownloadManager.STATUS_PAUSED) "다운로드가 잠시 멈췄어요. 자동으로 이어갈게요." else "다운로드 중…",
                        )
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val candidate = release ?: runCatching { withContext(Dispatchers.IO) { fetchLatestRelease() } }.getOrNull()
                        if (candidate == null) fail("다운로드 정보를 다시 확인하지 못했어요.")
                        else verifyDownloaded(candidate)
                        return@launch
                    }
                    else -> {
                        clearPending(deleteFile = true)
                        fail("다운로드하지 못했어요. 다시 시도해 주세요.")
                        return@launch
                    }
                }
                delay(500)
            }
        }
    }

    private suspend fun verifyDownloaded(candidate: ReleaseAsset) {
        _state.value = _state.value.copy(message = "파일의 안전성을 확인하는 중…")
        val error = withContext(Dispatchers.IO) {
            runCatching { validateApk(candidate, destination(candidate)) }
                .getOrElse { "설치 파일을 확인하지 못했어요. 다시 다운로드해 주세요." }
        }
        if (error != null) {
            clearPending(deleteFile = true)
            fail(error)
        } else {
            release = candidate
            prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
            downloadId = -1
            _state.value = _state.value.copy(phase = UpdatePhase.READY, downloaded = candidate.size, message = "설치 준비가 끝났어요.")
        }
    }

    private fun fetchLatestRelease(): ReleaseAsset? {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "button-Android-updater")
        }
        connection.inputStream.bufferedReader().use { reader ->
            if (connection.responseCode !in 200..299) error("GitHub ${connection.responseCode}")
            val json = JSONObject(reader.readText())
            require(!json.optBoolean("draft") && !json.optBoolean("prerelease"))
            val tag = json.getString("tag_name")
            require(tag.matches(Regex("android-v\\d+\\.\\d+\\.\\d+")))
            val version = tag.removePrefix("android-v")
            val body = json.optString("body")
            val internalCode = Regex("(?m)^Android-Version-Code:\\s*([1-9]\\d*)\\s*$").find(body)?.groupValues?.get(1)?.toLongOrNull()
                ?: error("릴리스 본문에 내부 코드가 없습니다")
            if (internalCode <= BuildConfig.VERSION_CODE || compareVersions(version, BuildConfig.VERSION_NAME) <= 0) return null
            val assets = json.getJSONArray("assets")
            val allowedName = "Button-Android-$version.apk"
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val name = asset.getString("name")
                if (name != allowedName || asset.getString("state") != "uploaded") continue
                val size = asset.getLong("size")
                val digest = asset.optString("digest")
                val url = asset.getString("browser_download_url")
                require(size > 0 && digest.matches(Regex("sha256:[0-9a-fA-F]{64}")))
                validateReleaseUrl(url, tag, name)
                return ReleaseAsset(tag, version, internalCode, body, name, size, digest.substringAfter(':').lowercase(), url)
            }
            error("허용된 APK가 없습니다")
        }
    }

    private fun validateReleaseUrl(value: String, tag: String, name: String) {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true))
        require(uri.rawQuery == null && uri.rawFragment == null)
        require(uri.path == "/armsone/$REPO/releases/download/$tag/$name")
    }

    @Suppress("DEPRECATION")
    private fun validateApk(candidate: ReleaseAsset, file: File): String? {
        if (!file.isFile || file.length() != candidate.size) return "파일 크기가 달라 다운로드를 지웠어요."
        val actualHash = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (actualHash != candidate.sha256) return "파일 확인값이 달라 설치하지 않았어요."
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: return "설치 파일을 읽을 수 없어요."
        if (archive.packageName != context.packageName) return "다른 앱의 설치 파일이라 거부했어요."
        val archiveCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(archive)
        if (archiveCode <= BuildConfig.VERSION_CODE || compareVersions(archive.versionName.orEmpty(), BuildConfig.VERSION_NAME) <= 0) return "현재 버전보다 새 파일이 아니에요."
        if (!signers(archive).any { it in signers(context.packageManager.getPackageInfo(context.packageName, flags)) }) return "앱 서명이 달라 설치하지 않았어요."
        release = candidate.copy(version = archive.versionName.orEmpty(), build = archiveCode)
        _state.value = _state.value.copy(version = archive.versionName, build = archiveCode)
        return null
    }

    @Suppress("DEPRECATION")
    private fun signers(info: android.content.pm.PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.signingCertificateHistory else info.signatures
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private data class DownloadSnapshot(val status: Int, val downloaded: Long, val total: Long)
    private fun query(id: Long): DownloadSnapshot = downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
        if (!cursor.moveToFirst()) return@use DownloadSnapshot(DownloadManager.STATUS_FAILED, 0, 0)
        DownloadSnapshot(
            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
        )
    }

    private fun canAutoDownload(): Boolean {
        val metered = context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return !metered && (charging || level < 0 || level >= 20)
    }

    private fun destination(candidate: ReleaseAsset) = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), candidate.name)
    private fun clearPending(deleteFile: Boolean) {
        observer?.cancel(); observer = null
        prefs.edit().remove(KEY_DOWNLOAD_ID).apply(); downloadId = -1
        if (deleteFile) release?.let { destination(it).delete() }
    }
    private fun fail(message: String) { _state.value = _state.value.copy(phase = UpdatePhase.ERROR, message = message) }

    companion object {
        private const val REPO = "button-Android"
        private const val API_URL = "https://api.github.com/repos/armsone/$REPO/releases/latest"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val KEY_AUTOMATIC = "automatic_download"
        private const val KEY_DOWNLOAD_ID = "download_id"
        @Volatile private var instance: DirectUpdateManager? = null
        fun get(context: Context): DirectUpdateManager = instance ?: synchronized(this) {
            instance ?: DirectUpdateManager(context.applicationContext).also { instance = it }
        }
    }
}

internal fun compareVersions(left: String, right: String): Int {
    val a = left.split('.').map { it.toIntOrNull() ?: return left.compareTo(right) }
    val b = right.split('.').map { it.toIntOrNull() ?: return left.compareTo(right) }
    repeat(maxOf(a.size, b.size)) { index ->
        val result = (a.getOrNull(index) ?: 0).compareTo(b.getOrNull(index) ?: 0)
        if (result != 0) return result
    }
    return 0
}

@Composable
fun DirectUpdateSettings(manager: DirectUpdateManager) {
    val state by manager.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("업데이트 자동 다운로드", modifier = Modifier.weight(1f))
            Switch(checked = state.automaticDownload, onCheckedChange = manager::setAutomaticDownload)
        }
        Text(state.message)
        state.size?.let { total ->
            val progress = if (total > 0) (state.downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
            if (state.phase == UpdatePhase.DOWNLOADING) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("${state.version ?: "-"} · 빌드 ${state.build ?: "확인 예정"} · ${formatBytes(total)}")
        }
        state.notes?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 4) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.phase) {
                UpdatePhase.AVAILABLE -> Button(onClick = { manager.download() }) { Text("다운로드") }
                UpdatePhase.DOWNLOADING -> Button(onClick = manager::cancel) { Text("취소") }
                UpdatePhase.READY -> Button(onClick = { activity?.let(manager::handoffInstaller) }) { Text("설치") }
                UpdatePhase.ERROR -> Button(onClick = manager::retry) { Text("다시 시도") }
                else -> Unit
            }
            Button(enabled = state.phase != UpdatePhase.CHECKING && state.phase != UpdatePhase.DOWNLOADING, onClick = { manager.check() }) {
                Text("업데이트 확인")
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun formatBytes(bytes: Long): String = String.format(Locale.KOREA, "%.1f MB", bytes / 1_048_576.0)
