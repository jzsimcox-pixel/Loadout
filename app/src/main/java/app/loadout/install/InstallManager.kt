package app.loadout.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import app.loadout.data.Emulator
import app.loadout.storage.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

sealed interface InstallState {
    data object Idle : InstallState
    data object Checking : InstallState
    data class Downloading(val percent: Int) : InstallState
    data object WaitingForApproval : InstallState
    data class Installed(val version: String) : InstallState
    data class Failed(val message: String) : InstallState

    val busy: Boolean get() = this is Checking || this is Downloading || this is WaitingForApproval
}

/**
 * Downloads an emulator from its GitHub release and hands it to Android to install.
 * Android always shows its own confirmation prompt; Loadout never installs silently here.
 */
object InstallManager {
    /** Current state per emulator name. Read by the screens. */
    val states = mutableStateMapOf<String, InstallState>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private fun set(name: String, state: InstallState) {
        main.post { states[name] = state }
    }

    private fun installedInfo(context: Context, packageId: String): PackageInfo? =
        try {
            context.packageManager.getPackageInfo(packageId, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    /** Updates the state from what is installed, without downloading anything. */
    fun refresh(context: Context, emulator: Emulator) {
        if (states[emulator.name]?.busy == true) return
        val packageId = Prefs.loadPackageId(context, emulator.name)
        val info = packageId?.let { installedInfo(context, it) }
        states[emulator.name] =
            if (info != null) InstallState.Installed(info.versionName ?: "unknown") else InstallState.Idle
    }

    fun install(context: Context, emulator: Emulator) {
        val source = emulator.source ?: return
        if (states[emulator.name]?.busy == true) return
        val app = context.applicationContext
        states[emulator.name] = InstallState.Checking
        scope.launch {
            try {
                val release = GitHubReleases.parse(Net.getText(GitHubReleases.latestUrl(source.repo)))
                val asset = GitHubReleases.pickAsset(release.assets, source.assets, Build.VERSION.SDK_INT)
                    ?: throw IllegalStateException("The latest release has no download that fits this device.")

                set(emulator.name, InstallState.Downloading(0))
                val file = File(File(app.cacheDir, "downloads"), asset.name)
                Net.download(asset.url, file) { percent ->
                    set(emulator.name, InstallState.Downloading(percent))
                }

                val archive = app.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                    ?: throw IllegalStateException("The downloaded file is not a valid app.")
                val packageId = archive.packageName
                Prefs.savePackageId(app, emulator.name, packageId)

                val existing = installedInfo(app, packageId)
                if (existing != null && existing.longVersionCode >= archive.longVersionCode) {
                    file.delete()
                    set(emulator.name, InstallState.Installed(existing.versionName ?: "unknown"))
                    return@launch
                }

                set(emulator.name, InstallState.WaitingForApproval)
                commit(app, file, emulator.name, packageId)
            } catch (error: Exception) {
                set(emulator.name, InstallState.Failed(error.message ?: "Something went wrong."))
            }
        }
    }

    private fun commit(context: Context, file: File, emulatorName: String, packageId: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageId)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { output ->
                file.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val intent = Intent(context, InstallReceiver::class.java)
                .setAction(InstallReceiver.ACTION)
                .putExtra(InstallReceiver.EXTRA_EMULATOR, emulatorName)
                .putExtra(InstallReceiver.EXTRA_PACKAGE, packageId)
                .putExtra(InstallReceiver.EXTRA_FILE, file.absolutePath)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    internal fun finished(context: Context, emulatorName: String, packageId: String?, error: String?) {
        if (error != null) {
            set(emulatorName, InstallState.Failed(error))
            return
        }
        val info = packageId?.let { installedInfo(context, it) }
        set(emulatorName, InstallState.Installed(info?.versionName ?: "unknown"))
    }

    internal fun waiting(emulatorName: String) = set(emulatorName, InstallState.WaitingForApproval)
}

/** Receives Android's answer about an install: needs approval, succeeded, or failed. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val emulator = intent.getStringExtra(EXTRA_EMULATOR) ?: return
        val packageId = intent.getStringExtra(EXTRA_PACKAGE)
        val filePath = intent.getStringExtra(EXTRA_FILE)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val prompt = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (prompt != null) {
                    InstallManager.waiting(emulator)
                    context.startActivity(prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    InstallManager.finished(context, emulator, packageId, "Android did not show the install prompt.")
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                filePath?.let { File(it).delete() }
                InstallManager.finished(context, emulator, packageId, null)
            }

            else -> {
                filePath?.let { File(it).delete() }
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                InstallManager.finished(context, emulator, packageId, explain(status, detail))
            }
        }
    }

    private fun explain(status: Int, detail: String): String = when {
        status == PackageInstaller.STATUS_FAILURE_ABORTED -> "Install cancelled."
        status == PackageInstaller.STATUS_FAILURE_CONFLICT || "INCOMPATIBLE" in detail ->
            "A copy from a different source is already installed. Loadout left it alone."
        status == PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough free space to install."
        status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This build does not suit this device."
        detail.isNotBlank() -> "Install failed: $detail"
        else -> "Install failed."
    }

    companion object {
        const val ACTION = "app.loadout.INSTALL_RESULT"
        const val EXTRA_EMULATOR = "emulator"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_FILE = "file"
    }
}
