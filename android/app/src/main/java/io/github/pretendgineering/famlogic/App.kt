package io.github.pretendgineering.famlogic

import android.app.Application
import org.osmdroid.config.Configuration
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // The phone's text to speech takes a moment to start: start it now, ready for reading aloud.
        Voice.warmUp(this)
        // Modules are no longer hidden per phone (tiles go in the drawer instead).
        Prefs(this).hiddenModules = emptySet()
        // osmdroid: identify ourselves to tile servers (required by the OSM tile policy)
        // and keep the tile cache inside the app's own cache folder.
        Configuration.getInstance().apply {
            userAgentValue = "FamLogic-Android/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
    }
}
