package app.loadout.install

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import app.loadout.data.ThemeOption
import app.loadout.storage.Storage
import app.loadout.storage.ThemeFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

sealed interface ThemeState {
    data object Idle : ThemeState
    data class Downloading(val megabytes: Int) : ThemeState
    data object Unpacking : ThemeState
    data class Done(val name: String) : ThemeState
    data class Failed(val message: String) : ThemeState

    val working: Boolean get() = this is Downloading || this is Unpacking
}

/** Downloads an ES-DE theme from its GitHub page into ES-DE's themes folder. */
object ThemeInstaller {
    val state = mutableStateOf<ThemeState>(ThemeState.Idle)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private fun set(value: ThemeState) {
        main.post { state.value = value }
    }

    fun install(context: Context, option: ThemeOption, onDone: () -> Unit) {
        if (state.value.working) return
        val app = context.applicationContext
        state.value = ThemeState.Downloading(0)
        scope.launch {
            var temp: File? = null
            try {
                val themes = ThemeFiles.themesDir(Storage.volumeRoots(app))
                val folderName = option.repo.substringAfter('/')
                val target = File(themes, folderName)
                if (!target.isDirectory) {
                    val zip = File(File(app.cacheDir, "downloads"), "$folderName.zip")
                    Net.download(
                        url = "https://api.github.com/repos/${option.repo}/zipball",
                        target = zip,
                        onBytes = { bytes -> set(ThemeState.Downloading((bytes / 1_000_000).toInt())) },
                        onProgress = {},
                    )
                    set(ThemeState.Unpacking)
                    // Unpack beside the final place, then rename, so a half-unpacked theme is never left in ES-DE's list.
                    val staging = File(themes, "$folderName.loadout-tmp")
                    temp = staging
                    staging.deleteRecursively()
                    ThemeFiles.unzip(zip, staging)
                    zip.delete()
                    if (!staging.renameTo(target)) throw IOException("Could not finish installing the theme.")
                    temp = null
                }
                set(ThemeState.Done(option.name))
                main.post(onDone)
            } catch (error: Exception) {
                temp?.deleteRecursively()
                set(ThemeState.Failed(error.message ?: "Something went wrong."))
            }
        }
    }
}
