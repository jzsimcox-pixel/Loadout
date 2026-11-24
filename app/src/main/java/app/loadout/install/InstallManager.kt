package app.loadout.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
    data object NeedsPermission : InstallState
    data object Checking : InstallState
    data class Downloading(val percent: Int) : InstallState
    data object WaitingForApproval : InstallState

    /** managedBy: who keeps this copy updated when it is not Loadout, for example "the Play Store". */
    data class Installed(val version: String, val managedBy: String? = null) : InstallState
    data class Failed(val message: String) : InstallState

    /** True while Loadout is working and the user should wait. */
    val busy: Boolean get() = this is Checking || this is Downloading
}

/** An emulator found on the device. */
data class InstalledApp(
    val packageId: String,
    val version: String,
    val versionCode: Long,
    val fromPlayStore: Boolean,
)

/**
 * Downloads an emulator from its GitHub release and hands it to Android to install.
 * Android always shows its own confirmation prompt; Loadout never installs silently here.
 * An emulator that is already on the device is adopted, never replaced or duplicated.
 */
object InstallManager {
    private const val PLAY_STORE = "com.android.vending"

    /** Current state per emulator name. Read by the screens. */
    val states = mutableStateMapOf<String, InstallState>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val sessions = HashMap<String, Int>()

    private fun set(name: String, state: InstallState) {
        main.post { states[name] = state }
    }

    private fun info(context: Context, packageId: String): PackageInfo? =
        try {
            context.packageManager.getPackageInfo(packageId, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun describe(context: Context, info: PackageInfo): InstalledApp {
        val installer = try {
            context.packageManager.getInstallSourceInfo(info.packageName).installingPackageName
        } catch (_: Exception) {
            null
        }
        return InstalledApp(
            packageId = info.packageName,
            version = info.versionName ?: "unknown",
            versionCode = info.longVersionCode,
            fromPlayStore = installer == PLAY_STORE,
        )
    }

    /**
     * Looks for the emulator on the device: first by the package Loadout learned
     * earlier, then by the app's visible name. Slow, so never call it on the main thread.
     */
    fun findInstalled(context: Context, emulator: Emulator): InstalledApp? {
        Prefs.loadPackageId(context, emulator.name)?.let { known ->
            info(context, known)?.let { return describe(context, it) }
        }
        val manager = context.packageManager
        for (app in manager.getInstalledApplications(0)) {
            val label = manager.getApplicationLabel(app).toString()
            if (AppMatcher.matches(emulator, label)) {
                val found = info(context, app.packageName) ?: continue
                Prefs.savePackageId(context, emulator.name, found.packageName)
                return describe(context, found)
            }
        }
        return null
    }

    private fun installedState(app: InstalledApp, emulator: Emulator?): InstallState =
        InstallState.Installed(
            app.version,
            when {
                app.fromPlayStore -> "the Play Store"
                emulator?.updates == "self" -> "its own updater"
                else -> null
            },
        )

    /** Updates the state from what is on the device, without downloading anything. */
    fun refresh(context: Context, emulator: Emulator) {
        val current = states[emulator.name]
        if (current?.busy == true || current == InstallState.WaitingForApproval || current is InstallState.Failed) return
        val app = context.applicationContext
        scope.launch {
            val found = findInstalled(app, emulator)
            set(emulator.name, if (found != null) installedState(found, emulator) else InstallState.Idle)
        }
    }

    fun canInstallApps(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system page where the user lets Loadout install apps. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Gives up on an install that is waiting for approval. Nothing has been installed at that point. */
    fun cancel(context: Context, emulator: Emulator) {
        abandon(context, emulator.name)
        states[emulator.name] = InstallState.Idle
        refresh(context, emulator)
    }

    private fun abandon(context: Context, emulatorName: String) {
        val sessionId = synchronized(sessions) { sessions.remove(emulatorName) } ?: return
        try {
            context.packageManager.packageInstaller.abandonSession(sessionId)
        } catch (_: Exception) {
            // The session already finished or was cleaned up by Android.
        }
    }

    fun install(context: Context, emulator: Emulator) {
        val source = emulator.source?.takeIf { it.downloadable } ?: return
        if (states[emulator.name]?.busy == true) return
        val app = context.applicationContext
        if (!canInstallApps(app)) {
            states[emulator.name] = InstallState.NeedsPermission
            return
        }
        states[emulator.name] = InstallState.Checking
        scope.launch {
            try {
                abandon(app, emulator.name)
                val found = findInstalled(app, emulator)
                if (found != null && found.fromPlayStore) {
                    // The Play Store owns this copy. Installing another build would create a duplicate.
                    set(emulator.name, installedState(found, emulator))
                    return@launch
                }

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

                if (found != null && found.packageId != packageId) {
                    // Same emulator, different edition. Leave the installed one alone.
                    file.delete()
                    set(emulator.name, InstallState.Installed(found.version, "another source"))
                    return@launch
                }
                Prefs.savePackageId(app, emulator.name, packageId)

                val existing = info(app, packageId)
                if (existing != null && existing.longVersionCode >= archive.longVersionCode) {
                    file.delete()
                    set(emulator.name, installedState(describe(app, existing), emulator))
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
        synchronized(sessions) { sessions[emulatorName] = sessionId }
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
        synchronized(sessions) { sessions.remove(emulatorName) }
        if (error != null) {
            set(emulatorName, InstallState.Failed(error))
            return
        }
        val installed = packageId?.let { info(context, it) }
        set(
            emulatorName,
            if (installed != null) installedState(describe(context, installed), null) else InstallState.Installed("unknown"),
        )
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
