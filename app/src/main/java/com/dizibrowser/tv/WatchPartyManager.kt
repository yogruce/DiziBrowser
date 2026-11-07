package com.dizibrowser.tv

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Farklı mekanlardaki / farklı ağlardaki cihazların (TV veya PC) internet üzerinden
 * sunucu kurulumu gerektirmeden gerçek zamanlı olarak aynı dizi bölümünü ve video
 * saniyesini (Play / Pause / Seek) senkronize izlemesini sağlayan Watch Party yöneticisi.
 */
class WatchPartyManager(private val listener: Listener) {

    interface Listener {
        fun onRoomStatusChanged(isActive: Boolean, roomCode: String, statusText: String)
        fun onRemoteNavigateToUrl(url: String, senderId: String)
        fun onRemotePlaybackTrigger(action: String, positionSec: Double, isPlaying: Boolean)
        fun onNewMemberJoinedRoom()
    }

    data class SyncState(
        val seq: Long,
        val action: String,
        val episodeUrl: String,
        val isPlaying: Boolean,
        val positionSec: Double,
        val receivedAtMs: Long
    )

    private val uiHandler = Handler(Looper.getMainLooper())
    private val publishExecutor = Executors.newSingleThreadExecutor()
    private val clientId = UUID.randomUUID().toString().substring(0, 8)

    @Volatile
    var roomCode: String = ""
        private set

    private val activeFlag = AtomicBoolean(false)
    private val seqCounter = AtomicLong(0L)
    private var streamThread: Thread? = null

    @Volatile
    var latestRemoteState: SyncState? = null
        private set

    private var lastPublishedAtMs = 0L
    private var lastPublishedAction = ""
    private var lastPublishedPos = -1.0

    val isActive: Boolean
        get() = activeFlag.get() && roomCode.isNotBlank()

    fun createNewRoom(currentEpisodeUrl: String): String {
        val generatedCode = Random.nextInt(10000, 99999).toString()
        joinRoom(generatedCode, isCreator = true, currentEpisodeUrl = currentEpisodeUrl)
        return generatedCode
    }

    fun joinRoom(code: String, isCreator: Boolean = false, currentEpisodeUrl: String = "") {
        val cleanCode = code.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")
        if (cleanCode.length < 4) return

        leaveRoom(silent = true)

        roomCode = cleanCode
        activeFlag.set(true)
        latestRemoteState = null

        val roleMsg = if (isCreator) {
            "👥 Oda Kuruldu (Kod: $roomCode) • Arkadaşınız bu kodla katılabilir"
        } else {
            "👥 Odaya Bağlanıldı (Kod: $roomCode) • Senkronize ediliyor..."
        }
        uiHandler.post {
            listener.onRoomStatusChanged(true, roomCode, roleMsg)
        }

        startStreamWorker(cleanCode)

        if (isCreator && currentEpisodeUrl.startsWith("http")) {
            publishEvent("URL_CHANGE", currentEpisodeUrl, isPlaying = false, positionSec = 0.0, force = true)
        } else if (!isCreator) {
            publishEvent("JOIN_REQUEST", "", isPlaying = false, positionSec = 0.0, force = true)
        }
    }

    fun leaveRoom(silent: Boolean = false) {
        val wasActive = activeFlag.getAndSet(false)
        roomCode = ""
        latestRemoteState = null
        try {
            streamThread?.interrupt()
        } catch (_: Exception) {}
        streamThread = null

        if (wasActive && !silent) {
            uiHandler.post {
                listener.onRoomStatusChanged(false, "", "👥 Watch Party odasından çıkıldı")
            }
        }
    }

    fun publishEvent(
        action: String,
        episodeUrl: String,
        isPlaying: Boolean,
        positionSec: Double,
        force: Boolean = false
    ) {
        if (!isActive) return
        val now = System.currentTimeMillis()

        // Aynı olayın milisaniyeler içinde tekrar tekrar gönderilmesini önle
        if (!force && action == lastPublishedAction && (now - lastPublishedAtMs) < 900L && kotlin.math.abs(positionSec - lastPublishedPos) < 1.5) {
            return
        }
        lastPublishedAtMs = now
        lastPublishedAction = action
        lastPublishedPos = positionSec

        if (action == "PLAY" || action == "PAUSE" || action == "SEEK" || action == "STATE_SYNC") {
            val existingSeq = latestRemoteState?.seq ?: 0L
            latestRemoteState = SyncState(
                seq = existingSeq,
                action = action,
                episodeUrl = episodeUrl,
                isPlaying = isPlaying,
                positionSec = positionSec,
                receivedAtMs = now
            )
        }

        val targetTopic = getTopicName(roomCode)
        publishExecutor.execute {
            try {
                val payload = JSONObject().apply {
                    put("sender", clientId)
                    put("action", action)
                    put("url", episodeUrl)
                    put("playing", isPlaying)
                    put("pos", (Math.round(positionSec * 10.0) / 10.0))
                    put("ts", System.currentTimeMillis())
                }.toString()

                val conn = (URL("https://ntfy.sh/$targetTopic").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 6000
                    readTimeout = 6000
                    doOutput = true
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                    writer.flush()
                }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    /**
     * WebView içindeki tüm frame'lerin (Cross-Origin video oynatıcı iframe'leri dahil)
     * doğrudan okuyabileceği JSON durumunu döndürür.
     */
    fun getTargetStateJsonForWebView(): String {
        if (!isActive) {
            return """{"active":false}"""
        }
        val state = latestRemoteState ?: return """{"active":true,"hasState":false}"""
        val elapsedSec = if (state.isPlaying) {
            ((System.currentTimeMillis() - state.receivedAtMs).coerceAtLeast(0L)) / 1000.0
        } else {
            0.0
        }
        val expectedPos = state.positionSec + elapsedSec
        return JSONObject().apply {
            put("active", true)
            put("hasState", true)
            put("seq", state.seq)
            put("action", state.action)
            put("playing", state.isPlaying)
            put("targetTime", (Math.round(expectedPos * 10.0) / 10.0))
        }.toString()
    }

    private fun getTopicName(code: String): String {
        return "dizibrowser_wp_${code.lowercase()}"
    }

    private fun startStreamWorker(code: String) {
        val topic = getTopicName(code)
        val worker = Thread {
            // 1) Odaya ilk girildiğinde son 15 dakikadaki son durumu çek (Oda sahibinin açtığı bölümü yakalamak için)
            fetchRecentRoomHistory(topic)

            // 2) Gerçek zamanlı kesintisiz akış (JSON stream) dinle
            while (activeFlag.get() && roomCode == code && !Thread.currentThread().isInterrupted) {
                var conn: HttpURLConnection? = null
                try {
                    val streamUrl = URL("https://ntfy.sh/$topic/json")
                    conn = (streamUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8000
                        readTimeout = 65000
                        setRequestProperty("Accept", "application/x-ndjson, application/json")
                    }
                    if (conn.responseCode == 200) {
                        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                            var line: String? = null
                            while (activeFlag.get() && roomCode == code && reader.readLine().also { line = it } != null) {
                                val raw = line?.trim().orEmpty()
                                if (raw.isNotEmpty()) {
                                    handleIncomingNtfyLine(raw, isHistorical = false)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Bağlantı koptuğunda veya zaman aşımında 1.5 sn bekleyip otomatik yeniden bağlan
                    try {
                        Thread.sleep(1500L)
                    } catch (_: InterruptedException) {
                        break
                    }
                } finally {
                    try {
                        conn?.disconnect()
                    } catch (_: Exception) {}
                }
            }
        }.apply {
            isDaemon = true
            name = "WatchPartyStream-$code"
        }
        streamThread = worker
        worker.start()
    }

    private fun fetchRecentRoomHistory(topic: String) {
        var conn: HttpURLConnection? = null
        try {
            val pollUrl = URL("https://ntfy.sh/$topic/json?poll=1&since=15m")
            conn = (pollUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode == 200) {
                val lines = mutableListOf<String>()
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String? = null
                    while (reader.readLine().also { line = it } != null) {
                        val trimmed = line?.trim().orEmpty()
                        if (trimmed.isNotEmpty()) lines.add(trimmed)
                    }
                }
                // En son gönderilen geçerli mesajı uygula
                for (i in lines.indices.reversed()) {
                    if (handleIncomingNtfyLine(lines[i], isHistorical = true)) {
                        break
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun handleIncomingNtfyLine(rawLine: String, isHistorical: Boolean): Boolean {
        return try {
            val envelope = JSONObject(rawLine)
            if (envelope.optString("event") != "message") return false
            val msgStr = envelope.optString("message", "")
            if (!msgStr.startsWith("{")) return false

            val data = JSONObject(msgStr)
            val sender = data.optString("sender", "")
            if (sender.isBlank() || sender == clientId) return false

            val action = data.optString("action", "")
            val epUrl = data.optString("url", "")
            val playing = data.optBoolean("playing", false)
            val pos = data.optDouble("pos", 0.0)

            if (action == "JOIN_REQUEST") {
                if (!isHistorical) {
                    uiHandler.post {
                        listener.onNewMemberJoinedRoom()
                    }
                }
                return false
            }

            val seq = seqCounter.incrementAndGet()
            val newState = SyncState(
                seq = seq,
                action = action,
                episodeUrl = epUrl,
                isPlaying = playing,
                positionSec = pos,
                receivedAtMs = System.currentTimeMillis()
            )
            latestRemoteState = newState

            uiHandler.post {
                if (epUrl.startsWith("http")) {
                    listener.onRemoteNavigateToUrl(epUrl, sender)
                }
                if (action == "PLAY" || action == "PAUSE" || action == "SEEK" || action == "STATE_SYNC" || action == "HEARTBEAT") {
                    listener.onRemotePlaybackTrigger(action, pos, playing)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
