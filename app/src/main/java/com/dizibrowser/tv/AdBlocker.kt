package com.dizibrowser.tv

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object AdBlocker {

    val blockedCount = AtomicInteger(0)

    private val dynamicHosts = ConcurrentHashMap.newKeySet<String>()

    private val defaultBlockedHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "adnxs.com", "adsrvr.org", "taboola.com",
        "outbrain.com", "popads.net", "popcash.net", "propellerads.com",
        "exoclick.com", "trafficjunky.com", "adsterra.com", "hilltopads.net",
        "juicyads.com", "clickadu.com", "pushame.com", "revcontent.com",
        "mgid.com", "bidvertiser.com", "adcash.com", "ero-advertising.com",
        "adtng.com", "a-ads.com", "plugrush.com", "trafficstars.com",
        "zeroredirect1.com", "tsyndicate.com", "realsrv.com", "onclkds.com",
        "acint.net", "adriver.ru", "betrad.com", "criteo.com", "criteo.net",
        "histats.com", "mc.yandex.ru", "scorecardresearch.com", "quantserve.com",
        "zedo.com", "adform.net", "smartadserver.com", "rubiconproject.com",
        "pubmatic.com", "openx.net", "moatads.com", "amazon-adsystem.com",
        "adroll.com", "Sortable.com", "teads.tv", "outbrainimg.com",
        "pushwhy.com", "notix.io", "richpush.co", "evadav.com", "galaksion.com",
        "admaven.com", "clksite.com", " onclickads.net", "voluum.com",
        "bet365.com", "1xbet.com", "mostbet.com", "melbet.com"
    )

    private val blockedPathKeywords = listOf(
        "/ads/", "/adserver/", "/popunder", "/popup.js", "/pop.js",
        "banner_ad", "ad_iframe", "clickunder", "/advert/", "prebid"
    )

    private val dangerousExtensions = listOf(
        ".apk", ".xapk", ".apks", ".exe", ".msi", ".bat", ".cmd", ".scr", ".sh", ".jar", ".dex"
    )

    fun init(context: Context) {
        dynamicHosts.addAll(defaultBlockedHosts.map { it.trim().lowercase() })
        val prefs = context.getSharedPreferences("adblock_prefs", Context.MODE_PRIVATE)
        val cached = prefs.getStringSet("cached_hosts", emptySet()) ?: emptySet()
        dynamicHosts.addAll(cached)
    }

    fun updateRemoteFilterList(context: Context, onComplete: (Int) -> Unit) {
        Thread {
            try {
                // StevenBlack / Peter Lowe hafif reklam & zararlı sunucu listesi
                val url = URL("https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0&mimetype=plaintext")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "DiziBrowserTV/1.0")
                }
                val newHosts = mutableSetOf<String>()
                BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val clean = line!!.trim().lowercase()
                        if (clean.isNotEmpty() && !clean.startsWith("#") && clean.contains(".")) {
                            newHosts.add(clean)
                        }
                    }
                }
                if (newHosts.isNotEmpty()) {
                    dynamicHosts.addAll(newHosts)
                    context.getSharedPreferences("adblock_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putStringSet("cached_hosts", newHosts.take(5000).toSet())
                        .putLong("last_updated", System.currentTimeMillis())
                        .apply()
                }
                onComplete(dynamicHosts.size)
            } catch (_: Exception) {
                onComplete(dynamicHosts.size)
            }
        }.start()
    }

    fun isDangerousDownload(url: String): Boolean {
        return try {
            val path = (Uri.parse(url).path ?: "").lowercase()
            dangerousExtensions.any { path.endsWith(it) }
        } catch (_: Exception) {
            false
        }
    }

    fun isBlocked(url: String): Boolean {
        return try {
            val u = Uri.parse(url)
            val host = (u.host ?: "").lowercase().trimEnd('.')
            if (host.isEmpty()) return false

            val hostBlocked = dynamicHosts.any { host == it || host.endsWith(".$it") }
            val pathLower = (u.path ?: "").lowercase()
            val pathBlocked = blockedPathKeywords.any { pathLower.contains(it) }

            if (hostBlocked || pathBlocked) {
                blockedCount.incrementAndGet()
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun getCosmeticHideJs(): String {
        return """
            (function() {
                try {
                    var styleId = '__dizi_adblock_css';
                    if (!document.getElementById(styleId)) {
                        var style = document.createElement('style');
                        style.id = styleId;
                        style.innerHTML = `
                            iframe[src*="ads"], iframe[src*="doubleclick"], iframe[src*="pop"],
                            div[class*="rek-"], div[id*="reklam"], .reklam-alani, .ads-banner,
                            .popup-overlay, .ad-overlay, #preroll-ad, .preroll-container,
                            a[href*="bet"], a[href*="casino"], a[target="_blank"][rel*="nofollow"] > img {
                                display: none !important;
                                visibility: hidden !important;
                                width: 0 !important;
                                height: 0 !important;
                                pointer-events: none !important;
                            }
                        `;
                        document.head.appendChild(style);
                    }
                    window.open = function() { return null; };
                } catch(e) {}
            })();
        """.trimIndent()
    }
}
