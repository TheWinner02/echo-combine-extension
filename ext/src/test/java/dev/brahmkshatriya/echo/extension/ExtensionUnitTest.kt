package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.Injectable
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import dev.brahmkshatriya.echo.common.models.ImportType
import dev.brahmkshatriya.echo.common.models.Metadata
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.providers.MusicExtensionsProvider
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.system.measureTimeMillis

@OptIn(DelicateCoroutinesApi::class)
@ExperimentalCoroutinesApi
class ExtensionUnitTest {
    private val extension: ExtensionClient = CombineExtension()
    private val searchQuery = "Skrillex"

    // Mock clients for testing
    private class MockMetadataClient : ExtensionClient, SearchFeedClient, HomeFeedClient, TrackClient, AlbumClient, ArtistClient, RadioClient {
        val sampleTrack = Track(
            id = "meta_track_1",
            title = "Bangarang",
            artists = listOf(Artist("artist_1", "Skrillex")),
            album = Album("album_1", "Bangarang EP"),
            streamables = listOf(Streamable("stream_meta", 128, Streamable.MediaType.values().first()))
        )

        override fun setSettings(settings: Settings) {}
        override suspend fun getSettingItems(): List<Setting> = emptyList()

        override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
            val shelf = Shelf.Lists.Tracks("shelf_1", "Tracks", listOf(sampleTrack))
            return Feed(tabs = emptyList()) { Feed.Data(PagedData.Single { listOf(shelf) }) }
        }

        override suspend fun loadHomeFeed(): Feed<Shelf> {
            val shelf = Shelf.Lists.Tracks("shelf_home", "Home Tracks", listOf(sampleTrack))
            return Feed(tabs = emptyList()) { Feed.Data(PagedData.Single { listOf(shelf) }) }
        }

        override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = sampleTrack

        override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
            return Streamable.Media.Server(listOf(Streamable.Source.Http(NetworkRequest("http://meta.stream/play"))), merged = false)
        }

        override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

        override suspend fun loadAlbum(album: Album): Album = album
        override suspend fun loadTracks(album: Album): Feed<Track> = Feed(tabs = emptyList()) { Feed.Data(PagedData.Single { listOf(sampleTrack) }) }
        override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

        override suspend fun loadArtist(artist: Artist): Artist = artist
        override suspend fun loadFeed(artist: Artist): Feed<Shelf> = Feed(tabs = emptyList()) { Feed.Data(PagedData.Single { emptyList() }) }

        override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio {
            return Radio("radio_1", "Radio Title")
        }

        override suspend fun loadRadio(radio: Radio): Radio = radio
        override suspend fun loadTracks(radio: Radio): Feed<Track> = Feed(tabs = emptyList()) { Feed.Data(PagedData.Single { listOf(sampleTrack) }) }
    }

    private class MockStreamClient : ExtensionClient, SearchFeedClient, TrackClient {
        val sampleTrack = Track(
            id = "stream_track_1",
            title = "Bangarang",
            artists = listOf(Artist("artist_1", "Skrillex")),
            streamables = listOf(Streamable("stream_audio", 320, Streamable.MediaType.values().first()))
        )

        override fun setSettings(settings: Settings) {}
        override suspend fun getSettingItems(): List<Setting> = emptyList()

        override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
            val shelf = Shelf.Lists.Tracks("shelf_stream", "Tracks", listOf(sampleTrack))
            return Feed(tabs = emptyList()) { Feed.Data(PagedData.Single { listOf(shelf) }) }
        }

        override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = sampleTrack

        override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
            return Streamable.Media.Server(listOf(Streamable.Source.Http(NetworkRequest("http://stream.provider/audio.mp3"))), merged = false)
        }

        override suspend fun loadFeed(track: Track): Feed<Shelf>? = null
    }

    private val mainThreadSurrogate = newSingleThreadContext("UI thread")

    @Before
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
        val settings = MockedSettings().apply {
            putString(CombineExtension.KEY_METADATA_EXT, "meta_ext")
            putString(CombineExtension.KEY_STREAM_EXT, "stream_ext")
        }
        extension.setSettings(settings)

        if (extension is MusicExtensionsProvider) {
            val metaExt = MusicExtension(
                metadata = Metadata("MockMetaClass", "", ImportType.values().first(), ExtensionType.values().first(), "meta_ext", "Mock Meta", "1.0.0", "", "Author"),
                instance = Injectable(getter = { MockMetadataClient() }, injections = emptyList())
            )
            val streamExt = MusicExtension(
                metadata = Metadata("MockStreamClass", "", ImportType.values().first(), ExtensionType.values().first(), "stream_ext", "Mock Stream", "1.0.0", "", "Author"),
                instance = Injectable(getter = { MockStreamClient() }, injections = emptyList())
            )
            extension.setMusicExtensions(listOf(metaExt, streamExt))
        }

        runBlocking {
            extension.onInitialize()
            extension.onExtensionSelected()
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mainThreadSurrogate.close()
    }

    @Test
    fun testSettingsItems() = testIn("Testing Settings Items") {
        val items = extension.getSettingItems()
        assertTrue(items.isNotEmpty())
    }

    @Test
    fun testSearch() = testIn("Testing Search") {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        val feed = extension.loadSearchFeed(searchQuery)
        val data = feed.pagedDataOfFirst().loadPage(null).data
        assertTrue(data.isNotEmpty())
    }

    @Test
    fun testHomeFeed() = testIn("Testing Home Feed") {
        if (extension !is HomeFeedClient) error("HomeFeedClient is not implemented")
        val feed = extension.loadHomeFeed()
        val data = feed.pagedDataOfFirst().loadPage(null).data
        assertTrue(data.isNotEmpty())
    }

    private suspend fun searchTrack(): Track {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        val track = extension.loadSearchFeed(searchQuery).pagedDataOfFirst().loadAll()
            .firstNotNullOfOrNull {
                when (it) {
                    is Shelf.Item -> it.media as? Track
                    is Shelf.Lists.Tracks -> it.list.firstOrNull()
                    is Shelf.Lists.Items -> it.list.firstOrNull() as? Track
                    else -> null
                }
            }
        return track ?: error("Track not found")
    }

    @Test
    fun testTrackGet() = testIn("Testing Track Get") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val search = searchTrack()
        measureTimeMillis {
            val track = extension.loadTrack(search, false)
            assertNotNull(track)
            assertTrue(track.streamables.isNotEmpty())
            assertEquals("stream_ext", track.streamables.first().extras["stream_ext_id"])
        }.also { println("time : $it ms") }
    }

    @Test
    fun testTrackStream() = testIn("Testing Track Stream") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val search = searchTrack()
        val track = extension.loadTrack(search, false)
        val streamable = track.streamables.firstOrNull() ?: error("Track is not streamable")
        val stream = extension.loadStreamableMedia(streamable, false)
        assertNotNull(stream)
    }

    @Test
    fun testTrackRadio() = testIn("Testing Track Radio") {
        if (extension !is RadioClient) error("RadioClient is not implemented")
        val search = searchTrack()
        val radio = extension.radio(search, null)
        val tracks = extension.loadTracks(radio).loadAll()
        assertTrue(tracks.isNotEmpty())
    }

    @Test
    fun testAlbumGet() = testIn("Testing Album Get") {
        if (extension !is TrackClient || extension !is AlbumClient) error("Clients not implemented")
        val track = searchTrack()
        val album = track.album ?: error("Track has no album")
        val loadedAlbum = extension.loadAlbum(album)
        assertNotNull(loadedAlbum)
        val tracks = extension.loadTracks(loadedAlbum)?.loadAll()
        assertTrue(!tracks.isNullOrEmpty())
    }

    private fun testIn(title: String, block: suspend CoroutineScope.() -> Unit) = runBlocking {
        println("\n-- $title --")
        block.invoke(this)
        println("\n")
    }
}
