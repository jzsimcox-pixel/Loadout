package app.loadout.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.loadout.MainActivity
import io.github.muntashirakon.adb.android.AdbMdns
import io.github.muntashirakon.adb.android.AndroidUtils
import java.util.concurrent.Executors

/**
 * Pairs Loadout with Android's wireless debugging.
 *
 * Android only shows the pairing code while its own code screen is open, and closes that
 * screen as soon as another app comes to the front. So Loadout cannot ask for the code on its
 * own screen. Instead this service waits for the code screen to appear and then asks for the
 * code in a notification, which the user can answer without leaving Android's screen.
 */
class PairingService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var finder: AdbMdns? = null

    @Volatile
    private var port = -1

    @Volatile
    private var busy = false

    private val giveUp = Runnable {
        if (DebugLink.pairing.value != PairState.Paired) {
            DebugLink.pairing.value = PairState.Failed("Pairing timed out. Start it again from Loadout.")
        }
        finish(keepNotification = false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CODE -> {
                val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString().orEmpty()
                submit(typed.filter(Char::isDigit))
            }
            ACTION_STOP -> {
                DebugLink.pairing.value = PairState.Idle
                finish(keepNotification = false)
            }
            else -> begin()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        createChannel()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, searching(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // Some Android versions refuse this kind of background work. Say so instead of crashing.
            DebugLink.pairing.value = PairState.Failed("Android wouldn't let Loadout wait for the code (${e.javaClass.simpleName}).")
            stopSelf()
            return
        }
        DebugLink.pairing.value = PairState.Searching
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, TIMEOUT_MS)
        if (finder == null) {
            finder = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, found ->
                port = found
                val settled = busy || DebugLink.pairing.value == PairState.Paired
                if (!settled && found > 0) {
                    DebugLink.pairing.value = PairState.CodeNeeded
                    show(askForCode(null))
                } else if (!settled) {
                    DebugLink.pairing.value = PairState.Searching
                    show(searching("The code screen closed. Open it again."))
                }
            }.also { it.start() }
        }
    }

    private fun submit(code: String) {
        val target = port
        if (code.length != 6) {
            show(askForCode("That wasn't 6 digits. Try again."))
            return
        }
        if (target <= 0) {
            show(searching("The code screen closed. Open it again."))
            return
        }
        busy = true
        DebugLink.pairing.value = PairState.Pairing
        show(working())
        worker.execute {
            val error = try {
                LoadoutAdb.get(this).pair(AndroidUtils.getHostIpAddress(this), target, code)
                null
            } catch (e: Throwable) {
                e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            }
            busy = false
            if (error == null) {
                DebugLink.markPaired(this, true)
                DebugLink.pairing.value = PairState.Paired
                main.post { finish(keepNotification = true) }
            } else {
                DebugLink.pairing.value = PairState.Failed(error)
                show(if (port > 0) askForCode("That didn't work. Check the code and try again.") else searching("That didn't work. Open the code screen again."))
            }
        }
    }

    private fun finish(keepNotification: Boolean) {
        main.removeCallbacks(giveUp)
        try {
            finder?.stop()
        } catch (_: Exception) {
            // Discovery may already have stopped.
        }
        finder = null
        if (keepNotification) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            show(done())
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(giveUp)
        try {
            finder?.stop()
        } catch (_: Exception) {
            // Already stopped.
        }
        worker.shutdown()
        super.onDestroy()
    }

    // ----------------------------------------------------------- notifications

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Wireless debugging pairing", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Asks for the pairing code while Android's code screen is open."
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun show(notification: Notification) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notifications were switched off. The setup screen says so.
        }
    }

    private fun base(title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

    private fun stopAction(): NotificationCompat.Action {
        val intent = Intent(this, PairingService::class.java).setAction(ACTION_STOP)
        val pending = PendingIntent.getService(this, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(0, "Cancel", pending).build()
    }

    private fun searching(note: String?): Notification =
        base(
            "Loadout is waiting for the pairing code",
            (note?.let { "$it " } ?: "") + "In Developer options, open Wireless debugging and tap \"Pair device with pairing code\".",
        ).setOngoing(true).setOnlyAlertOnce(note == null).addAction(stopAction()).build()

    private fun askForCode(note: String?): Notification {
        val intent = Intent(this, PairingService::class.java).setAction(ACTION_CODE)
        // Mutable so Android can attach what the user types.
        val pending = PendingIntent.getService(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val input = RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build()
        val action = NotificationCompat.Action.Builder(0, "Enter pairing code", pending).addRemoteInput(input).build()
        return base(
            "Enter the pairing code here",
            (note?.let { "$it " } ?: "") + "Tap \"Enter pairing code\" below and type the 6 digits Android is showing. Leave Android's code screen open.",
        ).setOngoing(true).addAction(action).addAction(stopAction()).build()
    }

    private fun working(): Notification =
        base("Pairing…", "Checking the code with Android.").setOngoing(true).setProgress(0, 0, true).build()

    private fun done(): Notification {
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(this, 3, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return base("Paired with Loadout", "Tap to go back to Loadout.")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        private const val CHANNEL = "pairing"
        private const val NOTIFICATION_ID = 41
        private const val KEY_CODE = "code"
        private const val ACTION_CODE = "app.loadout.debug.CODE"
        private const val ACTION_STOP = "app.loadout.debug.STOP"
        private const val TIMEOUT_MS = 5 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PairingService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PairingService::class.java).setAction(ACTION_STOP))
        }
    }
}
