package io.github.aedev.flow.data.offline

import android.os.Environment
import android.os.StatFs
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.local.VideoHistoryEntry
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.local.dao.DownloadDao
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.local.entity.DownloadWithItems
import io.github.aedev.flow.data.model.ShortVideo
import io.github.aedev.flow.data.model.toShortVideo
import io.github.aedev.flow.data.model.toVideo
import io.github.aedev.flow.data.shorts.ShortsFeedRepository
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.data.video.downloader.request.toDownloadRequest
import io.github.aedev.flow.data.video.downloader.tags.DownloadKind
import io.github.aedev.flow.data.video.downloader.work.DownloadController
import io.github.aedev.flow.data.video.downloader.work.EnqueueOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the Offline videos sheet shows: Shorts on the device, the room they take, and a few covers. */
data class OfflineShortsStats(
    val count: Int,
    val bytes: Long,
    val thumbnails: List<String>,
)

enum class OfflineFillResult { QUEUED, NOTHING_NEEDED, NO_CANDIDATES, LOW_STORAGE }

data class OfflineFill(
    val result: OfflineFillResult,
    val queued: Int = 0,
)

/**
 * Keeps a set of Shorts on the device for watching without a connection.
 *
 * It picks nothing new to download itself: it chooses which Shorts, hands them to
 * [DownloadController], and lets the existing queue do the transfer, so Wi-Fi-only, pausing,
 * resuming after the app is killed and the storage location all behave as they do for any download.
 * "Offline videos" are the finished downloads of kind [DownloadKind.SHORT].
 */
@Singleton
class OfflineShortsRepository
    @Inject
    constructor(
        private val downloadDao: DownloadDao,
        private val downloads: VideoDownloadManager,
        private val downloadController: DownloadController,
        private val likedVideos: LikedVideosRepository,
        private val viewHistory: ViewHistory,
        private val playlistRepository: PlaylistRepository,
        private val shortsFeed: ShortsFeedRepository,
        private val preferences: PlayerPreferences,
    ) {
        suspend fun stats(): OfflineShortsStats =
            withContext(Dispatchers.IO) {
                val done = completedShorts()
                OfflineShortsStats(
                    count = done.size,
                    bytes = done.sumOf { it.totalSize },
                    thumbnails =
                        done
                            .map { it.download.thumbnailUrl }
                            .filter { it.isNotBlank() }
                            .take(THUMBNAIL_STACK),
                )
            }

        /** How many downloads are waiting or running, so the sheet can show that work is going on. */
        fun queueSize(): Flow<Int> = downloadDao.observeQueue().map { it.size }

        /** How many finished Shorts are on the device, for the offline toggle on the Shorts screen. */
        fun downloadedCount(): Flow<Int> =
            queueSize()
                .map { completedShorts().size }
                .distinctUntilChanged()

        /** The finished Shorts whose file is still on disk, newest download first. */
        suspend fun downloadedShorts(): List<ShortVideo> =
            withContext(Dispatchers.IO) {
                completedShorts()
                    .filter { downloads.findLocalCopy(it.download.videoId) != null }
                    .map { downloads.toDownloadedVideo(it).video.toShortVideo() }
            }

        /**
         * Queues Shorts until [target] are on the device or on their way. Those already downloaded
         * or queued count toward [target], so asking for 50 twice does not fetch 100.
         */
        suspend fun fill(target: Int): OfflineFill =
            withContext(Dispatchers.IO) {
                val rows = downloadDao.getAllDownloadsWithItemsOnce()
                val known = rows.mapTo(HashSet()) { it.download.videoId }
                val have =
                    rows.count {
                        it.download.kind == DownloadKind.SHORT && it.overallStatus in HAVE_OR_COMING
                    }
                val need = target - have
                if (need <= 0) return@withContext OfflineFill(OfflineFillResult.NOTHING_NEEDED)
                if (freeBytes() < LOW_STORAGE_BYTES) return@withContext OfflineFill(OfflineFillResult.LOW_STORAGE)

                val picks = pick(need, known)
                var queued = 0
                picks.forEach { short ->
                    if (downloadController.enqueue(short.toVideo().toDownloadRequest().copy(appOnly = true)) == EnqueueOutcome.QUEUED) queued++
                }
                OfflineFill(if (queued > 0) OfflineFillResult.QUEUED else OfflineFillResult.NO_CANDIDATES, queued)
            }

        /** Deletes one finished Short download, and only that: a regular video or a song stays. */
        suspend fun remove(videoId: String): Boolean =
            withContext(Dispatchers.IO) {
                val row = downloadDao.getDownloadWithItems(videoId) ?: return@withContext false
                val isFinishedShort = row.download.kind == DownloadKind.SHORT && row.overallStatus == DownloadItemStatus.COMPLETED
                if (!isFinishedShort) return@withContext false
                downloads.deleteDownload(videoId)
                true
            }

        /**
         * A downloaded Short was watched through in the offline feed. Nothing happens while the person
         * keeps watched videos. Otherwise, once every Short on the device
         * has been watched, or as many as the person asked to keep, they are all deleted. Returns
         * true when that happened, so the caller can queue the next set.
         */
        suspend fun onWatched(videoId: String): Boolean =
            withContext(Dispatchers.IO) {
                if (preferences.offlineKeepWatched.first()) return@withContext false
                val onDevice = completedShorts().map { it.download.videoId }.toSet()
                if (videoId !in onDevice) return@withContext false
                val watched = (preferences.offlineWatchedIds.first() + videoId) intersect onDevice
                preferences.setOfflineWatchedIds(watched)
                val target = preferences.offlineVideosTarget.first()
                if (watched.size < minOf(target, onDevice.size)) return@withContext false
                watched.forEach { remove(it) }
                preferences.setOfflineWatchedIds(emptySet())
                true
            }

        /** Deletes every finished Short download. Returns how many were removed. */
        suspend fun clear(): Int =
            withContext(Dispatchers.IO) {
                val ids = completedShorts().map { it.download.videoId }
                ids.forEach { downloads.deleteDownload(it) }
                ids.size
            }

        private suspend fun completedShorts(): List<DownloadWithItems> =
            downloadDao
                .getAllDownloadsWithItemsOnce()
                .filter { it.download.kind == DownloadKind.SHORT && it.overallStatus == DownloadItemStatus.COMPLETED }

        /**
         * What the person most likely wants, in order: Shorts they liked, ones they saved, ones they
         * watched to the end, then Shorts the feed picks for them to fill what is left.
         */
        private suspend fun pick(
            need: Int,
            known: Set<String>,
        ): List<ShortVideo> {
            val chosen = LinkedHashMap<String, ShortVideo>()

            fun add(short: ShortVideo) {
                if (chosen.size < need && short.id !in known) chosen.putIfAbsent(short.id, short)
            }

            likedShorts().forEach { add(it) }
            if (chosen.size < need) savedShorts().forEach { add(it) }
            if (chosen.size < need) watchedShorts().forEach { add(it) }
            if (chosen.size < need) fromFeed(need - chosen.size, known + chosen.keys).forEach { add(it) }
            return chosen.values.toList()
        }

        /** Likes keep no flag for Shorts, so a liked video counts when it is short enough to be one. */
        private suspend fun likedShorts(): List<ShortVideo> =
            likedVideos
                .getAllLikedVideos()
                .first()
                .filter { !it.isMusic && it.durationSeconds in 1..MAX_SHORT_SECONDS }
                .sortedByDescending { it.likedAt }
                .map {
                    ShortVideo(
                        id = it.videoId,
                        thumbnailUrl = it.thumbnail,
                        title = it.title,
                        channelName = it.channelName,
                        channelId = it.channelId.orEmpty(),
                        durationMs = it.durationSeconds * 1_000L,
                    )
                }

        private suspend fun savedShorts(): List<ShortVideo> =
            playlistRepository
                .getSavedShortsFlow()
                .first()
                .map { it.toShortVideo() }

        private suspend fun watchedShorts(): List<ShortVideo> =
            viewHistory
                .getRecentVideoHistory(limit = HISTORY_SCAN, includeShorts = true)
                .filter { it.isShort && !it.isMusic && !it.isLocal }
                .sortedWith(compareByDescending<VideoHistoryEntry> { it.watchedToTheEnd() }.thenByDescending { it.timestamp })
                .map {
                    ShortVideo(
                        id = it.videoId,
                        thumbnailUrl = it.thumbnailUrl,
                        title = it.title,
                        channelName = it.channelName,
                        channelId = it.channelId,
                        durationMs = it.duration,
                    )
                }

        private suspend fun fromFeed(
            count: Int,
            exclude: Set<String>,
        ): List<ShortVideo> {
            val out = LinkedHashMap<String, ShortVideo>()
            for (page in 0 until MAX_FEED_PAGES) {
                feedPage(first = page == 0).forEach {
                    if (out.size < count && it.id !in exclude) out.putIfAbsent(it.id, it)
                }
                if (out.size >= count || shortsFeed.isExhausted) break
            }
            return out.values.toList()
        }

        private suspend fun feedPage(first: Boolean): List<ShortVideo> =
            try {
                if (first) shortsFeed.openFeed(null) else shortsFeed.nextPage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }

        private fun freeBytes(): Long =
            try {
                StatFs(Environment.getDataDirectory().path).availableBytes
            } catch (e: IllegalArgumentException) {
                Long.MAX_VALUE
            }

        private fun VideoHistoryEntry.watchedToTheEnd(): Boolean = duration > 0 && position * 100 >= duration * FINISHED_PERCENT

        private companion object {
            const val THUMBNAIL_STACK = 3
            const val MAX_SHORT_SECONDS = 180
            const val HISTORY_SCAN = 400
            const val MAX_FEED_PAGES = 12
            const val FINISHED_PERCENT = 80
            const val LOW_STORAGE_BYTES = 500L * 1024 * 1024
            val HAVE_OR_COMING =
                setOf(DownloadItemStatus.COMPLETED, DownloadItemStatus.PENDING, DownloadItemStatus.DOWNLOADING)
        }
    }
