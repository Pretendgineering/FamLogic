package io.github.spasm0dic.thereabouts

/**
 * Map styles anyone can pick in Settings > Map (this phone only). All are plain
 * image tiles from free services, drawn from OpenStreetMap data. "Server default"
 * uses whatever the server's admin configured.
 */
data class MapStyle(
    val id: String,
    val title: String,
    val description: String,
    val light: String,
    /** Dark-mode tiles; null when the style has no dark version. */
    val dark: String?,
    val attribution: String,
    val maxZoom: Int = 19,
)

object MapStyles {
    private const val CARTO = "© OpenStreetMap contributors © CARTO"
    private const val CARTO_DARK = "https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png"

    const val SERVER = "server"

    val all = listOf(
        MapStyle(SERVER, "Server default", "Whatever your server's admin chose.", "", null, ""),
        MapStyle(
            "calm", "Calm", "Soft colours and readable names. Dark at night.",
            "https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png", CARTO_DARK, CARTO,
        ),
        MapStyle(
            "minimal", "Minimal", "Nearly grey, so people stand out. Dark at night.",
            "https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png", CARTO_DARK, CARTO,
        ),
        MapStyle(
            "osm", "Classic OpenStreetMap", "The familiar, detailed map. Always light.",
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png", null, "© OpenStreetMap contributors",
        ),
        MapStyle(
            "topo", "Topographic", "Contours and terrain, for hikes and trips. Always light.",
            "https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png", null,
            "© OpenStreetMap contributors, SRTM · © OpenTopoMap (CC-BY-SA)", maxZoom = 17,
        ),
    )

    fun byId(id: String) = all.find { it.id == id } ?: all.first()

    /** The tiles to load: URL template, attribution and maximum zoom. */
    data class Tiles(val url: String, val attribution: String, val maxZoom: Int)

    fun resolve(id: String, dark: Boolean, serverUrl: String, serverDark: String, serverAttribution: String): Tiles {
        val s = byId(id)
        if (s.id == SERVER) return Tiles(if (dark) serverDark.ifEmpty { serverUrl } else serverUrl, serverAttribution, 19)
        return Tiles(if (dark) s.dark ?: s.light else s.light, s.attribution, s.maxZoom)
    }
}
