package app.loadout.debug

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationManagerCompat
import app.loadout.storage.EsdePlanner
import app.loadout.storage.FileDoor
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

sealed interface LinkResult {
    data object Ready : LinkResult

    /** Wireless debugging is on but has never been paired with Loadout. */
    data object NeedsPairing : LinkResult

    /** Nothing answered: wireless debugging is off, or the device is not on Wi-Fi. */
    data object NotFound : LinkResult
    data class Failed(val message: String) : LinkResult
}

sealed interface PairState {
    data object Idle : PairState

    /** Waiting for the user to open Android's pairing code screen. */
    data object Searching : PairState

    /** The code screen is open. Loadout's notification is asking for the code. */
    data object CodeNeeded : PairState
    data object Pairing : PairState
    data object Paired : PairState
    data class Failed(val message: String) : PairState
}

/**
 * Loadout's connection to Android's own wireless debugging, used to reach the settings folders
 * Android keeps closed to other apps. Everything that talks to the device blocks, so call it
 * off the main thread.
 */
object DebugLink {
    private const val PREFS = "loadout"
    private const val KEY_PAIRED = "link_paired"
    private const val WIRELESS_SETTING = "adb_wifi_enabled"
    private const val END = "__LOADOUT_END__"

    /** Written by the pairing service, read by the setup screen. */
    val pairing = mutableStateOf<PairState>(PairState.Idle)

    /**
     * True when wireless debugging was switched on for Loadout, by Loadout or by the user
     * following its guide. Only then does Loadout switch it off again afterwards.
     */
    @Volatile
    var ownsWireless = false

    fun wasPaired(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAIRED, false)

    fun markPaired(context: Context, paired: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PAIRED, paired).apply()
    }

    fun isConnected(context: Context): Boolean =
        try {
            LoadoutAdb.get(context).isConnected
        } catch (_: Throwable) {
            false
        }

    // ------------------------------------------------------------ device state

    fun developerOptionsOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    fun onWifi(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /** Null when Android won't say. */
    fun wirelessDebuggingOn(context: Context): Boolean? =
        try {
            Settings.Global.getInt(context.contentResolver, WIRELESS_SETTING, 0) == 1
        } catch (_: Exception) {
            null
        }

    /** True once Loadout has given itself the right to flip the wireless debugging switch. */
    fun canToggleWireless(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun setWirelessDebugging(context: Context, on: Boolean): Boolean {
        if (!canToggleWireless(context)) return false
        return try {
            val done = Settings.Global.putInt(context.contentResolver, WIRELESS_SETTING, if (on) 1 else 0)
            if (done) ownsWireless = on
            done
        } catch (_: Exception) {
            false
        }
    }

    fun checks(context: Context, error: String? = null): LinkChecks =
        LinkChecks(
            developer = developerOptionsOn(context),
            wifi = onWifi(context),
            wireless = wirelessDebuggingOn(context),
            notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            paired = wasPaired(context),
            connected = isConnected(context),
            error = error,
        )

    // -------------------------------------------------------------- connecting

    /**
     * Finds wireless debugging on this device and connects. When Loadout is allowed to, it
     * switches wireless debugging on first.
     */
    fun connect(context: Context, searchMillis: Long = 8000): LinkResult {
        val app = context.applicationContext
        val manager = try {
            LoadoutAdb.get(app)
        } catch (e: Throwable) {
            return LinkResult.Failed("Loadout couldn't create its key: ${describe(e)}")
        }
        if (manager.isConnected) return LinkResult.Ready
        if (wirelessDebuggingOn(app) == false && wasPaired(app)) setWirelessDebugging(app, true)
        return try {
            if (manager.connectTls(app, searchMillis)) verify(app) else LinkResult.Failed("Android refused the connection.")
        } catch (_: AdbPairingRequiredException) {
            LinkResult.NeedsPairing
        } catch (_: InterruptedException) {
            LinkResult.NotFound
        } catch (e: Throwable) {
            quietly { manager.disconnect() }
            when {
                // A key Android no longer knows shows up as a failed handshake.
                e is javax.net.ssl.SSLException -> LinkResult.NeedsPairing
                e.message.orEmpty().contains("valid host address") -> LinkResult.NotFound
                else -> LinkResult.Failed(describe(e))
            }
        }
    }

    /** Checks the connection carries text unchanged, then lets Loadout manage the switch itself. */
    private fun verify(context: Context): LinkResult {
        val probe = try {
            run(context, "printf 'a\\nb'")
        } catch (e: Throwable) {
            return LinkResult.Failed("Connected, but a test command failed: ${describe(e)}")
        }
        if (probe != "a\nb") {
            quietly { LoadoutAdb.get(context).disconnect() }
            return LinkResult.Failed("This device alters text sent over the connection, so Loadout won't edit files through it.")
        }
        markPaired(context, true)
        if (!canToggleWireless(context)) {
            quietly { run(context, "pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}") }
        }
        return LinkResult.Ready
    }

    /**
     * Closes the connection. Wireless debugging is switched off too when it was switched on
     * for Loadout and Loadout is allowed to.
     */
    fun finish(context: Context) {
        val app = context.applicationContext
        if (isConnected(app)) quietly { LoadoutAdb.get(app).disconnect() }
        if (ownsWireless) setWirelessDebugging(app, false)
    }

    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // Best effort.
        }
    }

    private fun describe(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    // ---------------------------------------------------------------- commands

    fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    /** Runs a command with the raw service, which passes bytes through untouched. */
    private fun raw(context: Context, command: String): ByteArray {
        val stream = LoadoutAdb.get(context.applicationContext).openStream("exec:$command")
        val out = ByteArrayOutputStream()
        try {
            val input = stream.openInputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = try {
                    input.read(buffer)
                } catch (_: IOException) {
                    // The library reports the normal end of a stream this way. A cut-off
                    // transfer looks the same, which is why callers check an end marker or a size.
                    -1
                }
                if (count < 0) break
                out.write(buffer, 0, count)
            }
        } finally {
            quietly { stream.close() }
        }
        return out.toByteArray()
    }

    /** Runs a command and returns what it printed. Throws when the output was cut short. */
    fun run(context: Context, command: String): String {
        val text = String(raw(context, "$command; printf '%s' $END"), Charsets.UTF_8)
        if (!text.endsWith(END)) throw IOException("The connection dropped before the command finished.")
        return text.removeSuffix(END)
    }

    fun isDir(context: Context, path: String): Boolean =
        run(context, "[ -d ${quote(path)} ] && printf Y || printf N") == "Y"

    /**
     * Reads a file. Returns null when it does not exist. The file's size is sent ahead of its
     * contents and checked, so a transfer that was cut short is never mistaken for the file.
     */
    fun read(context: Context, path: String): String? {
        val q = quote(path)
        val bytes = raw(context, "if [ -f $q ]; then printf 'Y\\n'; wc -c < $q; cat $q; else printf 'N\\n'; fi")
        val text = String(bytes, Charsets.ISO_8859_1)
        if (text.startsWith("N\n")) return null
        if (!text.startsWith("Y\n")) throw IOException("Unexpected reply while reading $path")
        val sizeEnd = text.indexOf('\n', 2)
        val size = if (sizeEnd > 0) text.substring(2, sizeEnd).trim().toIntOrNull() else null
        if (size == null || bytes.size - (sizeEnd + 1) != size) {
            throw IOException("Reading $path was cut short.")
        }
        return String(bytes, sizeEnd + 1, size, Charsets.UTF_8)
    }

    /**
     * Replaces the contents of a file that already exists, keeping the file itself so it stays
     * owned by its emulator. The text goes through a staging file Loadout owns, and the result
     * is read back and compared.
     */
    fun overwrite(context: Context, path: String, text: String) {
        val app = context.applicationContext
        val dir = app.getExternalFilesDir("staging") ?: throw IOException("No staging folder.")
        val staged = File(dir, "pending.tmp")
        staged.writeText(text)
        try {
            val q = quote(path)
            val backup = quote(path + EsdePlanner.BACKUP_SUFFIX)
            val result = run(app, "[ -e $backup ] || cp $q $backup; [ -e $backup ] && cat ${quote(staged.path)} > $q && printf OK")
            if (result != "OK") throw IOException("Android wouldn't let the file be written: ${result.take(200)}")
            if (read(app, path) != text) throw IOException("The file didn't read back the same after writing.")
        } finally {
            staged.delete()
        }
    }

    /** File access through the connection, for the folders Android keeps closed. */
    fun door(context: Context): FileDoor {
        val app = context.applicationContext
        return object : FileDoor {
            override val createsFiles = false
            override fun isDir(path: String) = this@DebugLink.isDir(app, path)
            override fun read(path: String) = this@DebugLink.read(app, path)
            override fun write(path: String, text: String) = overwrite(app, path, text)
        }
    }
}
