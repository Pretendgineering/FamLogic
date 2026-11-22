package io.github.pretendgineering.famlogic

import android.app.Application
import org.osmdroid.config.Configuration
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // osmdroid: identify ourselves to tile servers (required by the OSM tile policy)
        // and keep the tile cache inside the app's own cache folder.
        Configuration.getInstance().apply {
            userAgentValue = "FamLogic-Android/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
    }
}
