package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.*
import dev.brahmkshatriya.echo.common.helpers.*
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MatchingLifecycleTest {
    private val song = Track("catalog-song", "Song", artists = listOf(Artist("artist", "Artist")))
    private class Client(
        val load: suspend (Track, Boolean) -> Track,
        val search: suspend (String) -> Feed<Shelf>
    ) : ExtensionClient, TrackClient, SearchFeedClient {
        override fun setSettings(settings: Settings) = Unit
        override suspend fun getSettingItems(): List<Setting> = emptyList()
        override suspend fun loadTrack(track: Track, isDownload: Boolean) = load(track, isDownload)
        override suspend fun loadSearchFeed(query: String) = search(query)
        override suspend fun loadFeed(track: Track): Feed<Shelf>? = null
        override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media = error("Unused")
    }
    private fun feed() = Feed<Shelf>(emptyList()) { Feed.Data(PagedData.Single {
        listOf(Shelf.Lists.Tracks("songs", "Songs", listOf(song.copy(id = "provider-song"))))
    }) }
    private fun extension(provider: Client, original: Track = song): CombineExtension {
        val meta = Client({ _, _ -> original.copy(streamables = listOf(Streamable.server("catalog-stream", 128))) }, { feed() })
        fun wrap(id: String, client: Client) = MusicExtension(
            Metadata("Client", "", ImportType.values().first(), ExtensionType.values().first(), id, id, "1", "", "Author"),
            Injectable<ExtensionClient>({ client }, emptyList()))
        return CombineExtension().apply {
            setSettings(MockedSettings().apply {
                putString(CombineExtension.KEY_METADATA_EXT, "catalog")
                putString(CombineExtension.KEY_STREAM_EXT, "provider")
            })
            setMusicExtensions(listOf(wrap("catalog", meta), wrap("provider", provider)))
        }
    }
    @Test fun downloadIntentReachesTheMatchingProvider() = runBlocking {
        var requested: Boolean? = null
        val provider = Client({ track, download -> requested = download; track.copy(streamables = listOf(Streamable.server("audio", 320))) }, { feed() })
        val result = extension(provider).loadTrack(song, true)
        assertEquals(true, requested)
        assertTrue(result.servers.any { it.extras[RememberedSource.PROVIDER] == "provider" })
    }
    @Test fun searchCancellationIsNotTurnedIntoAFallback() = runBlocking {
        val provider = Client({ _, _ -> error("Unused") }, { throw CancellationException("fixture") })
        assertTrue(runCatching { extension(provider).loadTrack(song, false) }.exceptionOrNull() is CancellationException)
    }
    @Test fun directIsrcCancellationDoesNotStartAnotherSearch() = runBlocking {
        var searches = 0
        val provider = Client({ _, _ -> throw CancellationException("fixture") }, { searches++; feed() })
        assertTrue(runCatching { extension(provider, song.copy(isrc = "USAAA2600001")).loadTrack(song, false) }.exceptionOrNull() is CancellationException)
        assertEquals(0, searches)
    }
    @Test fun slowProviderDoesNotHoldTheOtherSourcesIndefinitely() = runTest {
        val provider = Client({ track, _ -> track }, { Feed(emptyList()) { Feed.Data(PagedData.Single { delay(60_000); emptyList<Shelf>() }) } })
        val result = extension(provider).loadTrack(song, false)
        assertTrue(result.servers.any { it.extras[RememberedSource.PROVIDER] == "catalog" })
        assertEquals(12_000L, testScheduler.currentTime)
    }
}
