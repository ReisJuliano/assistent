package io.github.assistent.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.assistent.AssistentApp
import io.github.assistent.BuildConfig
import io.github.assistent.config.ConfigStore
import io.github.assistent.core.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** `latest.json` published next to the APK by `./gradlew publishUpdate`. */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String = "",
    /** Absolute URL, or a file name relative to the manifest URL. */
    val apk: String,
    val sha256: String = "",
    val notes: String = "",
)

/**
 * Self-update without reinstalling by hand: polls a manifest (e.g. served from the dev PC on the LAN or a GitHub
 * release), downloads a newer APK, checks its SHA-256 and installs it with [PackageInstaller].
 *
 * The first update needs one confirmation tap (the app installed over ADB is not yet the "installer of record").
 * From then on, Android 12+ lets the app update itself silently (`USER_ACTION_NOT_REQUIRED`). After the update the
 * app relaunches itself (needs the "display over other apps" permission to start from the background).
 */
class Updater(
    private val context: Context,
    private val config: ConfigStore,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow("Versão ${BuildConfig.VERSION_NAME}")
    val status: StateFlow<String> = _status
    private val lock = Mutex()

    fun start() {
        scope.launch {
            delay(20_000) // let the app settle after start
            while (true) {
                val cfg = config.current.update
                if (cfg.enabled && cfg.url.isNotBlank()) runCatching { checkAndInstall(auto = true) }
                delay(cfg.checkMinutes.coerceAtLeast(1) * 60_000L)
            }
        }
    }

    /** True when Android lets this app install packages (user must allow "install unknown apps" once). */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun canRelaunch(): Boolean = Settings.canDrawOverlays(context)

    fun installPermissionIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun overlayPermissionIntent() = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Returns a human-readable result. */
    suspend fun checkAndInstall(auto: Boolean = false): String = lock.withLock {
        withContext(Dispatchers.IO) {
            val cfg = config.current.update
            if (cfg.url.isBlank()) return@withContext "Configure a URL de atualização".also { _status.value = it }
            val manifest = runCatching {
                http.newCall(Request.Builder().url(cfg.url).header("Cache-Control", "no-cache").build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    AppJson.decodeFromString(UpdateManifest.serializer(), r.body!!.string())
                }
            }.getOrElse { return@withContext "Sem acesso ao servidor de atualização (${it.message})".also { m -> _status.value = m } }

            if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                return@withContext "Atualizado (versão ${BuildConfig.VERSION_NAME})".also { if (!auto) _status.value = it }
            }
            if (!canInstall()) return@withContext "Nova versão ${manifest.versionName}: permita \"instalar apps desconhecidos\"".also { _status.value = it }

            _status.value = "Baixando versão ${manifest.versionName}…"
            val apkUrl = if (manifest.apk.startsWith("http")) manifest.apk else cfg.url.substringBeforeLast('/') + "/" + manifest.apk
            val file = File(context.cacheDir, "update.apk")
            http.newCall(Request.Builder().url(apkUrl).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext "Falha ao baixar: HTTP ${r.code}".also { _status.value = it }
                file.outputStream().use { r.body!!.byteStream().copyTo(it) }
            }
            if (manifest.sha256.isNotBlank() && !sha256(file).equals(manifest.sha256, ignoreCase = true)) {
                file.delete()
                return@withContext "Arquivo corrompido (SHA-256 não confere)".also { _status.value = it }
            }
            _status.value = "Instalando versão ${manifest.versionName}…"
            install(file)
            "Instalando versão ${manifest.versionName}"
        }
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateStatusReceiver::class.java)
            val pi = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(pi.intentSender)
        }
    }

    internal fun onStatus(message: String) { _status.value = message }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Receives the install result; asks the user once if Android requires confirmation. */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = (context.applicationContext as AssistentApp).graph.updater
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                updater.onStatus("Confirme a atualização na tela")
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT))
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> updater.onStatus("Atualizado!")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("Updater", "install failed: $msg")
                updater.onStatus("Falha na instalação: $msg")
            }
        }
    }
}
