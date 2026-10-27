package com.dizibrowser.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class WatchItem(
    val url: String,
    val title: String,
    val watchedAt: Long,
    val resumePositionMs: Long = 0L,
    val lastStreamUrl: String = ""
)

data class FavoriteSite(
    val title: String,
    val url: String
)

class WatchHistory(context: Context) {
    private val prefs = context.getSharedPreferences("watch_history_v1", Context.MODE_PRIVATE)
    private val historyKey = "items"
    private val favKey = "favorites"
    private val maxAgeMs = 10L * 24 * 60 * 60 * 1000 // 10 Gün

    init {
        if (!prefs.contains("initialized_defaults")) {
            val defaultFavs = listOf(
                FavoriteSite("Google Arama", "https://www.google.com"),
                FavoriteSite("YouTube", "https://www.youtube.com"),
                FavoriteSite("TRT İzle", "https://www.tabii.com"),
                FavoriteSite("PuhuTV", "https://puhutv.com"),
                FavoriteSite("Internet Archive", "https://archive.org/details/movies")
            )
            saveFavorites(defaultFavs)
            prefs.edit().putBoolean("initialized_defaults", true).apply()
        }
    }

    @Synchronized
    fun addOrUpdate(url: String, title: String, resumePositionMs: Long = -1L, streamUrl: String = "") {
        val current = getHistory().toMutableList()
        val existing = current.find { it.url == url }
        current.removeAll { it.url == url }

        val finalResume = if (resumePositionMs >= 0L) resumePositionMs else (existing?.resumePositionMs ?: 0L)
        val finalStream = streamUrl.ifBlank { existing?.lastStreamUrl ?: "" }
        val cleanTitle = title.trim().ifBlank { existing?.title ?: url }

        current.add(
            0,
            WatchItem(
                url = url,
                title = cleanTitle,
                watchedAt = System.currentTimeMillis(),
                resumePositionMs = finalResume,
                lastStreamUrl = finalStream
            )
        )
        saveHistoryList(current.take(100))
    }

    @Synchronized
    fun updatePositionByUrlOrStream(pageUrl: String, streamUrl: String, positionMs: Long) {
        val current = getHistory().toMutableList()
        val idx = current.indexOfFirst {
            it.url == pageUrl || (streamUrl.isNotBlank() && it.lastStreamUrl == streamUrl)
        }
        if (idx >= 0) {
            val item = current[idx]
            current[idx] = item.copy(
                watchedAt = System.currentTimeMillis(),
                resumePositionMs = positionMs,
                lastStreamUrl = streamUrl.ifBlank { item.lastStreamUrl }
            )
            saveHistoryList(current)
        } else if (pageUrl.isNotBlank()) {
            addOrUpdate(pageUrl, pageUrl, positionMs, streamUrl)
        }
    }

    @Synchronized
    fun getResumePosition(pageUrl: String, streamUrl: String): Long {
        val current = getHistory()
        return current.find {
            (pageUrl.isNotBlank() && it.url == pageUrl) ||
                (streamUrl.isNotBlank() && it.lastStreamUrl == streamUrl)
        }?.resumePositionMs ?: 0L
    }

    @Synchronized
    fun getHistory(): List<WatchItem> {
        val raw = prefs.getString(historyKey, "[]") ?: "[]"
        val now = System.currentTimeMillis()
        val valid = mutableListOf<WatchItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = o.optLong("watchedAt", 0L)
                // 10 günden eski kayıtları otomatik filtrele ve sil
                if (now >= t && (now - t) < maxAgeMs) {
                    valid += WatchItem(
                        url = o.optString("url"),
                        title = o.optString("title"),
                        watchedAt = t,
                        resumePositionMs = o.optLong("resumePositionMs", 0L),
                        lastStreamUrl = o.optString("lastStreamUrl", "")
                    )
                }
            }
            if (valid.size != arr.length()) {
                saveHistoryList(valid)
            }
        } catch (_: Exception) {
            prefs.edit().remove(historyKey).apply()
        }
        return valid
    }

    private fun saveHistoryList(items: List<WatchItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("url", item.url)
                put("title", item.title)
                put("watchedAt", item.watchedAt)
                put("resumePositionMs", item.resumePositionMs)
                put("lastStreamUrl", item.lastStreamUrl)
            })
        }
        prefs.edit().putString(historyKey, arr.toString()).apply()
    }

    @Synchronized
    fun clearHistory() {
        prefs.edit().remove(historyKey).apply()
    }

    @Synchronized
    fun getFavorites(): List<FavoriteSite> {
        val raw = prefs.getString(favKey, "[]") ?: "[]"
        val list = mutableListOf<FavoriteSite>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += FavoriteSite(o.optString("title"), o.optString("url"))
            }
        } catch (_: Exception) {}
        return list
    }

    @Synchronized
    fun toggleFavorite(title: String, url: String): Boolean {
        val current = getFavorites().toMutableList()
        val existing = current.indexOfFirst { it.url.equals(url, ignoreCase = true) }
        return if (existing >= 0) {
            current.removeAt(existing)
            saveFavorites(current)
            false
        } else {
            current.add(0, FavoriteSite(title.ifBlank { url }, url))
            saveFavorites(current)
            true
        }
    }

    @Synchronized
    fun isFavorite(url: String): Boolean {
        return getFavorites().any { it.url.equals(url, ignoreCase = true) }
    }

    private fun saveFavorites(list: List<FavoriteSite>) {
        val arr = JSONArray()
        list.forEach { fav ->
            arr.put(JSONObject().apply {
                put("title", fav.title)
                put("url", fav.url)
            })
        }
        prefs.edit().putString(favKey, arr.toString()).apply()
    }
}
