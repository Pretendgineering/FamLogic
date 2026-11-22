package io.github.pretendgineering.famlogic

/**
 * Map styles anyone can pick in Settings > Map (this phone only). All are free
 * image tiles drawn from OpenStreetMap data that need no account or key.
 * "Server default" uses whatever the server's admin configured.
 */
data class MapStyle(
    val id: String,
    val title: String,
    val description: String,
    val url: String,
    val attribution: String,
    val maxZoom: Int = 19,
)

object MapStyles {
    const val SERVER = "server"

    val all = listOf(
        MapStyle(SERVER, "Server default", "Whatever your server's admin chose.", "", ""),
        MapStyle(
            "osm", "OpenStreetMap", "The familiar, detailed map.",
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png", "© OpenStreetMap contributors",
        ),
        MapStyle(
            "hot", "Humanitarian", "Softer colours with fewer labels, so people stand out.",
            "https://{s}.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png",
            "© OpenStreetMap contributors · tiles by HOT, hosted by OSM France",
        ),
        MapStyle(
            "topo", "Topographic", "Contours and terrain, for hikes and trips.",
            "https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png",
            "© OpenStreetMap contributors, SRTM · © OpenTopoMap (CC-BY-SA)", maxZoom = 17,
        ),
    )

    fun byId(id: String) = all.find { it.id == id } ?: all.first()

    /**
     * The tiles to load. [dim] means "this is a light map shown in dark mode":
     * the map darkens it a little so it doesn't glare.
     */
    data class Tiles(val url: String, val attribution: String, val maxZoom: Int, val dim: Boolean)

    fun resolve(id: String, dark: Boolean, serverUrl: String, serverDark: String, serverAttribution: String, dimAtNight: Boolean = true): Tiles {
        val s = byId(id)
        if (s.id == SERVER) {
            val hasDark = serverDark.isNotEmpty() && serverDark != serverUrl
            return Tiles(if (dark && hasDark) serverDark else serverUrl, serverAttribution, 19, dim = dimAtNight && dark && !hasDark)
        }
        return Tiles(s.url, s.attribution, s.maxZoom, dim = dimAtNight && dark)
    }
}
