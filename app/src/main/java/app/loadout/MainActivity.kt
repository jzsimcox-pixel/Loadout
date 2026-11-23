package app.loadout

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import app.loadout.data.Recipes
import app.loadout.ui.LoadoutApp

class MainActivity : ComponentActivity() {
    /** Bumped every time the app returns to the foreground, so screens can re-check state. */
    private val resumeCount = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val catalog = Recipes.parse(assets.open("recipes.json").bufferedReader().use { it.readText() })
        setContent {
            LoadoutApp(catalog = catalog, resumeCount = resumeCount.intValue)
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount.intValue += 1
    }
}
