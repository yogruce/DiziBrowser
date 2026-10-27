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
        "adroll.com", "sortable.com", "teads.tv", "outbrainimg.com",
        "pushwhy.com", "notix.io", "richpush.co", "evadav.com", "galaksion.com",
        "admaven.com", "clksite.com", "onclickads.net", "voluum.com",
        "bet365.com", "1xbet.com", "mostbet.com", "melbet.com"
    )

    private val blockedPathKeywords = arrayOf(
        "/ads/", "/adserver/", "/popunder", "/popup.js", "/pop.js",
        "banner_ad", "ad_iframe", "clickunder", "/advert/", "prebid"
    )

    private val dangerousExtensions = arrayOf(
        ".apk", ".xapk", ".apks", ".exe", ".msi", ".bat", ".cmd", ".scr", ".sh", ".jar", ".dex"
    )

    // Video segmentlerini (.ts, .m4s, .mp4, .m3u8) hiç bekletmeden geçirmek için hızlı kontrol
    private val mediaFastPassExtensions = arrayOf(
        ".ts", ".m4s", ".m3u8", ".mpd", ".mp4", ".webm", ".aac", ".mp3", ".vtt", ".srt", ".woff2", ".png", ".jpg", ".webp"
    )

    fun init(context: Context) {
        dynamicHosts.addAll(defaultBlockedHosts)
        val prefs = context.getSharedPreferences("adblock_prefs", Context.MODE_PRIVATE)
        val cached = prefs.getStringSet("cached_hosts", emptySet()) ?: emptySet()
        dynamicHosts.addAll(cached)
    }

    fun updateRemoteFilterList(context: Context, onComplete: (Int) -> Unit) {
        Thread {
            try {
                val url = URL("https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0&mimetype=plaintext")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "DiziBrowserTV/1.2")
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

    /**
     * O(1) Hash Tabanlı Anlık Kontrol:
     * 5.000 elemanlı listeyi tek tek taramak yerine sadece nokta (.) konumlarına göre 2-3 kez HashSet.contains çağırır.
     * Video akışı sırasında CPU kullanımını sıfıra indirir, takılmayı önler.
     */
    private fun isHostInSetFast(host: String): Boolean {
        if (dynamicHosts.contains(host)) return true
        var dotIndex = host.indexOf('.')
        while (dotIndex != -1 && dotIndex + 1 < host.length) {
            val parentDomain = host.substring(dotIndex + 1)
            if (dynamicHosts.contains(parentDomain)) return true
            dotIndex = host.indexOf('.', dotIndex + 1)
        }
        return false
    }

    fun isBlocked(url: String): Boolean {
        return try {
            val u = Uri.parse(url)
            val host = (u.host ?: "").lowercase().trimEnd('.')
            if (host.isEmpty()) return false

            // 1. O(1) Hızlı Domain Kontrolü
            if (isHostInSetFast(host)) {
                blockedCount.incrementAndGet()
                return true
            }

            // 2. Medya parçalarını (.ts, .m4s vb.) yol (path) taramasına sokmadan anında geçir
            val pathLower = (u.path ?: "").lowercase()
            if (mediaFastPassExtensions.any { pathLower.endsWith(it) }) {
                return false
            }

            // 3. Yalnızca script/sayfa isteklerinde yol kelimelerini kontrol et
            val pathBlocked = blockedPathKeywords.any { pathLower.contains(it) }
            if (pathBlocked) {
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
