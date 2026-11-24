package com.sfidm.downloader.engine

import android.content.Context
import com.sfidm.downloader.model.DownloadMode
import com.sfidm.downloader.model.FileCategory
import com.sfidm.downloader.model.MediaQuality
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class MediaVideoDownloader(private val context: Context) {

    private val initMutex = Mutex()
    private var engineReady = false

    data class MediaOutcome(
        val filePath: String,
        val fileSize: Long,
        val category: FileCategory
    )

    suspend fun ensureInitialized() = initMutex.withLock {
        if (engineReady) return
        withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().init(context.applicationContext)
            runCatching { FFmpeg.getInstance().init(context.applicationContext) }
            engineReady = true
        }
    }

    suspend fun updateEngine(): YoutubeDL.UpdateStatus? = withContext(Dispatchers.IO) {
        ensureInitialized()
        YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext)
    }

    fun cancelProcess(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    suspend fun downloadMedia(
        processId: String,
        url: String,
        customName: String,
        quality: MediaQuality,
        isVault: Boolean,
        speedLimitBytesPerSec: Long,
        onProgress: (percent: Float, detail: String, resolvedTitle: String?) -> Unit
    ): MediaOutcome = withContext(Dispatchers.IO) {
        ensureInitialized()

        val tempBase = File(context.cacheDir, "sfidm_media_$processId")
        if (!tempBase.exists()) tempBase.mkdirs()

        val baseName = if (customName.isNotBlank()) {
            FileExportHelper.sanitizeFileName(customName.substringBeforeLast("."))
        } else {
            "%(title).70s"
        }
        val outputTemplate = File(tempBase, "$baseName.%(ext)s").absolutePath

        val request = YoutubeDLRequest(url)
        request.addOption("-o", outputTemplate)
        request.addOption("--no-mtime")
        request.addOption("--no-playlist")
        request.addOption("--restrict-filenames")
        request.addOption("--concurrent-fragments", "4")

        if (speedLimitBytesPerSec > 0L) {
            val kbps = (speedLimitBytesPerSec / 1024L).coerceAtLeast(64L)
            request.addOption("--limit-rate", "${kbps}K")
        }

        when (quality) {
            MediaQuality.BEST -> {
                request.addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best")
                request.addOption("--merge-output-format", "mp4")
            }
            MediaQuality.HD_1080 -> {
                request.addOption("-f", "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080][ext=mp4]/best[height<=1080]/best")
                request.addOption("--merge-output-format", "mp4")
            }
            MediaQuality.HD_720 -> {
                request.addOption("-f", "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best[height<=720]/best")
                request.addOption("--merge-output-format", "mp4")
            }
            MediaQuality.SD_480 -> {
                request.addOption("-f", "bestvideo[height<=480][ext=mp4]+bestaudio[ext=m4a]/best[height<=480][ext=mp4]/best[height<=480]/best")
                request.addOption("--merge-output-format", "mp4")
            }
            MediaQuality.AUDIO_MP3 -> {
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", "0")
            }
        }

        var detectedTitle: String? = null

        try {
            YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, line ->
                if (detectedTitle == null && line.contains("Destination:")) {
                    val rawDest = line.substringAfter("Destination:").trim()
                    val name = File(rawDest).name
                    if (name.isNotBlank()) detectedTitle = name
                }
                val cleanPercent = if (progress >= 0f) progress.coerceIn(0f, 100f) else 0f
                val detail = buildString {
                    if (cleanPercent > 0f) append("${cleanPercent.toInt()}%")
                    if (etaInSeconds > 0L) {
                        if (isNotEmpty()) append(" • ")
                        append("ETA ${etaInSeconds}s")
                    }
                }
                onProgress(cleanPercent, detail, detectedTitle)
            }

            val downloadedFile = tempBase.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() }
                ?: throw IllegalStateException("Downloaded media file not found")

            val category = FileCategory.detect(downloadedFile.name, DownloadMode.MEDIA_VIDEO, quality)
            val destDir = if (isVault) {
                FileExportHelper.getVaultDir(context)
            } else {
                FileExportHelper.getCategoryWorkingDir(context, category)
            }

            val finalDest = File(destDir, downloadedFile.name)
            if (finalDest.exists()) finalDest.delete()
            downloadedFile.copyTo(finalDest, overwrite = true)

            if (!isVault) {
                FileExportHelper.exportToPublicCategoryFolder(context, finalDest, category, finalDest.name)
            }

            MediaOutcome(
                filePath = finalDest.absolutePath,
                fileSize = finalDest.length(),
                category = category
            )
        } finally {
            tempBase.deleteRecursively()
        }
    }

    companion object {
        private val mediaHosts = listOf(
            "youtube.com", "youtu.be", "m.youtube.com", "music.youtube.com",
            "facebook.com", "fb.watch", "fb.com", "m.facebook.com",
            "tiktok.com", "vm.tiktok.com", "vt.tiktok.com",
            "instagram.com", "instagr.am",
            "twitter.com", "x.com",
            "reddit.com", "redd.it",
            "vimeo.com", "dailymotion.com", "soundcloud.com",
            "pinterest.com", "pin.it", "twitch.tv", "bilibili.com"
        )

        private val directExtensions = listOf(
            ".zip", ".rar", ".7z", ".apk", ".pdf", ".iso", ".exe",
            ".dmg", ".tar", ".gz", ".bin", ".docx", ".xlsx", ".pptx",
            ".jpg", ".jpeg", ".png", ".webp", ".gif"
        )

        fun isLikelyMediaPage(url: String): Boolean {
            val lower = url.lowercase()
            if (lower.contains(".m3u8") || lower.contains(".mpd")) return true
            if (mediaHosts.any { lower.contains(it) }) return true
            val path = lower.substringBefore("?").substringBefore("#")
            if (directExtensions.any { path.endsWith(it) }) return false
            if (path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".mp3")) return false
            return true
        }
    }
}
