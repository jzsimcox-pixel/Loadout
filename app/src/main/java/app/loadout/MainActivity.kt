package app.loadout

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import app.loadout.data.Recipes
import app.loadout.ui.LoadoutApp
import java.io.File

class MainActivity : ComponentActivity() {
    /** Bumped every time the app returns to the foreground, so screens can re-check state. */
    private val resumeCount = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val catalog = Recipes.parse(assets.open("recipes.json").bufferedReader().use { it.readText() })
        val crashFile = File(filesDir, "last-crash.txt")
        if (!crashHandlerInstalled) {
            crashHandlerInstalled = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                try {
                    crashFile.writeText(Log.getStackTraceString(error).take(6000))
                } catch (_: Throwable) {
                    // Recording the crash must never get in the way of it.
                }
                previous?.uncaughtException(thread, error)
            }
        }
        val crashReport = try {
            crashFile.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        setContent {
            LoadoutApp(
                catalog = catalog,
                resumeCount = resumeCount.intValue,
                crashReport = crashReport,
                onCrashSeen = { crashFile.delete() },
            )
        }
    }

    companion object {
        private var crashHandlerInstalled = false
    }

    override fun onResume() {
        super.onResume()
        resumeCount.intValue += 1
    }
}
