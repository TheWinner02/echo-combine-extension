package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.helpers.Injectable
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class RememberedSourceTest {
    private val original = Track("metadata-song", "Song", artists = listOf(Artist("artist", "Artist")))
    private fun recording(id: String) = original.copy(id = id, streamables = listOf(
        Streamable("same-stream-id", 320, Streamable.MediaType.Server)))

    private class Client(val songs: Map<String, Track>, val searchResult: Track) : ExtensionClient, TrackClient, SearchFeedClient {
        var searches = 0
        val loaded = mutableListOf<String>()
        override fun setSettings(settings: Settings) = Unit
        override suspend fun getSettingItems(): List<Setting> = emptyList()
        override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
            loaded.add(track.id)
            return songs[track.id] ?: error("Recording unavailable")
        }
        override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
            searches++
            return Feed(emptyList()) { Feed.Data(PagedData.Single {
                listOf(Shelf.Lists.Tracks("songs", "Songs", listOf(searchResult)))
            }) }
        }
        override suspend fun loadFeed(track: Track): Feed<Shelf>? = null
        override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media =
            Streamable.Media.Server(emptyList(), false)
    }
    private fun extension(id: String, client: Client) = MusicExtension(
        Metadata("Client", "", ImportType.values().first(), ExtensionType.values().first(), id, id, "1", "", "Author"),
        Injectable<ExtensionClient>({ client }, emptyList()))

    private fun fixture(available: Boolean = true): Pair<CombineExtension, Client> {
        val meta = Client(mapOf(original.id to recording(original.id)), original)
        val fuzzy = recording("different-recording")
        val provider = Client(buildMap {
            put(fuzzy.id, fuzzy)
            if (available) put("chosen/recording", recording("chosen/recording"))
        }, fuzzy)
        val combine = CombineExtension()
        combine.setSettings(MockedSettings().apply {
            putString(CombineExtension.KEY_METADATA_EXT, "catalog")
            putString(CombineExtension.KEY_STREAM_EXT, "provider")
        })
        combine.setMusicExtensions(listOf(extension("catalog", meta), extension("provider", provider)))
        return combine to provider
    }
    private fun hinted() = original.copy(extras = mapOf(
        "echo.source.v1.preferred.catalog" to "provider/" + URLEncoder.encode("chosen/recording", "UTF-8")))

    @Test fun exactChoiceBypassesSearchAndSurvivesStreamIdCollisions() = runBlocking {
        val (combine, provider) = fixture()
        val result = combine.loadTrack(hinted(), false)
        val chosen = result.servers.single { it.extras[RememberedSource.PROVIDER] == "provider" }
        assertEquals("chosen/recording", chosen.extras[RememberedSource.TRACK])
        assertEquals(0, provider.searches)
        assertEquals(listOf("chosen/recording"), provider.loaded)
        assertTrue(result.servers.any { it.extras[RememberedSource.PROVIDER] == "catalog" })
        assertEquals("catalog", result.extras[RememberedSource.CATALOG])
        assertEquals(original.id, result.extras[RememberedSource.ORIGINAL])
        assertFalse(result.extras.keys.any { it.startsWith("echo.source.v1.preferred.") })
    }
    @Test fun missingChoiceNeverFallsBackToAnotherRecordingFromThatProvider() = runBlocking {
        val (combine, provider) = fixture(available = false)
        val result = combine.loadTrack(hinted(), false)
        assertFalse(result.servers.any { it.extras[RememberedSource.PROVIDER] == "provider" })
        assertEquals(0, provider.searches)
    }
    @Test fun automaticModeStillUsesNormalMatching() = runBlocking {
        val (combine, provider) = fixture()
        val result = combine.loadTrack(original, false)
        assertEquals("different-recording", result.servers.first {
            it.extras[RememberedSource.PROVIDER] == "provider"
        }.extras[RememberedSource.TRACK])
        assertEquals(1, provider.searches)
    }
    @Test fun hintFromAnotherCatalogueDoesNotOverrideTheCurrentCatalogue() = runBlocking {
        val (combine, provider) = fixture()
        combine.loadTrack(original.copy(extras = mapOf("echo.source.v1.preferred.other" to "provider/chosen")), false)
        assertEquals(1, provider.searches)
    }
    @Test fun aLoadedSongFromAnotherCatalogueIsRejected() = runBlocking {
        val (combine, _) = fixture()
        assertTrue(runCatching {
            combine.loadTrack(original.copy(extras = mapOf(RememberedSource.CATALOG to "other")), false)
        }.isFailure)
    }
}
