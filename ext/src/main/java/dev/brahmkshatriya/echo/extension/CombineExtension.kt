package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.clients.HideClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SaveClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.providers.MusicExtensionsProvider
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class CombineExtension : ExtensionClient, MusicExtensionsProvider,
    HomeFeedClient, SearchFeedClient, TrackClient,
    AlbumClient, ArtistClient, PlaylistClient, PlaylistEditClient,
    SaveClient, LikeClient, ShareClient, FollowClient, HideClient, RadioClient,
    LibraryFeedClient {

    companion object {
        const val COMBINE_ID = "echo_combine"
        const val KEY_METADATA_EXT = "metadata_extension"
        const val KEY_STREAM_EXT = "stream_extension"
        const val KEY_FIRST_AVAILABLE = "first_available"
    }

    private lateinit var settings: Settings

    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    private fun getSettingString(key: String): String? {
        if (!::settings.isInitialized) return null
        return runCatching { settings.getString(key) }.getOrNull()
    }

    private fun getSettingBoolean(key: String): Boolean? {
        if (!::settings.isInitialized) return null
        return runCatching { settings.getBoolean(key) }.getOrNull()
    }

    override suspend fun getSettingItems(): List<Setting> {
        val extensions = awaitExtensions()
        val extIds = extensions.map { it.id }
        val extNames = extensions.map { it.name }

        val altIds = listOf("none") + extIds
        val altNames = listOf("None") + extNames

        val settingsList = mutableListOf<Setting>(
            SettingList(
                "Metadata Extension",
                KEY_METADATA_EXT,
                "Extension to use for metadata (search, feed, album info, etc.)",
                extNames,
                extIds,
                0
            ),
            SettingSwitch(
                "First Available Mode",
                KEY_FIRST_AVAILABLE,
                "Return only streams from the highest priority extension that finds the track",
                false
            ),
            SettingList(
                "Preferred Stream Extension",
                KEY_STREAM_EXT,
                "The primary extension to use for audio streaming",
                extNames,
                extIds,
                0
            )
        )

        var lastKey = KEY_STREAM_EXT
        val suffixes = listOf("2nd", "3rd", "4th", "5th")
        for (i in suffixes.indices) {
            val value = getSettingString(lastKey)
            if (value != null && value != "none") {
                val currentKey = "${KEY_STREAM_EXT}_${i + 1}"
                settingsList.add(
                    SettingList(
                        "${suffixes[i]} Alternative Stream Extension",
                        currentKey,
                        "Backup extension if the previous ones fail to find the track",
                        altNames,
                        altIds,
                        0
                    )
                )
                lastKey = currentKey
            } else {
                break
            }
        }

        return settingsList
    }

    // --- MusicExtensionsProvider ---

    override val requiredMusicExtensions: List<String> = emptyList()

    private val extensionsFlow = MutableStateFlow<List<MusicExtension>?>(null)

    override fun setMusicExtensions(extensions: List<MusicExtension>) {
        extensionsFlow.value = extensions.filter { it.id != COMBINE_ID }
    }

    private suspend fun awaitExtensions(): List<MusicExtension> {
        val current = extensionsFlow.value
        if (current != null) return current
        return withTimeoutOrNull(3000) {
            extensionsFlow.first { it != null }
        } ?: emptyList()
    }

    private suspend fun getMetadataExtension(): MusicExtension {
        return getSelectedExtension(KEY_METADATA_EXT)
    }

    private suspend fun getStreamExtensions(): List<MusicExtension> {
        val exts = awaitExtensions()
        if (exts.isEmpty()) return emptyList()
        val result = mutableListOf<MusicExtension>()
        val metaExt = runCatching { getMetadataExtension() }.getOrNull()

        fun findExt(id: String?): MusicExtension? {
            if (id.isNullOrEmpty() || id == "none") return null
            return exts.find { it.id == id || it.name == id }
        }

        // Legacy Migration
        val selectedSet = if (::settings.isInitialized) runCatching { settings.getStringSet(KEY_STREAM_EXT) }.getOrNull() else null
        if (!selectedSet.isNullOrEmpty()) {
            return exts.filter { it.id in selectedSet }
        }

        // Priority Chain: Preferred -> 2nd -> 3rd ...
        val preferredId = getSettingString(KEY_STREAM_EXT)
        val preferred = findExt(preferredId) ?: exts.firstOrNull()
        preferred?.let { result.add(it) }

        for (i in 1..5) {
            val id = getSettingString("${KEY_STREAM_EXT}_$i")
            findExt(id)?.let { result.add(it) } ?: break
        }

        if (metaExt != null && result.none { it.id == metaExt.id }) {
            result.add(metaExt)
        }

        return result.distinctBy { it.id }
    }

    private suspend fun getSelectedExtension(key: String): MusicExtension {
        val exts = awaitExtensions()
        if (exts.isEmpty()) throw Exception("No music extensions installed")
        val selectedValue = getSettingString(key)

        if (selectedValue == null) {
            val setValues = if (::settings.isInitialized) runCatching { settings.getStringSet(key) }.getOrNull() else null
            if (!setValues.isNullOrEmpty()) {
                val match = exts.find { it.id in setValues }
                if (match != null) return match
            }
            return exts.first()
        }

        val match = exts.find { it.id == selectedValue || it.name == selectedValue }
        if (match != null) return match

        val index = selectedValue.toIntOrNull()
        if (index != null && index in exts.indices) {
            return exts[index]
        }

        return exts.first()
    }

    @Suppress("UNCHECKED_CAST")
    private suspend inline fun <reified T> MusicExtension.client(): T {
        val inst = instance.value().getOrNull() ?: throw Exception("Extension '${name}' failed to initialize")
        return inst as? T ?: throw Exception("Extension '${name}' does not support ${T::class.simpleName}")
    }

    private suspend inline fun <reified T> MusicExtension.clientOrNull(): T? {
        return instance.value().getOrNull() as? T
    }

    override suspend fun loadHomeFeed(): Feed<Shelf> = getMetadataExtension().client<HomeFeedClient>().loadHomeFeed()

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val client = getMetadataExtension().clientOrNull<LibraryFeedClient>()
            ?: throw Exception("The selected metadata extension does not support the Library feed.")
        return client.loadLibraryFeed()
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> = getMetadataExtension().client<SearchFeedClient>().loadSearchFeed(query)

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val metaExt = getMetadataExtension()
        val metaClient = metaExt.client<TrackClient>()
        val metaTrack = try {
            metaClient.loadTrack(track, isDownload)
        } catch (e: Throwable) {
            println("CombineExtension: Metadata extension failed to load track: ${e.message}")
            track
        }

        val streamExts = getStreamExtensions()

        val allStreamables = coroutineScope {
            val deferreds = streamExts.mapIndexed { index, ext ->
                async {
                    val streamables = if (ext.id == metaExt.id) {
                        metaTrack.streamables
                    } else {
                        try {
                            findTrackInStreamExtension(metaTrack, ext)?.streamables ?: emptyList()
                        } catch (e: Throwable) {
                            println("CombineExtension: Extension ${ext.id} failed to find track: ${e.message}")
                            emptyList()
                        }
                    }
                    streamables.map { s ->
                        val existingExtras = s.extras
                        val updatedExtras = if (existingExtras.containsKey("stream_ext_id")) existingExtras else existingExtras + mapOf("stream_ext_id" to ext.id)
                        val titleWithExt = if (s.title != null) {
                            if (s.title!!.startsWith(ext.name)) s.title
                            else "${ext.name} • ${s.title}"
                        } else {
                            if (s.quality > 1) "${ext.name} • ${s.quality} kbps"
                            else "${ext.name} (Auto)"
                        }
                        s.copy(title = titleWithExt, extras = updatedExtras) to index
                    }
                }
            }
            val results = deferreds.awaitAll().flatten()
            if (results.isEmpty()) {
                metaTrack.streamables
            } else {
                results.sortedWith(compareByDescending<Pair<Streamable, Int>> { it.first.quality }.thenBy { it.second })
                    .map { it.first }
                    .distinctBy { it.id }
            }
        }

        return metaTrack.copy(streamables = allStreamables)
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        val streamExtId = streamable.extras["stream_ext_id"]
        val exts = awaitExtensions()
        val ext = if (streamExtId != null) exts.find { it.id == streamExtId } else getMetadataExtension()
        return ext?.client<TrackClient>()?.loadStreamableMedia(streamable, isDownload)
            ?: throw Exception("Extension '${streamExtId ?: "metadata"}' not found or not supporting playback")
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = getMetadataExtension().clientOrNull<TrackClient>()?.loadFeed(track)

    private suspend fun findTrackInStreamExtension(metaTrack: Track, streamExt: MusicExtension): Track? = try {
        val searchClient = streamExt.clientOrNull<SearchFeedClient>() ?: return null
        val trackClient = streamExt.clientOrNull<TrackClient>() ?: return null

        val artistName = metaTrack.artists.firstOrNull()?.name ?: ""
        val query = "${metaTrack.title} $artistName".trim()
        if (query.isEmpty()) return null

        val feed = try {
            withTimeoutOrNull(5000) {
                searchClient.loadSearchFeed(query)
            } ?: return null
        } catch (_: Throwable) { return null }

        val targetTab = feed.tabs.find { tab ->
            val tabTitle = tab.title.lowercase()
            tabTitle.contains("song") || tabTitle.contains("track") || tabTitle.contains("single")
        } ?: feed.tabs.firstOrNull()

        val shelves = try {
            loadFirstPage(feed.getPagedData(targetTab).pagedData)
        } catch (_: Throwable) {
            emptyList()
        }
        val artistNameLower = artistName.lowercase()
        val trackTitleLower = metaTrack.title.lowercase()

        var foundTrack: Track? = null
        for (shelf in shelves) {
            val candidate = findTrackInShelf(shelf, artistNameLower, trackTitleLower)
            if (candidate != null) {
                val loaded = try {
                    withTimeoutOrNull(5000) {
                        trackClient.loadTrack(candidate, false)
                    }
                } catch (_: Throwable) { null }

                if (loaded != null && loaded.streamables.isNotEmpty()) {
                    foundTrack = loaded.copy(streamables = loaded.streamables.map { s ->
                        val existingExtras = s.extras
                        val updatedExtras = if (existingExtras.containsKey("stream_ext_id")) existingExtras else existingExtras + mapOf("stream_ext_id" to streamExt.id)
                        s.copy(extras = updatedExtras)
                    })
                    break
                }
            }
        }
        foundTrack
    } catch (_: Throwable) {
        null
    }

    private fun findTrackInShelf(shelf: Shelf, artistNameLower: String, trackTitleLower: String): Track? {
        return when (shelf) {
            is Shelf.Item -> {
                val media = shelf.media
                if (media is Track && matches(media, artistNameLower, trackTitleLower)) media else null
            }
            is Shelf.Lists.Tracks -> shelf.list.firstOrNull { matches(it, artistNameLower, trackTitleLower) }
            is Shelf.Lists.Items -> shelf.list.mapNotNull { item -> item as? Track }.firstOrNull { matches(it, artistNameLower, trackTitleLower) }
            else -> null
        }
    }

    private fun matches(track: Track, artistNameLower: String, trackTitleLower: String): Boolean {
        val candidateTitle = cleanTitle(track.title)
        val targetTitle = cleanTitle(trackTitleLower)

        val titleMatch = when {
            candidateTitle == targetTitle -> true
            targetTitle.length > 3 && candidateTitle.contains(targetTitle) -> true
            candidateTitle.length > 3 && targetTitle.contains(candidateTitle) -> true
            else -> false
        }
        if (!titleMatch) return false
        if (artistNameLower.isEmpty()) return true

        val candidateArtists = track.artists.map { cleanTitle(it.name) }
        val targetArtistClean = cleanTitle(artistNameLower)

        val artistMatch = candidateArtists.any { it.contains(targetArtistClean) || targetArtistClean.contains(it) } ||
                targetArtistClean.split(" ").filter { it.length > 2 }.any { part -> candidateArtists.any { it.contains(part) } }

        return artistMatch
    }

    private fun cleanTitle(title: String): String {
        return title.lowercase()
            .replace(Regex("\\(.*\\)|\\[.*\\]"), "")
            .replace(Regex("[^a-z0-9\\s]"), "")
            .trim()
    }

    private suspend fun <T : Any> loadFirstPage(pagedData: PagedData<T>): List<T> = try { pagedData.loadPage(null).data } catch (_: Throwable) { emptyList() }

    override suspend fun loadAlbum(album: Album): Album = getMetadataExtension().client<AlbumClient>().loadAlbum(album)
    override suspend fun loadTracks(album: Album): Feed<Track>? = getMetadataExtension().clientOrNull<AlbumClient>()?.loadTracks(album)
    override suspend fun loadFeed(album: Album): Feed<Shelf>? = getMetadataExtension().clientOrNull<AlbumClient>()?.loadFeed(album)
    override suspend fun loadArtist(artist: Artist): Artist = getMetadataExtension().client<ArtistClient>().loadArtist(artist)
    override suspend fun loadFeed(artist: Artist): Feed<Shelf> = getMetadataExtension().client<ArtistClient>().loadFeed(artist)
    override suspend fun loadPlaylist(playlist: Playlist): Playlist = getMetadataExtension().client<PlaylistClient>().loadPlaylist(playlist)
    override suspend fun loadTracks(playlist: Playlist): Feed<Track> = getMetadataExtension().client<PlaylistClient>().loadTracks(playlist)
    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = getMetadataExtension().clientOrNull<PlaylistClient>()?.loadFeed(playlist)
    override suspend fun listEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.listEditablePlaylists(track) ?: emptyList()
    override suspend fun createPlaylist(title: String, description: String?): Playlist = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.createPlaylist(title, description) ?: throw Exception("Not supported")
    override suspend fun addTracksToPlaylist(playlist: Playlist, tracks: List<Track>, position: Int, previousTracks: List<Track>) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.addTracksToPlaylist(playlist, tracks, position, previousTracks) ?: throw Exception("Not supported")
    override suspend fun removeTracksFromPlaylist(playlist: Playlist, tracks: List<Track>, index: List<Int>) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.removeTracksFromPlaylist(playlist, tracks, index) ?: throw Exception("Not supported")
    override suspend fun moveTrackInPlaylist(playlist: Playlist, tracks: List<Track>, fromIndex: Int, toIndex: Int) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.moveTrackInPlaylist(playlist, tracks, fromIndex, toIndex) ?: throw Exception("Not supported")
    override suspend fun editPlaylistMetadata(playlist: Playlist, title: String, description: String?) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.editPlaylistMetadata(playlist, title, description) ?: throw Exception("Not supported")
    override suspend fun deletePlaylist(playlist: Playlist) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.deletePlaylist(playlist) ?: throw Exception("Not supported")
    override suspend fun isItemSaved(item: EchoMediaItem): Boolean = getMetadataExtension().clientOrNull<SaveClient>()?.isItemSaved(item) ?: false
    override suspend fun saveToLibrary(item: EchoMediaItem, shouldSave: Boolean) = getMetadataExtension().clientOrNull<SaveClient>()?.saveToLibrary(item, shouldSave) ?: throw Exception("Not supported")
    override suspend fun isItemLiked(item: EchoMediaItem): Boolean = getMetadataExtension().clientOrNull<LikeClient>()?.isItemLiked(item) ?: false
    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) = getMetadataExtension().clientOrNull<LikeClient>()?.likeItem(item, shouldLike) ?: throw Exception("Not supported")
    override suspend fun onShare(item: EchoMediaItem): String = getMetadataExtension().clientOrNull<ShareClient>()?.onShare(item) ?: throw Exception("Not supported")
    override suspend fun isFollowing(item: EchoMediaItem): Boolean = getMetadataExtension().clientOrNull<FollowClient>()?.isFollowing(item) ?: false
    override suspend fun followItem(item: EchoMediaItem, shouldFollow: Boolean) = getMetadataExtension().clientOrNull<FollowClient>()?.followItem(item, shouldFollow) ?: throw Exception("Not supported")
    override suspend fun getFollowersCount(item: EchoMediaItem): Long? = getMetadataExtension().clientOrNull<FollowClient>()?.getFollowersCount(item)
    override suspend fun isItemHidden(item: EchoMediaItem): Boolean = getMetadataExtension().clientOrNull<HideClient>()?.isItemHidden(item) ?: false
    override suspend fun hideItem(item: EchoMediaItem, shouldHide: Boolean) = getMetadataExtension().clientOrNull<HideClient>()?.hideItem(item, shouldHide) ?: throw Exception("Not supported")
    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio = getMetadataExtension().clientOrNull<RadioClient>()?.radio(item, context) ?: throw Exception("Not supported")
    override suspend fun loadRadio(radio: Radio): Radio = getMetadataExtension().clientOrNull<RadioClient>()?.loadRadio(radio) ?: throw Exception("Not supported")
    override suspend fun loadTracks(radio: Radio): Feed<Track> = getMetadataExtension().clientOrNull<RadioClient>()?.loadTracks(radio) ?: throw Exception("Not supported")
}
