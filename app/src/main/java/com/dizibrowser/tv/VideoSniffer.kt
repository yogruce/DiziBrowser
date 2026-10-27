package com.dizibrowser.tv

import android.net.Uri
import java.util.concurrent.CopyOnWriteArrayList

data class DetectedStream(
    val streamUrl: String,
    val pageUrl: String,
    val pageTitle: String,
    val referer: String,
    val userAgent: String,
    val formatLabel: String,
    val detectedAt: Long = System.currentTimeMillis()
)

object VideoSniffer {
    private val streams = CopyOnWriteArrayList<DetectedStream>()

    private val videoExtensions = listOf(
        ".m3u8", ".mp4", ".mpd", ".webm", ".mkv", ".ts"
    )

    private val ignoredSubstrings = listOf(
        "doubleclick", "googlesyndication", "adserver", "preroll", " Vast", "vast.xml", " preview"
    )

    fun inspectUrl(
        candidateUrl: String,
        pageUrl: String,
        pageTitle: String,
        userAgent: String,
        onNewStreamDetected: (DetectedStream) -> Unit
    ) {
        try {
            val lower = candidateUrl.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return
            if (AdBlocker.isBlocked(candidateUrl)) return
            if (ignoredSubstrings.any { lower.contains(it.trim()) }) return

            val uri = Uri.parse(candidateUrl)
            val path = (uri.path ?: "").lowercase()

            // .ts segmentlerini tek tek listelememek için m3u8 varsa .ts'leri atla
            if (path.endsWith(".ts") && streams.any { it.streamUrl.contains(".m3u8", ignoreCase = true) }) {
                return
            }

            val matchedExt = videoExtensions.firstOrNull { ext ->
                path.endsWith(ext) || lower.contains("$ext?") || lower.contains("mime=video/")
            } ?: return

            if (streams.any { it.streamUrl == candidateUrl }) return

            val label = when {
                matchedExt == ".m3u8" || lower.contains("m3u8") -> "HLS Akışı (.m3u8)"
                matchedExt == ".mpd" -> "DASH Akışı (.mpd)"
                matchedExt == ".mp4" || lower.contains("mp4") -> "MP4 Video"
                else -> "Video Akışı ($matchedExt)"
            }

            val detected = DetectedStream(
                streamUrl = candidateUrl,
                pageUrl = pageUrl,
                pageTitle = pageTitle.ifBlank { pageUrl },
                referer = pageUrl,
                userAgent = userAgent,
                formatLabel = label
            )
            streams.add(0, detected)
            if (streams.size > 25) {
                streams.removeAt(streams.size - 1)
            }
            onNewStreamDetected(detected)
        } catch (_: Exception) {}
    }

    fun getDetectedStreams(): List<DetectedStream> = streams.toList()

    fun clearForNewPage() {
        streams.clear()
    }
}
