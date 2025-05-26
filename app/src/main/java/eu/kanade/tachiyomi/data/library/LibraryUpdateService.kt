package eu.kanade.tachiyomi.data.library

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.track.TrackStatus
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga // For SManga.COMPLETED
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.all.MergedSource
import exh.source.MERGED_SOURCE_ID
import exh.util.nullIfBlank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category // For companion object start method
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.GroupLibraryMode
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_HAS_UNREAD
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_COMPLETED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_READ
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_OUTSIDE_RELEASE_PERIOD
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedMangaForDownloading
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.ZonedDateTime
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

const val TAG = "LibraryUpdateService"

@Serializable
enum class LiveUpdateAction {
    START,
    STOP,
}

@Serializable
data class ClientLiveUpdateRequest(
    val action: LiveUpdateAction,
    val mangas: List<LiveUpdateMangaInput>? = null,
) {
    @Serializable
    data class LiveUpdateMangaInput(
        val sourceId: Long,
        val mangaUrl: String,
        val mangaStatus: Int,
        val mangaId: Long, // Client-provided ID to correlate results
        val updateMangaMetadata: Boolean,
    )
}

@Serializable
data class ServerLiveUpdatePayload(
    val mangaId: Long, // Client-provided mangaId
    val mangaDetails: LiveMangaDetailsDto?,
    val chapters: List<LiveChapterDto>?,
    val error: String? = null,
) {
    @Serializable
    data class LiveMangaDetailsDto(
        val title: String,
        val artist: String?,
        val author: String?,
        val description: String?,
        val genre: String?, // Changed to List<String> to match common practice
        val status: Int,
        val thumbnailUrl: String?,
    )

    @Serializable
    data class LiveChapterDto(
        val url: String,
        val name: String,
        val dateUpload: Long,
        val chapterNumber: Float,
        val scanlator: String?,
    )
}

@Serializable
enum class LiveUpdateStatus { IDLE, ONGOING }

@Serializable
data class LiveUpdateStatusDto(
    val status: LiveUpdateStatus,
)

@OptIn(ExperimentalAtomicApi::class)
class LibraryUpdateService : Service() {

    private lateinit var sourceManager: SourceManager
    private lateinit var updateManga: UpdateManga
    private lateinit var getManga: GetManga
    private lateinit var getMergedMangaForDownloading: GetMergedMangaForDownloading
    private lateinit var filterChaptersForDownload: FilterChaptersForDownload
    private lateinit var syncChaptersWithSource: SyncChaptersWithSource
    private lateinit var coverCache: CoverCache
    private lateinit var downloadManager: DownloadManager
    private lateinit var libraryPreferences: LibraryPreferences
    private lateinit var getLibraryManga: GetLibraryManga
    private lateinit var getTracks: GetTracks
    private lateinit var trackerManager: TrackerManager
    private lateinit var fetchInterval: FetchInterval
    private lateinit var notifier: LibraryUpdateNotifier
    private lateinit var networkHelper: NetworkHelper
    private lateinit var json: Json

    private var mangaToUpdate: List<LibraryManga> = mutableListOf()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private var webSocketUrl: String? = null

    // This flag indicates if the service is in the process of stopping.
    private val isStopping = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        sourceManager = Injekt.get()
        updateManga = Injekt.get()
        getManga = Injekt.get()
        getMergedMangaForDownloading = Injekt.get()
        filterChaptersForDownload = Injekt.get()
        syncChaptersWithSource = Injekt.get()
        coverCache = Injekt.get()
        downloadManager = Injekt.get()
        libraryPreferences = Injekt.get()
        getLibraryManga = Injekt.get()
        getTracks = Injekt.get()
        trackerManager = Injekt.get()
        fetchInterval = Injekt.get()
        notifier = LibraryUpdateNotifier(this)
        networkHelper = Injekt.get()
        webSocketUrl = libraryPreferences.remoteUpdaterUrl().get()
        json = Injekt.get()
        logcat(LogPriority.INFO, tag = TAG) { "LibraryUpdateService created." }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logcat(LogPriority.INFO, tag = TAG) { "LibraryUpdateService onStartCommand." }

        val action = intent?.action ?: return START_NOT_STICKY
        when (action) {
            ACTION_START_SERVICE -> {
                if (isUpdateActive.load()) {
                    logcat(LogPriority.INFO, tag = TAG) { "Library remote update is already in progress. Ignoring new start request (Start ID: $startId)." }
                    // We return START_NOT_STICKY because this particular start command won't lead to a new operation.
                    // The existing operation will continue. The service itself will stop when that operation is done.
                    return START_NOT_STICKY
                }

                isUpdateActive.store(true)
                isStopping.store(false)

                try {
                    val initialNotification = notifier.progressNotificationBuilder.build()
                    startForeground(Notifications.ID_LIBRARY_PROGRESS, initialNotification)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e, tag = TAG) { "Error starting foreground service." }
                    stopSelf()
                    return START_NOT_STICKY
                }

                val categoryId = intent.getLongExtra(KEY_CATEGORY, -1L)
                val group = intent.getIntExtra(KEY_GROUP, LibraryGroup.BY_DEFAULT)
                val groupExtra = intent.getStringExtra(KEY_GROUP_EXTRA)

                serviceScope.launch {
                    try {
                        logcat(LogPriority.DEBUG, tag = TAG) { "Service Scope: Launching addMangaToQueue." }
                        addMangaToQueue(categoryId, group, groupExtra)
                        logcat(LogPriority.INFO, tag = TAG) { "addMangaToQueue completed. Manga to update count: ${mangaToUpdate.size}" }

                        if (mangaToUpdate.isNotEmpty()) {
                            val liveUpdateMangas = mangaToUpdate.map { libraryManga ->
                                ClientLiveUpdateRequest.LiveUpdateMangaInput(
                                    sourceId = libraryManga.manga.source,
                                    mangaUrl = libraryManga.manga.url,
                                    mangaStatus = libraryManga.manga.status.toInt(),
                                    mangaId = libraryManga.manga.id,
                                    updateMangaMetadata = libraryPreferences.autoUpdateMetadata().get(),
                                )
                            }

                            val startRequest = ClientLiveUpdateRequest(
                                action = LiveUpdateAction.START,
                                mangas = liveUpdateMangas,
                            )

                            webSocket?.close(1000, "Starting new update session")
                            connectWebSocket(startRequest)
                        } else {
                            logcat(LogPriority.INFO, tag = TAG) { "No manga to update. Service will stop." }
                            stopAndCleanup(startId)
                        }
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e, tag = TAG) { "Error during addMangaToQueue or WebSocket connection in LibraryUpdateService" }
                        stopAndCleanup(startId)
                    }
                }
            }
            ACTION_STOP_SERVICE -> {
                stopAndCleanup()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        webSocket?.close(1000, null)
        webSocket = null
        isUpdateActive.store(false)
        // Ensure notification is removed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        logcat(LogPriority.INFO, tag = TAG) { "LibraryUpdateService destroyed." }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun connectWebSocket(initialMessage: ClientLiveUpdateRequest? = null) {
        if (isStopping.load()) return

        val url = webSocketUrl
        if (url.isNullOrEmpty()) {
            logcat(LogPriority.ERROR, tag = TAG) { "WebSocket URL is null, cannot connect." }
            // isUpdateActive should already be true here if we reached this point.
            // stopAndCleanup will set it to false.
            stopAndCleanup()
            return
        }

        logcat(LogPriority.INFO, tag = TAG) { "Attempting to connect to WebSocket: $url" }
        val request = Request.Builder().url(url).build()
        webSocket = networkHelper.pingClient.newWebSocket(request, LibraryWebSocketListener(initialMessage))
    }

    private inner class LibraryWebSocketListener(
        private val initialMessageToSend: ClientLiveUpdateRequest?,
    ) : WebSocketListener() {

        private val progressCount = AtomicInt(0)
        private val fetchWindow = fetchInterval.getWindow(ZonedDateTime.now())
        private val newUpdates = mutableListOf<Pair<Manga, Array<Chapter>>>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            logcat(LogPriority.INFO, tag = TAG) { "WebSocket Opened: ${response.message}" }
            this@LibraryUpdateService.webSocket = webSocket
            initialMessageToSend?.let {
                try {
                    val messageJson = json.encodeToString(it)
                    logcat(LogPriority.DEBUG, tag = TAG) { "Sending START request: $messageJson" }
                    webSocket.send(messageJson)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e, tag = TAG) { "Error serializing/sending START request" }
                    stopAndCleanup() // Stop if initial message fails
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            logcat(LogPriority.DEBUG, tag = TAG) { "WebSocket Message Received: $text" }
            val currentMangaToUpdateSize = mangaToUpdate.size // Capture size for consistent check

            serviceScope.launch {
                try {
                    try {
                        val statusDto = json.decodeFromString<LiveUpdateStatusDto>(text)
                        logcat(LogPriority.INFO, tag = TAG) { "Received Status Update: ${statusDto.status}" }
                        if (statusDto.status == LiveUpdateStatus.IDLE) {
                            logcat(LogPriority.INFO, tag = TAG) { "Live update process is IDLE according to server. Update considered complete." }
                            stopAndCleanup()
                        }
                        return@launch
                    } catch (e: SerializationException) {
                        logcat(LogPriority.VERBOSE) { "Not a LiveUpdateStatusDto, trying ServerLiveUpdatePayload." }
                    }

                    val payload = json.decodeFromString<ServerLiveUpdatePayload>(text)
                    logcat(LogPriority.INFO, tag = TAG) { "Received Payload for mangaId: ${payload.mangaId}" }

                    notifier.showProgressRemoteNotification(
                        progressCount.load(),
                        mangaToUpdate.size,
                    )

                    val localManga = mangaToUpdate.find { it.manga.id == payload.mangaId }?.manga
                    if (localManga != null) {
                        if (payload.error != null) {
                            logcat(LogPriority.WARN, tag = TAG) { "Error for ${localManga.title}: ${payload.error}" }
                        } else {
                            val newChapters = syncMangaAndChapters(localManga, payload, fetchWindow)
                                .sortedByDescending { it.sourceOrder }

                            if (newChapters.isNotEmpty()) {
                                libraryPreferences.newUpdatesCount().getAndSet { it + newChapters.size }
                                newUpdates.add(localManga to newChapters.toTypedArray())
                            }

                            logcat(LogPriority.INFO, tag = TAG) { "Successfully processed ${localManga.title}" }
                        }
                    } else {
                        logcat(LogPriority.WARN, tag = TAG) { "Received payload for unknown mangaId: ${payload.mangaId}" }
                    }

                    progressCount.incrementAndFetch()

                    notifier.showProgressRemoteNotification(
                        progressCount.load(),
                        mangaToUpdate.size,
                    )

                    // Check if all manga have been processed
                    if (currentMangaToUpdateSize > 0 && progressCount.load() >= currentMangaToUpdateSize) {
                        logcat(LogPriority.INFO, tag = TAG) { "All $currentMangaToUpdateSize manga have been processed. Stopping service." }
                        if (newUpdates.isNotEmpty()) {
                            notifier.showUpdateNotifications(newUpdates)
                        } else {
                            notifier.showUpdateNoNewChapterNotifications()
                        }
                        stopAndCleanup()
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e, tag = TAG) { "Error processing WebSocket message: $text" }
                    // Consider if service should stop on all message processing errors
                    // stopAndCleanup()
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            logcat(LogPriority.INFO, tag = TAG) { "WebSocket Closing: Code=$code, Reason=$reason" }
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            logcat(LogPriority.INFO, tag = TAG) { "WebSocket Closed: Code=$code, Reason=$reason" }
            if (!isStopping.load() && isUpdateActive.load()) { // If not initiated by stopAndCleanup but an update was active
                logcat(LogPriority.WARN, tag = TAG) { "WebSocket closed unexpectedly. Code: $code, Reason=$reason" }
                stopAndCleanup()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            logcat(LogPriority.ERROR, t, tag = TAG) { "WebSocket Failure: ${response?.message}" }
            if (!isStopping.load() && isUpdateActive.load()) { // If not initiated by stopAndCleanup but an update was active
                logcat(LogPriority.WARN, tag = TAG) { "WebSocket connection failed: ${t.message}" }
                stopAndCleanup()
            }
        }
    }

    private fun stopAndCleanup(serviceStartId: Int? = null) {
        if (!isStopping.compareAndSet(expectedValue = false, newValue = true)) {
            logcat(LogPriority.DEBUG, tag = TAG) { "stopAndCleanup already in progress or called." }
            return
        }
        logcat(LogPriority.INFO, tag = TAG) { "stopAndCleanup called. StartId: $serviceStartId" }

        // 1. Send STOP message and close WebSocket if active
        val currentWebSocket = webSocket
        if (currentWebSocket != null) {
            this.webSocket = null // Nullify immediately to prevent reuse
            try {
                val stopRequest = ClientLiveUpdateRequest(action = LiveUpdateAction.STOP)
                val messageJson = json.encodeToString(stopRequest)
                logcat(LogPriority.INFO, tag = TAG) { "Attempting to send STOP request via WebSocket." }
                // Note: OkHttp's send can throw if socket is already closed or closing.
                // It returns false if the message couldn't be enqueued (e.g. buffer full).
                currentWebSocket.send(messageJson)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e, tag = TAG) { "Error sending STOP request, WebSocket might already be closed." }
            }
            logcat(LogPriority.INFO, tag = TAG) { "Closing WebSocket." }
            currentWebSocket.close(1000, "Client initiated shutdown")
        }

        // 2. cancel service and update isUpdateActive
        serviceScope.cancel()
        isUpdateActive.store(false)

        // 3. Stop foreground and service
        // Ensure this runs even if called multiple times or from different contexts
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            notifier.cancelProgressNotification()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e, tag = TAG) { "Error during stopForeground or cancelling notification." }
        }

        if (serviceStartId != null) {
            stopSelf(serviceStartId)
        } else {
            stopSelf()
        }
        logcat(LogPriority.INFO, tag = TAG) { "LibraryUpdateService stop procedures initiated." }
    }

    private suspend fun syncMangaAndChapters(
        manga: Manga,
        payload: ServerLiveUpdatePayload,
        fetchWindow: Pair<Long, Long>,
    ): List<Chapter> {
        val source = sourceManager.getOrStub(manga.source)
        if (source is MergedSource) {
            // TODO handle merged source
            return emptyList()
        }

        val mangaDetails = payload.mangaDetails
        if (mangaDetails != null) {
            val networkManga = SManga(
                url = manga.url,
                title = mangaDetails.title,
                artist = mangaDetails.artist,
                author = mangaDetails.author,
                description = mangaDetails.description,
                genre = mangaDetails.genre,
                status = mangaDetails.status,
                thumbnail_url = mangaDetails.thumbnailUrl,
                initialized = manga.initialized, // Retain initialized status
            ).apply { update_strategy = manga.updateStrategy } // Retain update strategy
            updateManga.awaitUpdateFromSource(manga, networkManga, manualFetch = false, coverCache)
        }

        val payloadChapters = payload.chapters ?: return emptyList()
        if (payloadChapters.isEmpty()) return emptyList()
        val dbManga = getManga.await(manga.id)?.takeIf { it.favorite } ?: return emptyList()
        val chapters = payloadChapters.map {
            SChapter(
                name = it.name,
                url = it.url,
                date_upload = it.dateUpload,
                chapter_number = it.chapterNumber,
                scanlator = it.scanlator,
            )
        }
        return syncChaptersWithSource.await(chapters, dbManga, source, false, fetchWindow)
    }

    private fun downloadChapters(manga: Manga, chapters: List<Chapter>) {
        if (manga.source == MERGED_SOURCE_ID) {
            val downloadingManga = runBlocking { getMergedMangaForDownloading.await(manga.id) }
                .associateBy { it.id }
            chapters.groupBy { it.mangaId }
                .forEach {
                    downloadManager.downloadChapters(
                        downloadingManga[it.key] ?: return@forEach,
                        it.value,
                        false,
                    )
                }
            return
        }
        downloadManager.downloadChapters(manga, chapters, false)
    }

    private suspend fun addMangaToQueue(
        categoryId: Long,
        group: Int,
        groupExtra: String?,
    ) {
        val contextForStrings: Context = this

        val libraryManga = getLibraryManga.await()
        val groupLibraryUpdateType = libraryPreferences.groupLibraryUpdateType().get()

        val listToUpdate = if (categoryId != -1L) {
            libraryManga.filter { it.category == categoryId }
        } else if (
            group == LibraryGroup.BY_DEFAULT ||
            groupLibraryUpdateType == GroupLibraryMode.GLOBAL ||
            (groupLibraryUpdateType == GroupLibraryMode.ALL_BUT_UNGROUPED && group == LibraryGroup.UNGROUPED)
        ) {
            val categoriesToUpdate = libraryPreferences.updateCategories().get().map(String::toLong)
            val includedManga = if (categoriesToUpdate.isNotEmpty()) {
                libraryManga.filter { it.category in categoriesToUpdate }
            } else {
                libraryManga
            }

            val categoriesToExclude = libraryPreferences.updateCategoriesExclude().get().map { it.toLong() }
            val excludedMangaIds = if (categoriesToExclude.isNotEmpty()) {
                libraryManga.filter { it.category in categoriesToExclude }.map { it.manga.id }
            } else {
                emptyList()
            }

            includedManga
                .filterNot { it.manga.id in excludedMangaIds }
        } else {
            when (group) {
                LibraryGroup.BY_TRACK_STATUS -> {
                    val trackingExtra = groupExtra?.toIntOrNull() ?: -1
                    val tracks = getTracks.await().groupBy { it.mangaId }

                    libraryManga.filter { (manga) ->
                        val status = tracks[manga.id]?.firstNotNullOfOrNull { track ->
                            TrackStatus.parseTrackerStatus(trackerManager, track.trackerId, track.status)
                        } ?: TrackStatus.OTHER
                        status.int == trackingExtra
                    }
                }
                LibraryGroup.BY_SOURCE -> {
                    val sourceExtra = groupExtra?.nullIfBlank()?.toIntOrNull()
                    val sourcesInLibrary = libraryManga.map { it.manga.source }.distinct().sorted()
                    val targetSourceId = if (sourceExtra != null && sourceExtra >= 0 && sourceExtra < sourcesInLibrary.size) {
                        sourcesInLibrary[sourceExtra]
                    } else {
                        null
                    }

                    if (targetSourceId != null) {
                        libraryManga.filter { it.manga.source == targetSourceId }
                    } else {
                        emptyList()
                    }
                }
                LibraryGroup.BY_STATUS -> {
                    val statusExtra = groupExtra?.toLongOrNull() ?: -1
                    libraryManga.filter {
                        it.manga.status == statusExtra
                    }
                }
                LibraryGroup.UNGROUPED -> libraryManga
                else -> libraryManga
            }
        }

        val restrictions = libraryPreferences.autoUpdateMangaRestrictions().get()
        val skippedUpdates = mutableListOf<Pair<Manga, String?>>()
        val (_, fetchWindowUpperBound) = fetchInterval.getWindow(ZonedDateTime.now())

        mangaToUpdate = listToUpdate
            .distinctBy { it.manga.id }
            .filter {
                when {
                    it.manga.updateStrategy != UpdateStrategy.ALWAYS_UPDATE -> {
                        skippedUpdates.add(
                            it.manga to
                                contextForStrings.stringResource(MR.strings.skipped_reason_not_always_update),
                        )
                        false
                    }
                    MANGA_NON_COMPLETED in restrictions && it.manga.status.toInt() == SManga.COMPLETED -> {
                        skippedUpdates.add(it.manga to contextForStrings.stringResource(MR.strings.skipped_reason_completed))
                        false
                    }
                    MANGA_HAS_UNREAD in restrictions && it.unreadCount != 0L -> {
                        skippedUpdates.add(it.manga to contextForStrings.stringResource(MR.strings.skipped_reason_not_caught_up))
                        false
                    }
                    MANGA_NON_READ in restrictions && it.totalChapters > 0L && !it.hasStarted -> {
                        skippedUpdates.add(it.manga to contextForStrings.stringResource(MR.strings.skipped_reason_not_started))
                        false
                    }
                    MANGA_OUTSIDE_RELEASE_PERIOD in restrictions && it.manga.nextUpdate > fetchWindowUpperBound -> {
                        skippedUpdates.add(
                            it.manga to
                                contextForStrings.stringResource(MR.strings.skipped_reason_not_in_release_period),
                        )
                        false
                    }
                    else -> true
                }
            }
            .sortedBy { it.manga.title }

        notifier.showQueueSizeWarningNotificationIfNeeded(mangaToUpdate)

        if (skippedUpdates.isNotEmpty()) {
            logcat(LogPriority.INFO, tag = TAG) {
                val reasonStrings = skippedUpdates
                    .groupBy { it.second }
                    .map { (reason, entries) ->
                        "$reason: ${entries.size} manga (${entries.take(5).joinToString { it.first.title }}${if (entries.size > 5) "..." else ""})"
                    }
                "Skipped manga for library update due to restrictions: ${reasonStrings.joinToString()}"
            }
        }
        logcat(LogPriority.DEBUG, tag = TAG) { "Finished addMangaToQueue. ${mangaToUpdate.size} manga selected." }
    }

    companion object {
        private const val KEY_CATEGORY = "eu.kanade.tachiyomi.data.library.LibraryUpdateService.KEY_CATEGORY"
        private const val KEY_GROUP = "eu.kanade.tachiyomi.data.library.LibraryUpdateService.KEY_GROUP"
        private const val KEY_GROUP_EXTRA = "eu.kanade.tachiyomi.data.library.LibraryUpdateService.KEY_GROUP_EXTRA"

        const val ACTION_START_SERVICE = "dev.achmad.shinyomi.ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "dev.achmad.shinyomi.ACTION_STOP_SERVICE"

        // Static flag to indicate if an update operation is logically active.
        private val isUpdateActive = AtomicBoolean(false)

        /**
         * Checks if the LibraryUpdateService is currently considered to be actively processing an update.
         * This is based on a static flag managed by the service itself.
         */
        fun isRunning(): Boolean {
            return isUpdateActive.load()
        }

        fun start(
            context: Context,
            category: Category? = null,
            group: Int = LibraryGroup.BY_DEFAULT,
            groupExtra: String? = null,
        ): Boolean {
            if (isRunning()) {
                logcat(LogPriority.DEBUG, tag = TAG) { "Remote Update already ongoing" }
                return false
            }

            val intent = Intent(context, LibraryUpdateService::class.java).apply {
                action = ACTION_START_SERVICE
                putExtra(KEY_CATEGORY, category?.id)
                putExtra(KEY_GROUP, group)
                putExtra(KEY_GROUP_EXTRA, groupExtra)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return true
        }

        fun stop(context: Context) {
            logcat(LogPriority.INFO, tag = TAG) { "Explicitly stopping LibraryUpdateService." }
            val intent = Intent(context, LibraryUpdateService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            // Use startService to deliver the intent to an already running service
            context.startService(intent)
        }
    }
}
