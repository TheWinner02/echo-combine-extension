package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import java.net.URLDecoder
import java.net.URLEncoder

/** Optional Echo v1 hints. Extensions built against the existing SDK remain binary-compatible. */
internal object RememberedSource {
    const val CATALOG = "echo.source.v1.catalog"
    const val ORIGINAL = "echo.source.v1.original"
    const val PROVIDER = "echo.source.v1.provider"
    const val TRACK = "echo.source.v1.track"
    private const val HINT = "echo.source.v1.preferred."
    data class Choice(val provider: String, val track: String)

    fun choice(track: Track, catalog: String): Choice? {
        val raw = track.extras[HINT + URLEncoder.encode(catalog, "UTF-8")] ?: return null
        val parts = raw.split('/', limit = 2)
        if (parts.size != 2) return null
        return runCatching {
            Choice(URLDecoder.decode(parts[0], "UTF-8"), URLDecoder.decode(parts[1], "UTF-8"))
        }.getOrNull()?.takeIf { it.provider.isNotBlank() && it.track.isNotBlank() }
    }
    fun tag(stream: Streamable, provider: String, track: String) = stream.copy(extras = stream.extras +
        mapOf("stream_ext_id" to provider, PROVIDER to provider, TRACK to track))

    fun metadata(track: Track, original: Track, catalog: String) = track.copy(extras =
        track.extras.filterKeys { !it.startsWith(HINT) } + mapOf(CATALOG to catalog,
            ORIGINAL to (original.extras[ORIGINAL] ?: original.id)))
}
