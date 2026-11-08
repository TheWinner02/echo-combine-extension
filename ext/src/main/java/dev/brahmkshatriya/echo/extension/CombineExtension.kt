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
import dev.brahmkshatriya.echo.common.settings.SettingOnClick
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ensureActive
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
        const val KEY_FILTER_HIDDEN = "filter_hidden_tracks"
        private const val KEY_HIDDEN_ITEMS = "combine_hidden_items"
        private const val PREFIX_HIDDEN_TITLE = "combine_hidden_title_"
        private const val PREFIX_HIDDEN_COVER = "combine_hidden_cover_"
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

    private fun isFilterHiddenEnabled(): Boolean {
        if (!::settings.isInitialized) return true
        return runCatching { settings.getBoolean(KEY_FILTER_HIDDEN) }.getOrNull() ?: true
    }

    private fun getHiddenItems(): Set<String> {
        if (!::settings.isInitialized) return emptySet()
        return runCatching { settings.getStringSet(KEY_HIDDEN_ITEMS) }.getOrNull() ?: emptySet()
    }

    private fun setHiddenItem(itemId: String, shouldHide: Boolean, title: String? = null, coverUrl: String? = null) {
        if (!::settings.isInitialized) return
        val current = getHiddenItems().toMutableSet()
        val titleKey = "$PREFIX_HIDDEN_TITLE$itemId"
        val coverKey = "$PREFIX_HIDDEN_COVER$itemId"
        if (shouldHide) {
            current.add(itemId)
            if (!title.isNullOrBlank()) {
                runCatching { settings.putString(titleKey, title) }
            }
            if (!coverUrl.isNullOrBlank()) {
                runCatching { settings.putString(coverKey, coverUrl) }
            }
        } else {
            current.remove(itemId)
            runCatching { settings.putString(titleKey, null) }
            runCatching { settings.putString(coverKey, null) }
        }
        runCatching { settings.putStringSet(KEY_HIDDEN_ITEMS, current) }
    }

    private fun clearAllHiddenItems() {
        if (!::settings.isInitialized) return
        val current = getHiddenItems()
        for (id in current) {
            runCatching { settings.putString("$PREFIX_HIDDEN_TITLE$id", null) }
            runCatching { settings.putString("$PREFIX_HIDDEN_COVER$id", null) }
        }
        runCatching { settings.putStringSet(KEY_HIDDEN_ITEMS, emptySet()) }
    }

    private fun getHiddenItemsWithTitles(): List<Pair<String, String>> {
        val items = getHiddenItems()
        if (items.isEmpty()) return emptyList()
        return items.map { id ->
            val title = runCatching { settings.getString("$PREFIX_HIDDEN_TITLE$id") }.getOrNull()
            id to (title ?: id)
        }.sortedBy { it.second.lowercase() }
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

        settingsList.add(
            SettingSwitch(
                "Filter Hidden Tracks",
                KEY_FILTER_HIDDEN,
                "Automatically exclude hidden tracks from playlists, albums and radios",
                true
            )
        )

        val hiddenCount = getHiddenItems().size
        settingsList.add(
            SettingOnClick(
                "Manage hidden tracks",
                "manage_hidden_tracks",
                if (hiddenCount == 0) "No hidden tracks • Tap to manage" else "$hiddenCount hidden tracks • Tap to manage"
            ) {}
        )

        return settingsList
    }

    // --- MusicExtensionsProvider ---

    override val requiredMusicExtensions: List<String> = emptyList()

    private val extensionsFlow = MutableStateFlow<List<MusicExtension>?>(null)

    override fun setMusicExtensions(extensions: List<MusicExtension>) {
        extensionsFlow.value = extensions.filter { it.id != COMBINE_ID && it.isEnabled }
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
        val originalCatalog = track.extras[RememberedSource.CATALOG]
        check(originalCatalog == null || originalCatalog == metaExt.id) {
            "Metadata source changed. Reopen this song from the current catalogue."
        }
        val metaClient = metaExt.client<TrackClient>()
        val metaTrack = try {
            metaClient.loadTrack(track, isDownload)
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Throwable) {
            println("CombineExtension: Metadata extension failed to load track: ${e.message}")
            track
        }

        val streamExts = getStreamExtensions()
        val remembered = RememberedSource.choice(track, metaExt.id)

        val allStreamables = coroutineScope {
            val deferreds = streamExts.mapIndexed { index, ext ->
                async {
                    val streamables = if (remembered?.provider == ext.id) {
                        // Resolve the exact stored recording. Never silently substitute a fuzzy match.
                        try {
                            val exact = if (ext.id == metaExt.id && metaTrack.id == remembered.track) metaTrack
                            else withTimeout(5_000) {
                                ext.client<TrackClient>().loadTrack(Track(remembered.track, metaTrack.title), isDownload)
                            }
                            if (exact.id != remembered.track) emptyList()
                            else exact.streamables.map { RememberedSource.tag(it, ext.id, exact.id) }
                        } catch (cancel: CancellationException) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            emptyList()
                        } catch (_: Exception) { emptyList() }
                    } else if (ext.id == metaExt.id) {
                        metaTrack.streamables.map { RememberedSource.tag(it, ext.id, metaTrack.id) }
                    } else {
                        try {
                            findTrackInStreamExtension(metaTrack, ext)?.streamables ?: emptyList()
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (e: Throwable) {
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
                if (remembered?.provider == metaExt.id) emptyList()
                else metaTrack.streamables.map { RememberedSource.tag(it, metaExt.id, metaTrack.id) }
            } else {
                results.sortedWith(compareByDescending<Pair<Streamable, Int>> { it.first.quality }.thenBy { it.second })
                    .map { it.first }
                    .distinctBy { Triple(it.extras[RememberedSource.PROVIDER], it.extras[RememberedSource.TRACK], it.id) }
            }
        }

        return RememberedSource.metadata(metaTrack, track, metaExt.id).copy(streamables = allStreamables)
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

        val allCandidates = mutableListOf<Track>()
        for (shelf in shelves) {
            when (shelf) {
                is Shelf.Item -> {
                    (shelf.media as? Track)?.let { allCandidates.add(it) }
                }
                is Shelf.Lists.Tracks -> allCandidates.addAll(shelf.list)
                is Shelf.Lists.Items -> allCandidates.addAll(shelf.list.mapNotNull { it as? Track })
                else -> {}
            }
        }

        val scoredCandidates = allCandidates.mapNotNull { candidate ->
            val score = scoreMatch(metaTrack, candidate)
            if (score > 30.0) candidate to score else null
        }.sortedByDescending { it.second }

        for ((candidate, _) in scoredCandidates) {
            val loaded = try {
                withTimeoutOrNull(5000) {
                    trackClient.loadTrack(candidate, false)
                }
            } catch (_: Throwable) { null }

            if (loaded != null && loaded.streamables.isNotEmpty()) {
                return loaded.copy(streamables = loaded.streamables.map { s ->
                    val existingExtras = s.extras
                    val updatedExtras = if (existingExtras.containsKey("stream_ext_id")) existingExtras else existingExtras + mapOf("stream_ext_id" to streamExt.id)
                    RememberedSource.tag(s.copy(extras = updatedExtras), streamExt.id, loaded.id)
                })
            }
        }
        null
    } catch (_: Throwable) {
        null
    }

    private fun scoreMatch(target: Track, candidate: Track): Double {
        if (!target.isrc.isNullOrBlank() && !candidate.isrc.isNullOrBlank() && target.isrc.equals(candidate.isrc, ignoreCase = true)) {
            return 1000.0
        }

        val targetRawTitle = target.title.lowercase().trim()
        val candidateRawTitle = candidate.title.lowercase().trim()

        val targetClean = cleanTitle(target.title)
        val candidateClean = cleanTitle(candidate.title)

        if (targetClean.isEmpty() || candidateClean.isEmpty()) return 0.0

        val versionModifiers = listOf(
            "remix", "live", "acoustic", "instrumental", "karaoke",
            "cover", "slowed", "reverb", "speed up", "sped up", "nightcore",
            "radio edit", "extended", "club mix", "dub", "tribute", "parody"
        )

        val targetModifiers = versionModifiers.filter { targetRawTitle.contains(it) }
        val candidateModifiers = versionModifiers.filter { candidateRawTitle.contains(it) }

        val extraModifiersInCandidate = candidateModifiers.filter { !targetModifiers.contains(it) }
        if (extraModifiersInCandidate.isNotEmpty()) {
            return 0.0
        }

        val missingModifiersInCandidate = targetModifiers.filter { !candidateModifiers.contains(it) }
        if (missingModifiersInCandidate.isNotEmpty()) {
            return 10.0
        }

        var score = 0.0

        if (targetRawTitle == candidateRawTitle) {
            score += 80.0
        } else if (targetClean == candidateClean) {
            score += 60.0
        } else if (candidateClean.startsWith(targetClean) || targetClean.startsWith(candidateClean)) {
            score += 40.0
        } else if (candidateClean.contains(targetClean) || targetClean.contains(candidateClean)) {
            score += 30.0
        } else {
            val targetWords = targetClean.split(" ").filter { it.length > 1 }.toSet()
            val candidateWords = candidateClean.split(" ").filter { it.length > 1 }.toSet()
            val commonWords = targetWords.intersect(candidateWords)
            if (commonWords.isEmpty()) return 0.0
            val overlapRatio = (commonWords.size.toDouble() * 2) / (targetWords.size + candidateWords.size)
            if (overlapRatio < 0.5) return 0.0
            score += (overlapRatio * 35.0)
        }

        val targetArtistClean = cleanTitle(target.artists.firstOrNull()?.name ?: "")
        val candidateArtistsClean = candidate.artists.map { cleanTitle(it.name) }

        if (targetArtistClean.isNotEmpty()) {
            val directArtistMatch = candidateArtistsClean.any { it.contains(targetArtistClean) || targetArtistClean.contains(it) }
            val inCandidateTitle = candidateClean.contains(targetArtistClean)
            if (directArtistMatch) {
                score += 30.0
            } else if (inCandidateTitle) {
                score += 20.0
            } else {
                val targetArtistWords = targetArtistClean.split(" ").filter { it.length > 2 }
                val anyWordMatch = targetArtistWords.any { word -> candidateArtistsClean.any { it.contains(word) } || candidateClean.contains(word) }
                if (anyWordMatch) {
                    score += 15.0
                } else {
                    return 0.0
                }
            }
        }

        val targetDuration = target.duration
        val candidateDuration = candidate.duration
        if (targetDuration != null && targetDuration > 0 && candidateDuration != null && candidateDuration > 0) {
            val diffSec = kotlin.math.abs(targetDuration - candidateDuration) / 1000
            when {
                diffSec <= 3 -> score += 25.0
                diffSec <= 7 -> score += 15.0
                diffSec <= 15 -> score += 5.0
                diffSec > 45 -> score -= 40.0
            }
        }

        return score
    }

    private fun cleanTitle(title: String): String {
        return title.lowercase()
            .replace(Regex("\\(.*\\)|\\[.*\\]"), "")
            .replace(Regex("[^a-z0-9\\s]"), "")
            .trim()
    }

    private suspend fun <T : Any> loadFirstPage(pagedData: PagedData<T>): List<T> = try { pagedData.loadPage(null).data } catch (_: Throwable) { emptyList() }

    private fun Feed<Track>.filterHidden(): Feed<Track> {
        return Feed(tabs) { tab ->
            val originalData = getPagedData(tab)
            if (!isFilterHiddenEnabled()) return@Feed originalData

            val hidden = getHiddenItems()
            val meta = runCatching { getMetadataExtension() }.getOrNull()
            val hideClient = meta?.clientOrNull<HideClient>()

            val isHiddenTrack: suspend (Track) -> Boolean = { track ->
                if (hideClient != null) {
                    runCatching { hideClient.isItemHidden(track) }.getOrDefault(false)
                } else {
                    track.id in hidden
                }
            }

            val filteredPaged = originalData.pagedData.map { result ->
                val list = result.getOrDefault(emptyList())
                list.filter { track -> !isHiddenTrack(track) }
            }

            val origButtons = originalData.buttons
            val filteredCustom = origButtons?.customTrackList?.filter { track ->
                !isHiddenTrack(track)
            }
            val buttons = if (origButtons != null && origButtons.customTrackList != null) {
                origButtons.copy(customTrackList = filteredCustom)
            } else {
                origButtons
            }

            Feed.Data(filteredPaged, buttons, originalData.background)
        }
    }

    override suspend fun loadAlbum(album: Album): Album = getMetadataExtension().client<AlbumClient>().loadAlbum(album)
    override suspend fun loadTracks(album: Album): Feed<Track>? = getMetadataExtension().clientOrNull<AlbumClient>()?.loadTracks(album)?.filterHidden()
    override suspend fun loadFeed(album: Album): Feed<Shelf>? = getMetadataExtension().clientOrNull<AlbumClient>()?.loadFeed(album)
    override suspend fun loadArtist(artist: Artist): Artist = getMetadataExtension().client<ArtistClient>().loadArtist(artist)
    override suspend fun loadFeed(artist: Artist): Feed<Shelf> = getMetadataExtension().client<ArtistClient>().loadFeed(artist)
    override suspend fun loadPlaylist(playlist: Playlist): Playlist = getMetadataExtension().client<PlaylistClient>().loadPlaylist(playlist)
    override suspend fun loadTracks(playlist: Playlist): Feed<Track> = getMetadataExtension().client<PlaylistClient>().loadTracks(playlist).filterHidden()
    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = getMetadataExtension().clientOrNull<PlaylistClient>()?.loadFeed(playlist)
    override suspend fun listEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.listEditablePlaylists(track) ?: emptyList()
    override suspend fun createPlaylist(title: String, description: String?): Playlist = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.createPlaylist(title, description) ?: throw Exception("Not supported")
    override suspend fun addTracksToPlaylist(playlist: Playlist, tracks: List<Track>, index: Int, new: List<Track>) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.addTracksToPlaylist(playlist, tracks, index, new) ?: throw Exception("Not supported")
    override suspend fun removeTracksFromPlaylist(playlist: Playlist, tracks: List<Track>, indexes: List<Int>) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.removeTracksFromPlaylist(playlist, tracks, indexes) ?: throw Exception("Not supported")
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
    override suspend fun isItemHidden(item: EchoMediaItem): Boolean {
        val meta = runCatching { getMetadataExtension() }.getOrNull()
        val hideClient = meta?.clientOrNull<HideClient>()
        if (hideClient != null) {
            return runCatching { hideClient.isItemHidden(item) }.getOrDefault(false)
        }
        return item.id in getHiddenItems()
    }

    override suspend fun hideItem(item: EchoMediaItem, shouldHide: Boolean) {
        val meta = runCatching { getMetadataExtension() }.getOrNull()
        val hideClient = meta?.clientOrNull<HideClient>()
        if (hideClient != null) {
            hideClient.hideItem(item, shouldHide)
        } else {
            val title = if (item is Track && item.artists.isNotEmpty()) {
                "${item.title} - ${item.artists.joinToString { it.name }}"
            } else {
                item.title
            }
            val coverUrl = when (val c = (item as? Track)?.cover ?: (item as? Track)?.album?.cover ?: item.cover) {
                is dev.brahmkshatriya.echo.common.models.ImageHolder.NetworkRequestImageHolder -> c.request.url
                is dev.brahmkshatriya.echo.common.models.ImageHolder.ResourceUriImageHolder -> c.uri
                else -> null
            }
            setHiddenItem(item.id, shouldHide, title, coverUrl)
        }
    }

    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio = getMetadataExtension().clientOrNull<RadioClient>()?.radio(item, context) ?: throw Exception("Not supported")
    override suspend fun loadRadio(radio: Radio): Radio = getMetadataExtension().clientOrNull<RadioClient>()?.loadRadio(radio) ?: throw Exception("Not supported")
    override suspend fun loadTracks(radio: Radio): Feed<Track> = (getMetadataExtension().clientOrNull<RadioClient>()?.loadTracks(radio) ?: throw Exception("Not supported")).filterHidden()
}
