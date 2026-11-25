package com.dizibrowser.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewMediaIntegrityApiStatusConfig
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var host: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var cursorOverlay: CursorOverlayView
    private lateinit var floatingCursorHint: TextView

    // Netflix Tarzı Sonraki Bölüm Overlay Bileşenleri
    private lateinit var netflixNextCard: LinearLayout
    private lateinit var netflixNextTitle: TextView
    private lateinit var netflixNextSubtitle: TextView
    private lateinit var netflixPlayNextBtn: Button
    private lateinit var netflixCancelNextBtn: Button

    private lateinit var backButton: Button
    private lateinit var homeButton: Button
    private lateinit var goButton: Button
    private lateinit var autoNextButton: Button
    private lateinit var watchPartyButton: Button
    private lateinit var historyButton: Button
    private lateinit var qrButton: Button
    private lateinit var updateButton: Button
    private lateinit var addressBar: EditText
    private lateinit var statusBar: TextView
    private lateinit var blockedCounterText: TextView
    private lateinit var cursorButton: Button
    private lateinit var favButton: Button

    private lateinit var history: WatchHistory
    private lateinit var watchPartyManager: WatchPartyManager
    private var isNavigatingFromWatchParty = false
    private var lastKnownPlaybackPosSec = 0.0
    private var lastKnownIsPlaying = false
    private var remoteServer: RemoteWebServer? = null

    private val historyIoExecutor = Executors.newSingleThreadExecutor()

    private var currentScreen = ScreenState.HOME
    private var customFullscreenView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var desktopUserAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 BrowseHere"
    private var isRequestedWithHeaderDisabledNatively = false

    override fun getPackageName(): String {
        return DiziApplication.resolvePackageName(super.getPackageName())
    }

    private var lastRecordedUrl = ""
    private var lastRecordAt = 0L

    // Otomatik Sonraki Bölüm, Otomatik Oynatma ve Otomatik Tam Ekran Durumu
    private var isAutoNextEnabled = true
    private var detectedNextEpisodeUrl: String = ""
    private var detectedNextEpisodeLabel: String = ""
    private var shouldAutoStartVideoAfterNextTransition = false
    private var autoNextTransitionActiveUntilMs = 0L
    private var autoNextPlayCompleted = false
    private var hasAutoFullscreenedCurrentPage = false
    private var playerCenterRatioX = 0.50f
    private var playerCenterRatioY = 0.45f
    private var autoPlayTapCount = 0
    private var isNetflixCountdownRunning = false
    private var isNetflixCountdownCancelledForCurrentPage = false
    private var netflixCountdownSeconds = 10

    // OK tuşuna basılı tutulduğunda web sayfasının yanlışlıkla tıklama algılamasını tamamen önleyen bayrak
    private var okLongPressConsumed = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideHintRunnable = Runnable {
        floatingCursorHint.visibility = View.GONE
    }
    private val hideNetflixPeekRunnable = Runnable {
        if (!isNetflixCountdownRunning) {
            netflixNextCard.visibility = View.GONE
        }
    }
    private val autoPlayTapAssistRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (currentScreen != ScreenState.WEB || now > autoNextTransitionActiveUntilMs || autoNextPlayCompleted || customFullscreenView != null) {
                return
            }
            // En fazla 2 nazik deneme yap, kullanıcıya her zaman müdahale alanı bırak
            if (autoPlayTapCount >= 2) return
            autoPlayTapCount++

            web.evaluateJavascript(
                "(function(){ var c = window[Symbol.for('__db_ctl')]; if (c && c.updateCoords) c.updateCoords(); })();",
                null
            )

            uiHandler.postDelayed({
                if (!autoNextPlayCompleted && customFullscreenView == null && currentScreen == ScreenState.WEB && System.currentTimeMillis() < autoNextTransitionActiveUntilMs) {
                    dispatchSyntheticTapOnPlayer()
                }
            }, 300L)

            if (autoPlayTapCount < 2) {
                uiHandler.postDelayed(this, 3200L)
            }
        }
    }
    private val autoFullscreenTapRunnable = Runnable {
        if (currentScreen == ScreenState.WEB && customFullscreenView == null && !hasAutoFullscreenedCurrentPage) {
            dispatchSyntheticTapOnPlayer()
        }
    }
    private val netflixCountdownTickRunnable = object : Runnable {
        override fun run() {
            if (!isNetflixCountdownRunning) return
            if (netflixCountdownSeconds <= 0) {
                isNetflixCountdownRunning = false
                netflixNextCard.visibility = View.GONE
                triggerTransitionToNextEpisode()
                return
            }
            val label = detectedNextEpisodeLabel.ifBlank { "Sonraki Bölüm" }
            netflixNextTitle.text = "⏭️ SIRADAKİ: ${label.uppercase(Locale.getDefault())}"
            netflixNextSubtitle.text = "Bölüm bitiyor • $netflixCountdownSeconds sn sonra geçilecek (İptal için Geri tuşu)"
            netflixPlayNextBtn.text = "▶ Şimdi Geç ($netflixCountdownSeconds)"
            netflixCountdownSeconds--
            uiHandler.postDelayed(this, 1000L)
        }
    }

    private enum class ScreenState {
        HOME, WEB, HISTORY
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        AdBlocker.init(this)
        history = WatchHistory(this)
        val settingsPrefs = getSharedPreferences("dizi_settings", Context.MODE_PRIVATE)
        isAutoNextEnabled = settingsPrefs.getBoolean("auto_next_enabled", true)

        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        host = findViewById(R.id.contentHost)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        cursorOverlay = findViewById(R.id.cursorOverlay)
        floatingCursorHint = findViewById(R.id.floatingCursorHint)

        netflixNextCard = findViewById(R.id.netflixNextCard)
        netflixNextTitle = findViewById(R.id.netflixNextTitle)
        netflixNextSubtitle = findViewById(R.id.netflixNextSubtitle)
        netflixPlayNextBtn = findViewById(R.id.netflixPlayNextBtn)
        netflixCancelNextBtn = findViewById(R.id.netflixCancelNextBtn)

        backButton = findViewById(R.id.backButton)
        homeButton = findViewById(R.id.homeButton)
        goButton = findViewById(R.id.goButton)
        autoNextButton = findViewById(R.id.autoNextButton)
        watchPartyButton = findViewById(R.id.watchPartyButton)
        historyButton = findViewById(R.id.historyButton)
        qrButton = findViewById(R.id.qrButton)
        updateButton = findViewById(R.id.updateButton)
        addressBar = findViewById(R.id.addressBar)
        statusBar = findViewById(R.id.statusBar)
        blockedCounterText = findViewById(R.id.blockedCounterText)
        cursorButton = findViewById(R.id.cursorButton)
        favButton = findViewById(R.id.favButton)

        setupWatchParty()
        setupWebView()
        setupTopBarButtons()
        setupNetflixCardButtons()
        startRemoteControlServer()
        updateAutoNextButtonUi()

        // Arka planda güncel reklam ve zararlı alan adı listesini çek
        AdBlocker.updateRemoteFilterList(this) { totalRules ->
            runOnUiThread {
                statusBar.text = "🛡️ Kalkan Aktif ($totalRules filtre) • OK Basılı Tut: İmleç Aç/Kapat"
            }
        }

        // Uygulama açılışında sessizce GitHub'da yeni APK güncellemesi var mı kontrol et
        AppUpdater.checkForUpdates(this, silentIfUpToDate = true)

        // Harici link ile açıldıysa doğrudan o adrese git
        val incomingUrl = intent?.dataString
        if (!incomingUrl.isNullOrBlank() && isSafeWebUrl(incomingUrl)) {
            addressBar.setText(incomingUrl)
            navigateFromAddress()
        } else {
            showHome()
        }
    }

    private fun setupNetflixCardButtons() {
        netflixPlayNextBtn.setOnClickListener {
            stopNetflixCountdown(cancelledByUser = false)
            triggerTransitionToNextEpisode()
        }
        netflixCancelNextBtn.setOnClickListener {
            stopNetflixCountdown(cancelledByUser = true)
            showFloatingCursorBanner("⏹️ Otomatik geçiş bu bölüm için iptal edildi")
        }
    }

    private fun hasDetectedNextEpisode(): Boolean {
        return detectedNextEpisodeUrl.isNotBlank() || detectedNextEpisodeLabel.isNotBlank()
    }

    /**
     * Netflix tarzı geri sayım kartını başlatır (Bölümün son 15 saniyesinde veya bittiğinde).
     * Tam ekran videonun da üzerinde görünür!
     */
    private fun startNetflixCountdown(initialSeconds: Int = 10) {
        if (!isAutoNextEnabled || !hasDetectedNextEpisode() || isNetflixCountdownCancelledForCurrentPage) return
        if (isNetflixCountdownRunning) return

        isNetflixCountdownRunning = true
        netflixCountdownSeconds = initialSeconds.coerceIn(8, 15)
        uiHandler.removeCallbacks(hideNetflixPeekRunnable)

        netflixCancelNextBtn.text = "İptal (Geri)"
        netflixNextCard.visibility = View.VISIBLE
        netflixNextCard.bringToFront()
        cursorOverlay.bringToFront()
        if (!cursorOverlay.isCursorEnabled) {
            netflixPlayNextBtn.requestFocus()
        }

        uiHandler.removeCallbacks(netflixCountdownTickRunnable)
        uiHandler.post(netflixCountdownTickRunnable)
    }

    /**
     * Kullanıcı tam ekranda veya video izlerken kumandaya / OK tuşuna bastığında,
     * eğer sitede "Sonraki Bölüm" varsa sağ altta 5 saniyeliğine Netflix tarzı "Sonraki Bölüme Geç" kartını gösterir!
     */
    private fun showNetflixNextEpisodePeek() {
        if (!isAutoNextEnabled || !hasDetectedNextEpisode() || isNetflixCountdownRunning) return
        if (currentScreen != ScreenState.WEB) return

        val label = detectedNextEpisodeLabel.ifBlank { "Sonraki Bölüm" }
        netflixNextTitle.text = "⏭️ SIRADAKİ: ${label.uppercase(Locale.getDefault())}"
        netflixNextSubtitle.text = "İstediğiniz an sonraki bölüme geçebilirsiniz"
        netflixPlayNextBtn.text = "▶ Sonraki Bölüme Geç"
        netflixCancelNextBtn.text = "Gizle"

        netflixNextCard.visibility = View.VISIBLE
        netflixNextCard.bringToFront()
        cursorOverlay.bringToFront()

        uiHandler.removeCallbacks(hideNetflixPeekRunnable)
        uiHandler.postDelayed(hideNetflixPeekRunnable, 5000L)
    }

    private fun stopNetflixCountdown(cancelledByUser: Boolean) {
        isNetflixCountdownRunning = false
        if (cancelledByUser) {
            isNetflixCountdownCancelledForCurrentPage = true
        }
        uiHandler.removeCallbacks(netflixCountdownTickRunnable)
        uiHandler.removeCallbacks(hideNetflixPeekRunnable)
        netflixNextCard.visibility = View.GONE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // WebView ve BuildInfo ilk kez başlatılırken de X-Requested-With paket maskesi (com.tcl.browser) aktif olmalı
        DiziApplication.spoofPackageNameForWebView = true

        web = object : WebView(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                // WebView'in kendi başına D-Pad YUKARI/AŞAĞI tuşlarıyla odak zıplaması (spatial navigation / page down) yapmasını tamamen engelle!
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        WebView.setWebContentsDebuggingEnabled(false)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            userAgentString = desktopUserAgent
        }

        // 1) WebView X-Requested-With uygulama kimliği başlığını tamamen gizle
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            try {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(web.settings, emptySet())
                isRequestedWithHeaderDisabledNatively = true
            } catch (_: Exception) {}
        }

        // 2) Sec-CH-UA (Client Hints) başlıklarındaki "Android WebView" ibaresini gerçek Google Chrome 131 ile değiştir
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            try {
                val brands = listOf(
                    UserAgentMetadata.BrandVersion.Builder()
                        .setBrand("Not(A:Brand")
                        .setMajorVersion("99")
                        .setFullVersion("99.0.0.0")
                        .build(),
                    UserAgentMetadata.BrandVersion.Builder()
                        .setBrand("Google Chrome")
                        .setMajorVersion("131")
                        .setFullVersion("131.0.6778.200")
                        .build(),
                    UserAgentMetadata.BrandVersion.Builder()
                        .setBrand("Chromium")
                        .setMajorVersion("131")
                        .setFullVersion("131.0.6778.200")
                        .build()
                )
                val uaMetadata = UserAgentMetadata.Builder()
                    .setBrandVersionList(brands)
                    .setFullVersion("131.0.6778.200")
                    .setPlatform("Windows")
                    .setPlatformVersion("10.0.0")
                    .setArchitecture("x86")
                    .setBitness(64)
                    .setModel("")
                    .setMobile(false)
                    .setWow64(false)
                    .build()
                WebSettingsCompat.setUserAgentMetadata(web.settings, uaMetadata)
            } catch (_: Exception) {}
        }

        // 3) WebView Media Integrity API üzerinden paket adı (com.dizibrowser.tv) sızıntısını kapat
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEBVIEW_MEDIA_INTEGRITY_API_STATUS)) {
            try {
                val integrityConfig = WebViewMediaIntegrityApiStatusConfig.Builder(
                    WebViewMediaIntegrityApiStatusConfig.WEBVIEW_MEDIA_INTEGRITY_API_ENABLED_WITHOUT_APP_IDENTITY
                ).build()
                WebSettingsCompat.setWebViewMediaIntegrityApiStatus(web.settings, integrityConfig)
            } catch (_: Exception) {}
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        // Yeni sürüme geçildiğinde önceden önbelleğe alınmış "Bu uygulama desteklenmiyor" sayfalarını temizle
        val cachePrefs = getSharedPreferences("dizi_settings", Context.MODE_PRIVATE)
        val curVerCode = AppUpdater.getCurrentVersionCode(this)
        if (cachePrefs.getInt("last_cache_cleared_version", 0) < curVerCode) {
            try {
                web.clearCache(true)
                cachePrefs.edit().putInt("last_cache_cleared_version", curVerCode).apply()
            } catch (_: Exception) {}
        }

        web.setDownloadListener(DownloadListener { url, _, _, mimetype, _ ->
            runOnUiThread {
                if (AdBlocker.isDangerousDownload(url) || mimetype?.contains("android.package-archive") == true) {
                    statusBar.text = "🛑 GÜVENLİK: Zararlı/APK dosya indirme girişimi engellendi!"
                    Toast.makeText(
                        this,
                        "🛡️ Güvenlik Kalkanı: Zararlı olabilecek APK/dosya indirmesi engellendi!",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    statusBar.text = "🛑 Güvenlik nedeniyle tarayıcıdan dosya indirme kapalıdır."
                }
            }
        })

        web.addJavascriptInterface(DiziJsBridge(), "DiziBridge")

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(web, getUniversalAllFramesScript(), setOf("*"))
            } catch (_: Exception) {}
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()

                // Sitelerdeki "Chrome'da aç" (intent://...) butonlarına basıldığında harici uygulama aramak yerine doğrudan temiz URL'yi aç
                if (url.startsWith("intent://", ignoreCase = true)) {
                    val cleanTarget = extractUrlFromIntentScheme(url)
                    if (cleanTarget != null && isSafeWebUrl(cleanTarget) && !AdBlocker.isBlocked(cleanTarget)) {
                        view.loadUrl(cleanTarget, mapOf("X-Requested-With" to "com.tcl.browser"))
                    }
                    return true
                }

                if (AdBlocker.isDangerousDownload(url)) {
                    updateBlockedCounterUi("🛑 APK/Zararlı dosya bağlantısı engellendi")
                    return true
                }

                if (!isSafeWebUrl(url) || AdBlocker.isBlocked(url)) {
                    updateBlockedCounterUi("🛡️ Reklam / yönlendirme engellendi")
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()

                if (AdBlocker.isBlocked(url) || AdBlocker.isDangerousDownload(url)) {
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        204,
                        "Blocked",
                        emptyMap(),
                        ByteArrayInputStream(ByteArray(0))
                    )
                }

                // hdfilmcehennemi "Close" (hdfilmcehennemi.mobi) gömülü oynatıcısını X-Requested-With sızıntısından tamamen izole ederek yükle
                if (request.method.equals("GET", ignoreCase = true) &&
                    url.contains("hdfilmcehennemi.mobi/video/embed/", ignoreCase = true) &&
                    !url.contains("/kn/", ignoreCase = true)
                ) {
                    val cleanMobiResp = fetchCleanMobiEmbedHtml(url, request)
                    if (cleanMobiResp != null) {
                        return cleanMobiResp
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                detectedNextEpisodeUrl = ""
                detectedNextEpisodeLabel = ""
                isNetflixCountdownCancelledForCurrentPage = false
                hasAutoFullscreenedCurrentPage = false
                playerCenterRatioX = 0.50f
                playerCenterRatioY = 0.45f
                stopNetflixCountdown(cancelledByUser = false)
                updateAutoNextButtonUi()
                addressBar.setText(url)
                updateFavoriteButtonState(url)
                updateBackButtonVisual()
                statusBar.text = "⏳ Yükleniyor: $url"
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView, url: String) {
                addressBar.setText(url)
                updateFavoriteButtonState(url)
                updateBackButtonVisual()
                updateBlockedCounterUi()
                statusBar.text = "🛡️ Kalkan Aktif • OK Basılı Tut: İmleç Aç/Kapat • En alta inince sayfa kayar"

                val pageTitle = view.title?.trim().orEmpty()
                if (shouldRecordPageInHistory(url)) {
                    recordHistoryAsync(url, pageTitle.ifBlank { url }, -1L)
                }

                if (watchPartyManager.isActive && shouldRecordPageInHistory(url)) {
                    if (isNavigatingFromWatchParty) {
                        isNavigatingFromWatchParty = false
                    } else {
                        watchPartyManager.publishEvent(
                            action = "URL_CHANGE",
                            episodeUrl = url,
                            isPlaying = false,
                            positionSec = 0.0,
                            force = true
                        )
                    }
                }

                injectPageEnhancements()

                if (System.currentTimeMillis() < autoNextTransitionActiveUntilMs && !autoNextPlayCompleted) {
                    uiHandler.removeCallbacks(autoPlayTapAssistRunnable)
                    uiHandler.postDelayed(autoPlayTapAssistRunnable, 1500L)
                }
                super.onPageFinished(view, url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (!isReload && shouldRecordPageInHistory(url)) {
                    isNetflixCountdownCancelledForCurrentPage = false
                    hasAutoFullscreenedCurrentPage = false
                    val pageTitle = view.title?.trim().orEmpty()
                    recordHistoryAsync(url, pageTitle.ifBlank { url }, -1L)
                }
            }

            override fun onSafeBrowsingHit(
                view: WebView,
                request: WebResourceRequest,
                threatType: Int,
                callback: SafeBrowsingResponse
            ) {
                statusBar.text = "🛑 Tehlikeli site tespit edildi ve engellendi!"
                callback.backToSafety(true)
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                statusBar.text = "🛑 Geçersiz SSL sertifikası reddedildi"
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                val currentUrl = view?.url ?: return
                val cleanTitle = title?.trim().orEmpty()
                if (cleanTitle.isNotBlank() && shouldRecordPageInHistory(currentUrl)) {
                    recordHistoryAsync(currentUrl, cleanTitle, -1L)
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                AdBlocker.blockedCount.incrementAndGet()
                updateBlockedCounterUi("🚫 Popup pencere engellendi")
                return false
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customFullscreenView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customFullscreenView = view
                customViewCallback = callback
                hasAutoFullscreenedCurrentPage = true
                autoNextPlayCompleted = true
                autoNextTransitionActiveUntilMs = 0L
                uiHandler.removeCallbacks(autoPlayTapAssistRunnable)
                uiHandler.removeCallbacks(autoFullscreenTapRunnable)

                topBar.visibility = View.GONE
                bottomBar.visibility = View.GONE
                fullscreenContainer.visibility = View.VISIBLE
                fullscreenContainer.removeAllViews()
                if (view != null) {
                    fullscreenContainer.addView(view, FrameLayout.LayoutParams(-1, -1))
                }
                val currentUrl = web.url.orEmpty()
                if (isSafeWebUrl(currentUrl)) {
                    recordHistoryAsync(currentUrl, web.title?.trim().orEmpty().ifBlank { currentUrl }, -1L)
                }
                // Tam ekranda Netflix kartı ve İmleç her zaman videonun üst katmanında kalsın
                netflixNextCard.bringToFront()
                cursorOverlay.bringToFront()
                floatingCursorHint.bringToFront()
                cursorOverlay.hideCursorImmediately()
                showFloatingCursorBanner("🎬 Tam Ekran • OK Basınca Sonraki Bölüm Kartı Çıkar • OK Basılı Tut: İmleç")
            }

            override fun onHideCustomView() {
                if (customFullscreenView == null) return
                fullscreenContainer.removeAllViews()
                fullscreenContainer.visibility = View.GONE
                customFullscreenView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                topBar.visibility = View.VISIBLE
                bottomBar.visibility = View.VISIBLE
                netflixNextCard.bringToFront()
                cursorOverlay.bringToFront()
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, false, false)
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
        }
    }

    private fun loadUrlStealth(targetUrl: String) {
        web.loadUrl(targetUrl, mapOf("X-Requested-With" to "com.tcl.browser"))
    }

    private fun fetchCleanMobiEmbedHtml(url: String, request: WebResourceRequest): WebResourceResponse? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                useCaches = false
            }

            val reqHeaders = request.requestHeaders ?: emptyMap()
            var hasReferer = false
            for ((k, v) in reqHeaders) {
                val kl = k.lowercase()
                if (kl == "x-requested-with" ||
                    kl == "accept-encoding" ||
                    kl == "host" ||
                    kl == "connection" ||
                    kl.startsWith("sec-ch-ua")
                ) {
                    continue
                }
                if (kl == "referer") hasReferer = true
                conn.setRequestProperty(k, v)
            }
            if (!hasReferer) {
                conn.setRequestProperty("Referer", "https://www.hdfilmcehennemi.nl/")
            }
            conn.setRequestProperty("User-Agent", desktopUserAgent)
            conn.setRequestProperty(
                "Sec-CH-UA",
                "\"Not(A:Brand\";v=\"99\", \"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\""
            )
            conn.setRequestProperty("Sec-CH-UA-Mobile", "?0")
            conn.setRequestProperty("Sec-CH-UA-Platform", "\"Windows\"")
            conn.setRequestProperty("Sec-Fetch-Dest", "iframe")
            conn.setRequestProperty("Sec-Fetch-Mode", "navigate")
            conn.setRequestProperty("Sec-Fetch-Site", "cross-site")

            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrBlank()) {
                conn.setRequestProperty("Cookie", cookie)
            }

            val status = conn.responseCode
            if (status !in 200..299) {
                conn.disconnect()
                return null
            }

            val headerFields = conn.headerFields
            val setCookies = headerFields["Set-Cookie"] ?: headerFields["set-cookie"]
            if (!setCookies.isNullOrEmpty()) {
                val cm = CookieManager.getInstance()
                for (sc in setCookies) {
                    if (!sc.isNullOrBlank()) {
                        cm.setCookie(url, sc)
                    }
                }
            }

            val rawStream = conn.inputStream
            val decodedStream = if (conn.contentEncoding?.equals("gzip", ignoreCase = true) == true) {
                GZIPInputStream(rawStream)
            } else {
                rawStream
            }
            val bytes = decodedStream.use { it.readBytes() }
            if (bytes.isEmpty()) return null

            val html = String(bytes, Charsets.UTF_8)
            if (html.contains("Bu uygulama desteklenmiyor", ignoreCase = true)) {
                return null
            }

            // DOCUMENT_START_SCRIPT desteklemeyen TV WebView'leri için evrensel betiği <head> başına doğrudan yerleştir
            val universalScriptTag = "<script>" + getUniversalAllFramesScript() + "</script>"
            val patchedHtml = if (html.contains("<head>", ignoreCase = true)) {
                html.replaceFirst(Regex("<head>", RegexOption.IGNORE_CASE), "<head>$universalScriptTag")
            } else {
                universalScriptTag + html
            }

            val respHeaders = mutableMapOf<String, String>()
            for ((k, values) in headerFields) {
                if (k == null || values.isNullOrEmpty()) continue
                val kl = k.lowercase()
                if (kl == "content-encoding" ||
                    kl == "content-length" ||
                    kl == "transfer-encoding" ||
                    kl == "x-frame-options" ||
                    kl == "content-security-policy" ||
                    kl == "content-security-policy-report-only"
                ) {
                    continue
                }
                respHeaders[k] = values.joinToString(", ")
            }

            WebResourceResponse(
                "text/html",
                "utf-8",
                200,
                "OK",
                respHeaders,
                ByteArrayInputStream(patchedHtml.toByteArray(Charsets.UTF_8))
            )
        } catch (_: Exception) {
            try {
                conn?.disconnect()
            } catch (_: Exception) {}
            null
        }
    }

    private fun extractUrlFromIntentScheme(intentUrl: String): String? {
        return try {
            val fallbackIdx = intentUrl.indexOf("S.browser_fallback_url=", ignoreCase = true)
            if (fallbackIdx != -1) {
                val start = fallbackIdx + "S.browser_fallback_url=".length
                val end = intentUrl.indexOf(';', start).let { if (it == -1) intentUrl.length else it }
                val decoded = Uri.decode(intentUrl.substring(start, end))
                if (isSafeWebUrl(decoded)) return decoded
            }
            val body = intentUrl.removePrefix("intent://").removePrefix("INTENT://")
            val hashIdx = body.indexOf("#Intent")
            val hostAndPath = if (hashIdx != -1) body.substring(0, hashIdx) else body
            val scheme = if (intentUrl.contains("scheme=http;", ignoreCase = true)) "http" else "https"
            val candidate = "$scheme://$hostAndPath"
            if (isSafeWebUrl(candidate)) candidate else null
        } catch (_: Exception) {
            null
        }
    }

    private fun shouldRecordPageInHistory(url: String): Boolean {
        if (!isSafeWebUrl(url)) return false
        val lower = url.lowercase()
        if (lower == "https://www.google.com/" || lower == "https://www.google.com" || lower.startsWith("https://www.google.com/search?")) {
            return false
        }
        return true
    }

    private fun recordHistoryAsync(url: String, title: String, positionMs: Long) {
        historyIoExecutor.execute {
            history.addOrUpdate(url, title, positionMs)
        }
    }

    private fun focusTopBarElementAtCursorX(cursorX: Float) {
        val candidates = listOf<View>(
            backButton,
            homeButton,
            addressBar,
            goButton,
            cursorButton,
            autoNextButton,
            watchPartyButton,
            favButton,
            historyButton,
            qrButton,
            updateButton
        ).filter { it.visibility == View.VISIBLE }

        var matchedView: View? = null
        val loc = IntArray(2)

        for (v in candidates) {
            v.getLocationOnScreen(loc)
            val left = loc[0].toFloat()
            val right = left + v.width
            if (cursorX in left..right) {
                matchedView = v
                break
            }
        }

        if (matchedView == null) {
            matchedView = candidates.minByOrNull { v ->
                v.getLocationOnScreen(loc)
                val center = loc[0] + v.width / 2f
                abs(cursorX - center)
            }
        }

        (matchedView ?: addressBar).requestFocus()
    }

    private fun showFloatingCursorBanner(msg: String) {
        floatingCursorHint.text = msg
        floatingCursorHint.visibility = View.VISIBLE
        floatingCursorHint.bringToFront()
        uiHandler.removeCallbacks(hideHintRunnable)
        uiHandler.postDelayed(hideHintRunnable, 4000L)
    }

    private fun setupWatchParty() {
        watchPartyManager = WatchPartyManager(object : WatchPartyManager.Listener {
            override fun onRoomStatusChanged(isActive: Boolean, roomCode: String, statusText: String) {
                if (isActive) {
                    watchPartyButton.text = "👥 Oda: $roomCode"
                    watchPartyButton.setTextColor(Color.parseColor("#7EE787"))
                } else {
                    watchPartyButton.text = "👥 Birlikte İzle"
                    watchPartyButton.setTextColor(Color.parseColor("#D2A8FF"))
                }
                statusBar.text = statusText
                showFloatingCursorBanner(statusText)
            }

            override fun onRemoteNavigateToUrl(url: String, senderId: String) {
                if (!isSafeWebUrl(url) || !shouldRecordPageInHistory(url)) return
                val currentUrl = if (currentScreen == ScreenState.WEB) (web.url ?: "") else ""
                val cleanRemote = url.trimEnd('/', '#')
                val cleanCurrent = currentUrl.trimEnd('/', '#')

                if (cleanRemote.isNotEmpty() && !cleanRemote.equals(cleanCurrent, ignoreCase = true)) {
                    isNavigatingFromWatchParty = true
                    if (customFullscreenView != null) {
                        web.webChromeClient?.onHideCustomView()
                    }
                    addressBar.setText(url)
                    navigateFromAddress()
                    showFloatingCursorBanner("👥 Birlikte İzle: Arkadaşınızın açtığı bölüm yükleniyor...")
                }
            }

            override fun onRemotePlaybackTrigger(action: String, positionSec: Double, isPlaying: Boolean) {
                val mins = (positionSec.toInt()) / 60
                val secs = (positionSec.toInt()) % 60
                val timeStr = "%02d:%02d".format(mins, secs)

                when (action) {
                    "PAUSE" -> showFloatingCursorBanner("👥 Birlikte İzle: Video duraklatıldı ($timeStr)")
                    "PLAY" -> showFloatingCursorBanner("👥 Birlikte İzle: Video başlatıldı ($timeStr)")
                    "SEEK" -> showFloatingCursorBanner("👥 Birlikte İzle: Video $timeStr konumuna sarıldı")
                }
            }

            override fun onNewMemberJoinedRoom() {
                showFloatingCursorBanner("👥 Arkadaşınız odaya katıldı! Mevcut bölüm ve saniye eşitleniyor...")
                val curUrl = if (currentScreen == ScreenState.WEB) (web.url ?: "") else ""
                if (isSafeWebUrl(curUrl) && shouldRecordPageInHistory(curUrl)) {
                    watchPartyManager.publishEvent(
                        action = "STATE_SYNC",
                        episodeUrl = curUrl,
                        isPlaying = lastKnownIsPlaying,
                        positionSec = lastKnownPlaybackPosSec,
                        force = true
                    )
                }
            }
        })
    }

    private fun showWatchPartyDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 32, 44, 24)
            setBackgroundColor(Color.parseColor("#161B22"))
        }

        val titleView = TextView(this).apply {
            text = "👥 BİRLİKTE İZLE (WATCH PARTY)"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#D2A8FF"))
        }
        container.addView(titleView)

        val descView = TextView(this).apply {
            text = if (watchPartyManager.isActive) {
                "🟢 Aktif Oda Kodu: ${watchPartyManager.roomCode}\n\n" +
                    "Farklı mekanlardaki arkadaşlarınız uygulamadaki '👥 Birlikte İzle' menüsünden (veya PC eklentisinden) bu kodu girerek aynı bölümü sizinle senkronize izleyebilir."
            } else {
                "Farklı evlerde / farklı bilgisayarlarda veya TV'lerde internet üzerinden aynı dizi bölümünü saniyesi saniyesine senkronize izleyin.\n\n" +
                    "• Bir kişi videoyu duraklattığında, başlattığında veya sardığında herkesin ekranında otomatik eşitlenir."
            }
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 12, 0, 18)
        }
        container.addView(descView)

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .create()

        if (watchPartyManager.isActive) {
            container.addView(Button(this).apply {
                text = "🔄 Şu Anki Bölüm ve Saniyeyi Herkese Eşitle"
                textSize = 13f
                isAllCaps = false
                setTextColor(Color.WHITE)
                setBackgroundResource(R.drawable.tv_button_bg)
                setPadding(24, 14, 24, 14)
                setOnClickListener {
                    val curUrl = if (currentScreen == ScreenState.WEB) (web.url ?: "") else ""
                    watchPartyManager.publishEvent(
                        action = "STATE_SYNC",
                        episodeUrl = curUrl,
                        isPlaying = lastKnownIsPlaying,
                        positionSec = lastKnownPlaybackPosSec,
                        force = true
                    )
                    showFloatingCursorBanner("👥 Mevcut sahne ve saniye odaya gönderildi!")
                    dialog.dismiss()
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 6, 0, 10) })

            container.addView(Button(this).apply {
                text = "🚪 Odadan Ayrıl (Senkronizasyonu Kapat)"
                textSize = 13f
                isAllCaps = false
                setTextColor(Color.parseColor("#FF7B72"))
                setBackgroundResource(R.drawable.tv_button_bg)
                setPadding(24, 14, 24, 14)
                setOnClickListener {
                    watchPartyManager.leaveRoom()
                    dialog.dismiss()
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 10) })
        } else {
            val createBtn = Button(this).apply {
                text = "➕ Yeni Oda Kur (5 Haneli Kod Oluştur)"
                textSize = 14f
                isAllCaps = false
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#7EE787"))
                setBackgroundResource(R.drawable.tv_button_bg)
                setPadding(24, 14, 24, 14)
                setOnClickListener {
                    val curUrl = if (currentScreen == ScreenState.WEB) (web.url ?: "") else ""
                    val code = watchPartyManager.createNewRoom(curUrl)
                    dialog.dismiss()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("🎉 Watch Party Odası Kuruldu!")
                        .setMessage("Oda Kodunuz:  $code\n\nArkadaşınız kendi cihazında '👥 Birlikte İzle' butonuna basıp '$code' kodunu girdiğinde otomatik olarak açtığınız bölüme ve saniyeye bağlanacaktır.")
                        .setPositiveButton("Tamam", null)
                        .show()
                }
            }
            container.addView(createBtn, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 4, 0, 16) })

            val codeInput = EditText(this).apply {
                hint = "Arkadaşınızın Oda Kodunu Girin (Örn: 48291)"
                setSingleLine(true)
                textSize = 14f
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                setBackgroundResource(R.drawable.tv_button_bg)
                setPadding(20, 14, 20, 14)
            }
            container.addView(codeInput, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 10) })

            container.addView(Button(this).apply {
                text = "🔗 Bu Koda Katıl ve Senkronize Başla"
                textSize = 14f
                isAllCaps = false
                setTextColor(Color.parseColor("#00E5FF"))
                setBackgroundResource(R.drawable.tv_button_bg)
                setPadding(24, 14, 24, 14)
                setOnClickListener {
                    val entered = codeInput.text.toString().trim()
                    if (entered.length < 4) {
                        Toast.makeText(this@MainActivity, "Lütfen en az 4 haneli oda kodunu girin", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    watchPartyManager.joinRoom(entered, isCreator = false)
                    dialog.dismiss()
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 10) })
        }

        container.addView(Button(this).apply {
            text = "Kapat"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.LTGRAY)
            setBackgroundResource(R.drawable.tv_button_bg)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, -2))

        dialog.show()
    }

    private fun updateAutoNextButtonUi() {
        val hasNextEpisodeOnSite = hasDetectedNextEpisode()
        if (!hasNextEpisodeOnSite || currentScreen != ScreenState.WEB) {
            autoNextButton.visibility = View.GONE
            return
        }

        autoNextButton.visibility = View.VISIBLE
        if (isAutoNextEnabled) {
            autoNextButton.text = "⏭️ Oto-Bölüm: Açık"
            autoNextButton.setTextColor(Color.parseColor("#7EE787"))
        } else {
            autoNextButton.text = "⏭️ Oto-Bölüm: Kapalı"
            autoNextButton.setTextColor(Color.LTGRAY)
        }
    }

    private fun toggleAutoNextEpisodeMode() {
        if (!hasDetectedNextEpisode()) {
            Toast.makeText(
                this,
                "Bu sayfada 'Sonraki Bölüm' butonu tespit edilemediği için bu özellik pasiftir.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        isAutoNextEnabled = !isAutoNextEnabled
        if (!isAutoNextEnabled) {
            stopNetflixCountdown(cancelledByUser = false)
        }
        getSharedPreferences("dizi_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("auto_next_enabled", isAutoNextEnabled)
            .apply()

        updateAutoNextButtonUi()
        val msg = if (isAutoNextEnabled) {
            "⏭️ Netflix Tarzı Sonraki Bölüm: AÇIK (Bölüm sonunda geri sayımla otomatik geçer)"
        } else {
            "⏭️ Otomatik Sonraki Bölüm: KAPALI"
        }
        statusBar.text = msg
        showFloatingCursorBanner(msg)
    }

    private fun setupTopBarButtons() {
        backButton.setOnClickListener { handleTopBackAction() }
        homeButton.setOnClickListener { showHome() }
        goButton.setOnClickListener { navigateFromAddress() }
        historyButton.setOnClickListener { showHistory() }
        qrButton.setOnClickListener { showQrRemoteDialog() }
        updateButton.setOnClickListener { AppUpdater.checkForUpdates(this, silentIfUpToDate = false) }

        cursorButton.setOnClickListener {
            toggleCursorMode()
        }

        autoNextButton.setOnClickListener {
            toggleAutoNextEpisodeMode()
        }
        autoNextButton.setOnLongClickListener {
            if (hasDetectedNextEpisode()) {
                triggerTransitionToNextEpisode()
            }
            true
        }

        watchPartyButton.setOnClickListener {
            showWatchPartyDialog()
        }

        favButton.setOnClickListener {
            val currentUrl = addressBar.text.toString().trim()
            if (!isSafeWebUrl(currentUrl)) {
                Toast.makeText(this, "Önce bir dizi/film sitesi açın", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val title = if (currentScreen == ScreenState.WEB) (web.title ?: currentUrl) else currentUrl
            val added = history.toggleFavorite(title, currentUrl)
            updateFavoriteButtonState(currentUrl)
            Toast.makeText(
                this,
                if (added) "⭐ Favorilere eklendi" else "Favorilerden çıkarıldı",
                Toast.LENGTH_SHORT
            ).show()
            if (currentScreen == ScreenState.HOME) showHome()
        }

        addressBar.setOnEditorActionListener { _, _, _ ->
            navigateFromAddress()
            true
        }
    }

    private fun triggerTransitionToNextEpisode() {
        val nextUrl = detectedNextEpisodeUrl
        runOnUiThread {
            stopNetflixCountdown(cancelledByUser = false)
            if (customFullscreenView != null) {
                web.webChromeClient?.onHideCustomView()
            }
            shouldAutoStartVideoAfterNextTransition = true
            autoNextTransitionActiveUntilMs = System.currentTimeMillis() + 45_000L
            autoNextPlayCompleted = false
            hasAutoFullscreenedCurrentPage = false
            autoPlayTapCount = 0
            uiHandler.removeCallbacks(autoPlayTapAssistRunnable)
            uiHandler.removeCallbacks(autoFullscreenTapRunnable)

            if (nextUrl.isNotBlank() && isSafeWebUrl(nextUrl)) {
                showFloatingCursorBanner("⏭️ Sonraki bölüme geçiliyor • Otomatik başlatılıp tam ekran yapılacak...")
                statusBar.text = "⏭️ Sonraki bölüm açılıyor: $nextUrl"
                addressBar.setText(nextUrl)
                loadUrlStealth(nextUrl)
            } else {
                showFloatingCursorBanner("⏭️ Sonraki bölüme geçiliyor • Otomatik başlatılıp tam ekran yapılacak...")
                web.evaluateJavascript(
                    "(function(){ var c = window[Symbol.for('__db_ctl')]; if (c && c.clickNext) c.clickNext(); })();",
                    null
                )
                uiHandler.postDelayed(autoPlayTapAssistRunnable, 2000L)
            }
        }
    }

    /**
     * WebView üzerindeki video oynatıcı kutusunun merkezine gerçek bir donanımsal Android dokunuşu (MotionEvent) gönderir.
     * Bu sayede:
     * 1) Cross-Origin <iframe> içindeki oynatıcıların (VidMoly, CloseLoad, JWPlayer vb.) Play butonu tetiklenir.
     * 2) Chromium'un Tam Ekran (requestFullscreen) için zorunlu tuttuğu 'User Activation' izni otomatik kazanılır!
     */
    private fun dispatchSyntheticTapOnPlayer() {
        if (web.width <= 0 || web.height <= 0 || currentScreen != ScreenState.WEB) return
        val x = (playerCenterRatioX * web.width).coerceIn(60f, (web.width - 60).toFloat())
        val y = (playerCenterRatioY * web.height).coerceIn(60f, (web.height - 60).toFloat())

        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        web.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        uiHandler.postDelayed({
            val upTime = SystemClock.uptimeMillis()
            val upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
            web.dispatchTouchEvent(upEvent)
            upEvent.recycle()
        }, 45L)
    }

    private fun handleTopBackAction() {
        if (customFullscreenView != null) {
            web.webChromeClient?.onHideCustomView()
            return
        }
        if (currentScreen == ScreenState.HISTORY) {
            showHome()
            return
        }
        if (currentScreen == ScreenState.WEB) {
            if (web.canGoBack()) {
                web.goBack()
                activateWebCursorNow()
            } else {
                showHome()
            }
            return
        }
        Toast.makeText(this, "Zaten ana ekrandasınız", Toast.LENGTH_SHORT).show()
    }

    private fun updateBackButtonVisual() {
        val canGoBack = (currentScreen == ScreenState.WEB && web.canGoBack()) || currentScreen == ScreenState.HISTORY || currentScreen == ScreenState.WEB
        backButton.alpha = if (canGoBack) 1.0f else 0.55f
    }

    private fun toggleCursorMode() {
        val next = !cursorOverlay.isCursorEnabled
        cursorOverlay.isCursorEnabled = next
        cursorButton.text = "🖱️"
        cursorButton.setTextColor(if (next) Color.parseColor("#00E5FF") else Color.GRAY)
        cursorButton.alpha = if (next) 1.0f else 0.55f

        if (currentScreen == ScreenState.WEB) {
            if (next) {
                topBar.clearFocus()
                web.requestFocus()
                cursorOverlay.visibility = View.VISIBLE
                cursorOverlay.centerCursor()
                cursorOverlay.bringToFront()
                statusBar.text = "🖱️ İmleç Açık (Kapatmak için OK tuşuna basılı tutun)"
                showFloatingCursorBanner("🖱️ İmleç Açıldı (Kapatmak için OK tuşuna basılı tutun)")
            } else {
                cursorOverlay.visibility = View.GONE
                web.requestFocus()
                statusBar.text = "⌨️ İmleç Kapalı: Yön tuşları videoyu sarar / sayfada gezinir (Açmak için OK basılı tutun)"
                showFloatingCursorBanner("🚫 İmleç Kapatıldı (Tekrar açmak için OK basılı tutun)")
            }
        }
    }

    private fun activateWebCursorNow() {
        if (currentScreen == ScreenState.WEB && cursorOverlay.isCursorEnabled) {
            topBar.clearFocus()
            addressBar.clearFocus()
            web.requestFocus()
            cursorOverlay.visibility = View.VISIBLE
            cursorOverlay.bringToFront()
        }
    }

    private fun updateFavoriteButtonState(url: String) {
        val fav = history.isFavorite(url)
        favButton.text = if (fav) "★" else "☆"
        favButton.setTextColor(if (fav) Color.parseColor("#FFD700") else Color.LTGRAY)
    }

    private fun updateBlockedCounterUi(statusMsg: String? = null) {
        blockedCounterText.text = "🚫 Engellenen: ${AdBlocker.blockedCount.get()}"
        if (statusMsg != null) {
            statusBar.text = statusMsg
        }
    }

    private fun startRemoteControlServer() {
        remoteServer = RemoteWebServer(
            port = 8989,
            onNavigateUrl = { incoming ->
                runOnUiThread {
                    addressBar.setText(incoming)
                    navigateFromAddress()
                    Toast.makeText(this, "📱 Telefondan gönderilen adres açılıyor...", Toast.LENGTH_SHORT).show()
                }
            },
            onAddFavorite = { title, url ->
                runOnUiThread {
                    var normalized = url.trim()
                    if (!normalized.contains("://")) normalized = "https://$normalized"
                    history.toggleFavorite(title, normalized)
                    if (currentScreen == ScreenState.HOME) showHome()
                    Toast.makeText(this, "📱 Favori eklendi: $title", Toast.LENGTH_SHORT).show()
                }
            }
        ).also { it.start() }
    }

    private fun showQrRemoteDialog() {
        val ip = RemoteWebServer.getLocalIpAddress()
        val url = if (ip != null) "http://$ip:8989" else "Ağ bağlantısı kontrol ediliyor..."

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 32, 40, 24)
            setBackgroundColor(Color.parseColor("#161B22"))
        }

        container.addView(TextView(this).apply {
            text = "📱 Telefonla Link Gönder & Kumanda Et"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00E5FF"))
            gravity = Gravity.CENTER
        })

        container.addView(TextView(this).apply {
            text = "TV kumandasıyla uzun site adresi yazmak yerine, telefonunuzun kamerasıyla QR kodu okutun veya aşağıdaki adresi telefon tarayıcısında açın:"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 16)
        })

        if (ip != null) {
            val qrBmp = RemoteWebServer.generateQrBitmap(url, 320)
            if (qrBmp != null) {
                container.addView(ImageView(this).apply {
                    setImageBitmap(qrBmp)
                }, LinearLayout.LayoutParams(320, 320).apply {
                    gravity = Gravity.CENTER
                    setMargins(0, 8, 0, 16)
                })
            }
        }

        container.addView(TextView(this).apply {
            text = url
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#7EE787"))
            gravity = Gravity.CENTER
        })

        AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton("Tamam", null)
            .show()
    }

    private fun isSafeWebUrl(url: String): Boolean = try {
        val uri = Uri.parse(url)
        (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }

    private fun navigateFromAddress() {
        var raw = addressBar.text.toString().trim()
        if (raw.isBlank()) return
        if (!raw.contains("://")) {
            raw = if (raw.contains(" ") || !raw.contains(".")) {
                "https://www.google.com/search?q=" + URLEncoder.encode(raw, "UTF-8")
            } else {
                "https://$raw"
            }
        }
        if (!isSafeWebUrl(raw) || AdBlocker.isBlocked(raw)) {
            statusBar.text = "🛑 Bu adres güvenlik filtresi tarafından engellendi"
            return
        }

        currentScreen = ScreenState.WEB
        cursorOverlay.isCursorEnabled = true
        cursorButton.text = "🖱️"
        cursorButton.setTextColor(Color.parseColor("#00E5FF"))
        cursorButton.alpha = 1.0f
        updateBackButtonVisual()

        host.removeAllViews()
        (web.parent as? ViewGroup)?.removeView(web)
        host.addView(web, FrameLayout.LayoutParams(-1, -1))
        web.visibility = View.VISIBLE

        cursorOverlay.centerCursor()
        activateWebCursorNow()
        showFloatingCursorBanner("🖱️ İmleci Açmak/Kapatmak için OK tuşuna 1 sn basılı tutabilirsiniz")
        loadUrlStealth(raw)
    }

    private fun showHome() {
        currentScreen = ScreenState.HOME
        stopNetflixCountdown(cancelledByUser = false)
        updateBackButtonVisual()
        updateAutoNextButtonUi()
        cursorOverlay.visibility = View.GONE
        floatingCursorHint.visibility = View.GONE
        host.removeAllViews()
        (web.parent as? ViewGroup)?.removeView(web)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0D1117"))
        }

        val rootPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 32)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val verName = AppUpdater.getCurrentVersionName(this)
        titleCol.addView(TextView(this).apply {
            text = "📺 DİZİ BROWSER TV (v$verName)"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00E5FF"))
        })
        titleCol.addView(TextView(this).apply {
            text = "Android TV & Akıllı Kutular İçin Reklamsız, Korumalı Video Tarayıcısı"
            textSize = 13f
            setTextColor(Color.parseColor("#8B949E"))
        })
        headerRow.addView(titleCol)

        val updateHomeBtn = Button(this).apply {
            text = "🔄 Uygulamayı Güncelle"
            textSize = 13f
            setTextColor(Color.parseColor("#7EE787"))
            setBackgroundResource(R.drawable.tv_button_bg)
            setPadding(22, 14, 22, 14)
            setOnClickListener { AppUpdater.checkForUpdates(this@MainActivity, silentIfUpToDate = false) }
        }
        headerRow.addView(updateHomeBtn, LinearLayout.LayoutParams(-2, -2).apply {
            setMargins(0, 0, 10, 0)
        })

        val qrQuickBtn = Button(this).apply {
            text = "📱 Telefondan Link Gönder (QR)"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.tv_button_bg)
            setPadding(22, 14, 22, 14)
            setOnClickListener { showQrRemoteDialog() }
        }
        headerRow.addView(qrQuickBtn)
        rootPanel.addView(headerRow)

        val favHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        favHeader.addView(TextView(this).apply {
            text = "⭐ FAVORİ SİTELER & HIZLI ERİŞİM"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        favHeader.addView(Button(this).apply {
            text = "+ Yeni Favori Site Ekle"
            textSize = 12f
            setTextColor(Color.parseColor("#FFD700"))
            setBackgroundResource(R.drawable.tv_button_bg)
            setPadding(20, 8, 20, 8)
            setOnClickListener { showAddFavoriteDialog() }
        })
        rootPanel.addView(favHeader)

        val favScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val favRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 14)
        }

        history.getFavorites().forEach { fav ->
            val card = Button(this).apply {
                text = "🌐 ${fav.title}\n${fav.url.replace("https://", "").replace("http://", "").take(26)}"
                textSize = 13f
                isAllCaps = false
                setTextColor(Color.WHITE)
                setBackgroundResource(R.drawable.tv_button_bg)
                setPadding(26, 20, 26, 20)
                setOnClickListener {
                    addressBar.setText(fav.url)
                    navigateFromAddress()
                }
                setOnLongClickListener {
                    history.toggleFavorite(fav.title, fav.url)
                    Toast.makeText(this@MainActivity, "Favorilerden kaldırıldı: ${fav.title}", Toast.LENGTH_SHORT).show()
                    showHome()
                    true
                }
            }
            favRow.addView(card, LinearLayout.LayoutParams(330, 115).apply {
                setMargins(0, 0, 14, 0)
            })
        }
        favScroll.addView(favRow)
        rootPanel.addView(favScroll)

        rootPanel.addView(TextView(this).apply {
            text = "🕘 SON İZLENEN BÖLÜMLER & SAYFALAR (10 GÜN SAKLANIR)"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 14, 0, 10)
        })

        val recentItems = history.getHistory()
        if (recentItems.isEmpty()) {
            rootPanel.addView(TextView(this).apply {
                text = "Henüz izleme kaydı yok. Açtığınız dizi/film sayfaları ve kaldığınız dakika otomatik olarak burada görünür. 10 günü geçen kayıtlar kendiliğinden silinir."
                textSize = 13f
                setTextColor(Color.parseColor("#8B949E"))
                setPadding(16, 16, 16, 16)
                setBackgroundResource(R.drawable.tv_button_bg)
            })
        } else {
            val df = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
            recentItems.take(10).forEach { item ->
                val resumeInfo = if (item.resumePositionMs > 10_000L) {
                    val m = (item.resumePositionMs / 1000) / 60
                    val s = (item.resumePositionMs / 1000) % 60
                    " • ⏯️ Kaldığı yer: %02d:%02d".format(m, s)
                } else ""

                val openPageBtn = Button(this).apply {
                    text = "▶️ ${item.title}\n🔗 ${item.url}  •  🕒 ${df.format(Date(item.watchedAt))}$resumeInfo"
                    textSize = 13f
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setTextColor(Color.WHITE)
                    setBackgroundResource(R.drawable.tv_button_bg)
                    setPadding(24, 16, 24, 16)
                    setOnClickListener {
                        addressBar.setText(item.url)
                        navigateFromAddress()
                    }
                }
                rootPanel.addView(openPageBtn, LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins(0, 4, 0, 4)
                })
            }
        }

        scroll.addView(rootPanel)
        host.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        statusBar.text = "🛡️ Güvenli Başlangıç • Reklam & APK Kalkanı Aktif • 10 Günlük Bölüm Hafızası"
    }

    private fun showAddFavoriteDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 12)
        }
        val titleInput = EditText(this).apply {
            hint = "Site Adı (Örn: Yabancı Dizi)"
        }
        val urlInput = EditText(this).apply {
            hint = "Site Adresi (Örn: https://...)"
        }
        box.addView(titleInput)
        box.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle("⭐ Yeni Favori Site Ekle")
            .setView(box)
            .setPositiveButton("Kaydet") { _, _ ->
                var u = urlInput.text.toString().trim()
                val t = titleInput.text.toString().trim()
                if (u.isNotBlank()) {
                    if (!u.contains("://")) u = "https://$u"
                    history.toggleFavorite(t.ifBlank { u }, u)
                    showHome()
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showHistory() {
        currentScreen = ScreenState.HISTORY
        stopNetflixCountdown(cancelledByUser = false)
        updateBackButtonVisual()
        updateAutoNextButtonUi()
        cursorOverlay.visibility = View.GONE
        floatingCursorHint.visibility = View.GONE
        host.removeAllViews()
        (web.parent as? ViewGroup)?.removeView(web)

        val scroller = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0D1117"))
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 24, 36, 24)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }
        topRow.addView(TextView(this).apply {
            text = "🕘 SON 10 GÜNLÜK İZLEME GEÇMİŞİ"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        topRow.addView(Button(this).apply {
            text = "🗑️ Geçmişi Temizle"
            textSize = 13f
            setTextColor(Color.parseColor("#FF7B72"))
            setBackgroundResource(R.drawable.tv_button_bg)
            setPadding(20, 10, 20, 10)
            setOnClickListener {
                history.clearHistory()
                showHistory()
            }
        })
        list.addView(topRow)

        val items = history.getHistory()
        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Henüz kayıtlı bölüm yok. Bir dizi veya film sayfasını açtığınızda otomatik olarak kaydedilir ve 10 gün sonra silinir."
                textSize = 16f
                setTextColor(Color.LTGRAY)
                setPadding(12, 12, 12, 12)
            })
        } else {
            val df = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            items.forEach { item ->
                val resumeInfo = if (item.resumePositionMs > 10_000L) {
                    val m = (item.resumePositionMs / 1000) / 60
                    val s = (item.resumePositionMs / 1000) % 60
                    " • ⏯️ Kaldığı yer: %02d:%02d".format(m, s)
                } else ""

                list.addView(Button(this).apply {
                    text = "${item.title}\n${item.url}\nİzlenme: ${df.format(Date(item.watchedAt))}$resumeInfo"
                    textSize = 14f
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setTextColor(Color.WHITE)
                    setBackgroundResource(R.drawable.tv_button_bg)
                    setPadding(24, 18, 24, 18)
                    setOnClickListener {
                        addressBar.setText(item.url)
                        navigateFromAddress()
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 6, 0, 6) })
            }
        }

        scroller.addView(list)
        host.addView(scroller, FrameLayout.LayoutParams(-1, -1))
        statusBar.text = "${items.size} bölüm kaydı • Yalnızca cihazda tutulur ve 10 gün sonunda otomatik silinir"
    }

    /**
     * Hem ana sayfada (window.top) hem de tüm Cross-Origin video oynatıcı <iframe>'leri (VidMoly, CloseLoad,
     * Rapidrame, Ok.ru, Sibnet, JWPlayer, Plyr, VideoJS vb.) içinde çalışan evrensel otomasyon ve gizlilik betiği.
     */
    private fun getUniversalAllFramesScript(): String {
        return """
            (function() {
                var installSym = typeof Symbol === 'function' ? Symbol.for('__db_u_init') : '__db_u_init';
                var bridgeSym = typeof Symbol === 'function' ? Symbol.for('__db_bridge') : '__db_bridge';
                var ctlSym = typeof Symbol === 'function' ? Symbol.for('__db_ctl') : '__db_ctl';
                var upgSym = typeof Symbol === 'function' ? Symbol.for('__db_upg') : '__db_upg';
                var wpSym = typeof Symbol === 'function' ? Symbol.for('__db_wp') : '__db_wp';
                var endSym = typeof Symbol === 'function' ? Symbol.for('__db_end') : '__db_end';

                // 0) DiziBridge nesnesini sayfa/oynatıcı scriptlerinden tamamen gizle, yalnızca private referansta tut
                var Bridge = null;
                try {
                    if (window[bridgeSym]) {
                        Bridge = window[bridgeSym];
                    } else if (window.DiziBridge) {
                        Bridge = window.DiziBridge;
                        window[bridgeSym] = Bridge;
                    }
                    try {
                        delete window.DiziBridge;
                    } catch(e) {}
                    if ('DiziBridge' in window) {
                        Object.defineProperty(window, 'DiziBridge', {
                            get: function() { return undefined; },
                            set: function() {},
                            enumerable: false,
                            configurable: true
                        });
                    }
                } catch(e) {}

                if (window[installSym]) return;
                window[installSym] = true;

                // 1) WebView / In-App Browser Algılama Koruması (Chrome 131 Masaüstü & BrowseHere Uyumluluğu)
                try {
                    var uaBrands = [
                        { brand: 'Not(A:Brand', version: '99' },
                        { brand: 'Google Chrome', version: '131' },
                        { brand: 'Chromium', version: '131' }
                    ];
                    var fullVersionList = [
                        { brand: 'Not(A:Brand', version: '99.0.0.0' },
                        { brand: 'Google Chrome', version: '131.0.6778.200' },
                        { brand: 'Chromium', version: '131.0.6778.200' }
                    ];
                    var fakeUaData = {
                        brands: uaBrands,
                        mobile: false,
                        platform: 'Windows',
                        getHighEntropyValues: function(hints) {
                            return Promise.resolve({
                                architecture: 'x86',
                                bitness: '64',
                                brands: uaBrands,
                                fullVersionList: fullVersionList,
                                mobile: false,
                                model: '',
                                platform: 'Windows',
                                platformVersion: '10.0.0',
                                uaFullVersion: '131.0.6778.200',
                                wow64: false
                            });
                        },
                        toJSON: function() {
                            return { brands: uaBrands, mobile: false, platform: 'Windows' };
                        }
                    };
                    Object.defineProperty(Navigator.prototype, 'userAgentData', {
                        get: function() { return fakeUaData; },
                        configurable: true
                    });
                    Object.defineProperty(Navigator.prototype, 'platform', {
                        get: function() { return 'Win32'; },
                        configurable: true
                    });
                    Object.defineProperty(Navigator.prototype, 'vendor', {
                        get: function() { return 'Google Inc.'; },
                        configurable: true
                    });
                    Object.defineProperty(Navigator.prototype, 'maxTouchPoints', {
                        get: function() { return 0; },
                        configurable: true
                    });
                    Object.defineProperty(Navigator.prototype, 'webdriver', {
                        get: function() { return false; },
                        configurable: true
                    });
                    Object.defineProperty(Navigator.prototype, 'pdfViewerEnabled', {
                        get: function() { return true; },
                        configurable: true
                    });

                    if (!window.chrome) {
                        window.chrome = {};
                    }
                    if (!window.chrome.runtime) {
                        window.chrome.runtime = {
                            OnInstalledReason: { CHROME_UPDATE: 'chrome_update', INSTALL: 'install', SHARED_MODULE_UPDATE: 'shared_module_update', UPDATE: 'update' },
                            PlatformOs: { ANDROID: 'android', CROS: 'cros', LINUX: 'linux', MAC: 'mac', OPENBSD: 'openbsd', WIN: 'win' }
                        };
                    }
                    if (!window.chrome.app) {
                        window.chrome.app = { isInstalled: false };
                    }
                    if (!window.chrome.csi) {
                        window.chrome.csi = function() { return { startE: Date.now(), onloadT: Date.now(), pageT: 120, tran: 15 }; };
                    }
                    if (!window.chrome.loadTimes) {
                        window.chrome.loadTimes = function() { return { requestTime: Date.now() / 1000 }; };
                    }

                    var noopOpen = function open() { return null; };
                    noopOpen.toString = function() { return 'function open() { [native code] }'; };
                    window.open = noopOpen;

                    // hdfilmcehennemi.mobi Close oynatıcısının /video/embed/.../kn/ POST isteğini X-Requested-With sızıntısı olmadan DiziBridge üzerinden geçir
                    if (location.hostname && location.hostname.indexOf('hdfilmcehennemi.mobi') !== -1 && Bridge && Bridge.postClosePlayerKn) {
                        var origXhrOpen = XMLHttpRequest.prototype.open;
                        var origXhrSend = XMLHttpRequest.prototype.send;
                        XMLHttpRequest.prototype.open = function(method, url) {
                            this.__db_m = method;
                            this.__db_u = url;
                            return origXhrOpen.apply(this, arguments);
                        };
                        XMLHttpRequest.prototype.send = function(body) {
                            try {
                                if (this.__db_m && String(this.__db_m).toUpperCase() === 'POST' &&
                                    this.__db_u && String(this.__db_u).indexOf('/kn/') !== -1
                                ) {
                                    var self = this;
                                    var absUrl = new URL(this.__db_u, location.href).href;
                                    var respText = String(Bridge.postClosePlayerKn(absUrl, String(body || ''), location.href) || '');
                                    if (respText.length > 0) {
                                        Object.defineProperty(self, 'readyState', { get: function() { return 4; }, configurable: true });
                                        Object.defineProperty(self, 'status', { get: function() { return 200; }, configurable: true });
                                        Object.defineProperty(self, 'statusText', { get: function() { return 'OK'; }, configurable: true });
                                        Object.defineProperty(self, 'responseText', { get: function() { return respText; }, configurable: true });
                                        Object.defineProperty(self, 'response', { get: function() { return respText; }, configurable: true });
                                        setTimeout(function() {
                                            try { if (typeof self.onreadystatechange === 'function') self.onreadystatechange(); } catch(e) {}
                                            try { if (typeof self.onload === 'function') self.onload(); } catch(e) {}
                                            try { self.dispatchEvent(new Event('readystatechange')); } catch(e) {}
                                            try { self.dispatchEvent(new Event('load')); } catch(e) {}
                                        }, 15);
                                        return;
                                    }
                                }
                            } catch(e) {}
                            return origXhrSend.apply(this, arguments);
                        };
                    }
                } catch(e) {}

                var isTopFrame = (window === window.top);
                var activePlayingVideo = null;
                var needFullscreenGesture = false;
                var fullscreenGestureExpireAt = 0;

                // 2) Videoyu veya oynatıcı kapsayıcısını Tam Ekran (HTML5 Fullscreen) yapan yardımcı
                function enterPlayerFullscreen(v) {
                    try {
                        if (document.fullscreenElement || document.webkitFullscreenElement) return true;
                        var target = (v && v.closest && v.closest('.jwplayer, .video-js, .plyr, .artplayer-app, .dplayer, .clappr-player, .flowplayer, #player, .player'))
                            || (!isTopFrame ? document.documentElement : v)
                            || v;
                        if (target) {
                            var req = target.requestFullscreen || target.webkitRequestFullscreen || target.mozRequestFullScreen || target.msRequestFullscreen;
                            if (req) {
                                var p = req.call(target);
                                if (p && typeof p.catch === 'function') {
                                    p.catch(function() {
                                        tryFallbackFullscreen(v);
                                    });
                                }
                                return true;
                            }
                        }
                        return tryFallbackFullscreen(v);
                    } catch(e) {
                        return tryFallbackFullscreen(v);
                    }
                }

                function tryFallbackFullscreen(v) {
                    try {
                        if (v) {
                            var vReq = v.requestFullscreen || v.webkitRequestFullscreen || v.webkitEnterFullscreen;
                            if (vReq) {
                                vReq.call(v);
                                return true;
                            }
                        }
                        var fsBtns = document.querySelectorAll('.jw-icon-fullscreen, .vjs-fullscreen-control, [data-plyr="fullscreen"], .art-icon-fullscreen, .dplayer-full-icon, [aria-label*="Fullscreen" i], [title*="Fullscreen" i], [title*="Tam Ekran" i]');
                        if (fsBtns.length > 0) {
                            fsBtns[0].click();
                            return true;
                        }
                    } catch(e) {}
                    return false;
                }

                // 3) Capture-Phase (Yakalama Evresi) Dokunuş Dinleyicisi:
                function onCaptureGestureForFullscreen(e) {
                    try {
                        if (!needFullscreenGesture) return;
                        if (Date.now() > fullscreenGestureExpireAt || document.fullscreenElement || document.webkitFullscreenElement) {
                            needFullscreenGesture = false;
                            return;
                        }
                        var v = activePlayingVideo || document.querySelector('video');
                        if (!v) return;

                        if (e.type === 'pointerdown' || e.type === 'touchstart' || e.type === 'mousedown') {
                            e.stopImmediatePropagation();
                            return;
                        }

                        if (e.type === 'pointerup' || e.type === 'touchend' || e.type === 'mouseup' || e.type === 'click') {
                            e.stopImmediatePropagation();
                            e.preventDefault();
                            if (v.muted) v.muted = false;
                            if (v.volume < 0.2) v.volume = 1.0;
                            if (v.paused) v.play().catch(function(){});
                            enterPlayerFullscreen(v);
                            if (e.type === 'click') {
                                needFullscreenGesture = false;
                            }
                        }
                    } catch(err) {}
                }

                ['pointerdown', 'pointerup', 'touchstart', 'touchend', 'mousedown', 'mouseup', 'click'].forEach(function(evtName) {
                    window.addEventListener(evtName, onCaptureGestureForFullscreen, true);
                });

                // 4) Tüm <iframe>'lere autoplay ve fullscreen izni tanımla & birden fazla kaynak varsa Close yerine çalışan alternatife geç
                var switchedFromCloseOnce = false;
                function upgradeAllIframes() {
                    try {
                        if (isTopFrame && !switchedFromCloseOnce) {
                            var activeGroup = document.querySelector('.alternative-links[data-active="1"]') || document;
                            var altBtns = activeGroup.querySelectorAll('button.alternative-link');
                            if (altBtns.length > 1) {
                                var closeActive = false;
                                var fallbackBtn = null;
                                for (var b = 0; b < altBtns.length; b++) {
                                    var btnText = (altBtns[b].innerText || altBtns[b].textContent || '').trim().toLowerCase();
                                    var isActive = altBtns[b].getAttribute('data-active') === '1' || altBtns[b].classList.contains('active');
                                    if (btnText.indexOf('close') !== -1) {
                                        if (isActive) closeActive = true;
                                    } else if (!fallbackBtn) {
                                        fallbackBtn = altBtns[b];
                                    }
                                }
                                if (closeActive && fallbackBtn) {
                                    switchedFromCloseOnce = true;
                                    fallbackBtn.click();
                                }
                            }
                        }

                        var iframes = document.querySelectorAll('iframe');
                        for (var i = 0; i < iframes.length; i++) {
                            var ifr = iframes[i];
                            if (!ifr[upgSym]) {
                                ifr[upgSym] = true;
                                ifr.allowFullscreen = true;
                                ifr.setAttribute('allowfullscreen', 'true');
                                ifr.setAttribute('webkitallowfullscreen', 'true');
                                ifr.setAttribute('allow', 'autoplay; fullscreen; encrypted-media; picture-in-picture');
                            }
                        }
                    } catch(e) {}
                }

                // 5) Yalnızca Ana Sayfada (isTopFrame) Sonraki Bölüm ve Oynatıcı Konumu Tespiti
                function findNextEpisodeElement() {
                    var keywords = [
                        'sonraki bölüm', 'sonraki bolum', 'gelecek bölüm', 'sıradaki bölüm',
                        'next episode', 'sonraki >', 'sonraki »', 'bölüm ileri'
                    ];
                    var candidates = document.querySelectorAll('a[href], button, [role="button"], .next-episode, .sonraki-bolum, [class*="next-ep"], [id*="next"]');
                    for (var i = 0; i < candidates.length; i++) {
                        var el = candidates[i];
                        var text = ((el.innerText || el.textContent || '') + ' ' + (el.getAttribute('title') || '') + ' ' + (el.getAttribute('aria-label') || '')).toLowerCase().trim();
                        var cls = ((el.className || '') + ' ' + (el.id || '')).toLowerCase();
                        var href = el.getAttribute('href') || '';
                        if (href.startsWith('javascript:') || href === '#') href = '';

                        for (var k = 0; k < keywords.length; k++) {
                            if (text.indexOf(keywords[k]) !== -1) {
                                return { el: el, url: el.href || '', label: text.substring(0, 40) };
                            }
                        }
                        if ((cls.indexOf('next-ep') !== -1 || cls.indexOf('sonraki') !== -1 || el.getAttribute('rel') === 'next') && (el.href || el.tagName === 'BUTTON')) {
                            return { el: el, url: el.href || '', label: text || 'Sonraki Bölüm' };
                        }
                    }

                    var loc = location.href;
                    var m = loc.match(/(bolum|episode|ep|part)([-_\/])(\d+)/i);
                    if (m) {
                        var nextNum = parseInt(m[3], 10) + 1;
                        var nextPattern = m[1] + m[2] + nextNum;
                        var links = document.querySelectorAll('a[href]');
                        for (var j = 0; j < links.length; j++) {
                            var a = links[j];
                            if (a.href && a.href.toLowerCase().indexOf(nextPattern.toLowerCase()) !== -1) {
                                return { el: a, url: a.href, label: 'Bölüm ' + nextNum };
                            }
                        }
                    }
                    return null;
                }

                function findMainPlayerElement() {
                    var selectors = [
                        'iframe[src*="rplayer"]', 'iframe[src*="embed"]', 'iframe[src*="player"]',
                        'iframe[src*="vidmoly"]', 'iframe[src*="closeload"]', 'iframe[src*="rapid"]',
                        'iframe[src*="ok.ru"]', 'iframe[src*="sibnet"]', 'iframe[src*="contentx"]',
                        'iframe[src*="pichive"]', 'video', '.play-that-video', '[aria-label="Play video"]',
                        '.player-box', '#player', '.video-player', '.player-container',
                        '.embed-container', '#video-area', '.player', 'iframe'
                    ];
                    for (var s = 0; s < selectors.length; s++) {
                        var els = document.querySelectorAll(selectors[s]);
                        for (var i = 0; i < els.length; i++) {
                            var r = els[i].getBoundingClientRect();
                            if (r.width >= 240 && r.height >= 140) {
                                return els[i];
                            }
                        }
                    }
                    return null;
                }

                if (isTopFrame) {
                    window[ctlSym] = {
                        clickNext: function() {
                            var found = findNextEpisodeElement();
                            if (found && found.el) {
                                found.el.click();
                                return true;
                            }
                            return false;
                        },
                        updateCoords: function() {
                            try {
                                upgradeAllIframes();
                                var pEl = findMainPlayerElement();
                                if (pEl) {
                                    var r = pEl.getBoundingClientRect();
                                    if (r.width > 160 && r.height > 100 && window.innerWidth > 0 && window.innerHeight > 0) {
                                        var rx = (r.left + r.width / 2) / window.innerWidth;
                                        var ry = (r.top + r.height / 2) / window.innerHeight;
                                        if (Bridge && Bridge.onPlayerRectFound) {
                                            Bridge.onPlayerRectFound(rx, ry);
                                        }
                                    }
                                }
                            } catch(e) {}
                        }
                    };
                }

                function checkAndReportNextEpisode() {
                    if (!isTopFrame || !Bridge) return;
                    try {
                        upgradeAllIframes();
                        var found = findNextEpisodeElement();
                        if (found) {
                            Bridge.onNextEpisodeDetected(found.url || '', found.label || 'Sonraki Bölüm');
                        } else {
                            Bridge.onNextEpisodeDetected('', '');
                        }
                        var pEl = findMainPlayerElement();
                        if (pEl && window.innerWidth > 0 && window.innerHeight > 0) {
                            var r = pEl.getBoundingClientRect();
                            if (r.top >= 0 && r.bottom <= window.innerHeight && r.width >= 240 && r.height >= 140) {
                                Bridge.onPlayerRectFound((r.left + r.width / 2) / window.innerWidth, (r.top + r.height / 2) / window.innerHeight);
                            }
                        }
                    } catch(e) {}
                }

                // 6) Otomatik Oynatma Denemesi (Yalnızca sonraki bölüme otomatik geçildiğinde ve kesinlikle <a> linklerine dokunmadan!)
                function tryAutoPlayInCurrentFrame() {
                    try {
                        if (!Bridge || !Bridge.isAutoPlayPending || !Bridge.isAutoPlayPending()) return;
                        var videos = document.querySelectorAll('video');
                        for (var i = 0; i < videos.length; i++) {
                            var v = videos[i];
                            if (v.paused) {
                                v.muted = false;
                                v.play().catch(function() {
                                    v.muted = true;
                                    v.play().catch(function(){});
                                });
                            }
                        }
                        // Yalnızca gerçek video oynatıcılarının kendi iç Play butonlarına tıkla (Kesinlikle <a> linklerine tıklama!)
                        var playBtns = document.querySelectorAll('.play-that-video, [aria-label="Play video"], .jw-icon-display, .jw-display-icon-container, .vjs-big-play-button, .plyr__control--overlaid, .art-state, .dplayer-play-icon, .fp-play');
                        for (var b = 0; b < playBtns.length; b++) {
                            var btn = playBtns[b];
                            if (btn.closest && btn.closest('a')) continue;
                            var br = btn.getBoundingClientRect();
                            if (br.width > 15 && br.height > 15) {
                                btn.click();
                                break;
                            }
                        }
                    } catch(e) {}
                }

                var recorded = false;
                var tickCount = 0;
                var wasPlaying = false;
                var countdownRequested = false;
                var autoFsTriggeredInFrame = false;
                var lastAppliedWpSeq = 0;
                var suppressWpLocalEventsUntil = 0;

                // 7) Watch Party (Birlikte İzle) Gerçek Zamanlı Video Senkronizasyonu (Cross-Origin Iframe Dahil!)
                function bindWatchPartyToVideo(v) {
                    if (v[wpSym]) return;
                    v[wpSym] = true;

                    v.addEventListener('play', function() {
                        if (Date.now() > suppressWpLocalEventsUntil && v.currentTime > 0.5 && Bridge) {
                            try {
                                Bridge.onLocalWatchPartyVideoEvent('PLAY', v.currentTime, true);
                            } catch(e) {}
                        }
                    });

                    v.addEventListener('pause', function() {
                        if (Date.now() > suppressWpLocalEventsUntil && !v.ended && v.currentTime > 0.5 && Bridge) {
                            try {
                                Bridge.onLocalWatchPartyVideoEvent('PAUSE', v.currentTime, false);
                            } catch(e) {}
                        }
                    });

                    v.addEventListener('seeked', function() {
                        if (Date.now() > suppressWpLocalEventsUntil && v.currentTime > 0.5 && Bridge) {
                            try {
                                Bridge.onLocalWatchPartyVideoEvent('SEEK', v.currentTime, !v.paused);
                            } catch(e) {}
                        }
                    });
                }

                // Her 900ms'de bir Watch Party hedef durumunu kontrol edip videoyu milimetrik hizalar
                setInterval(function() {
                    try {
                        if (!Bridge || !Bridge.getWatchPartyTargetStateJson) return;
                        var rawWp = Bridge.getWatchPartyTargetStateJson();
                        if (!rawWp || rawWp.indexOf('"active":true') === -1) return;

                        var v = activePlayingVideo || document.querySelector('video');
                        if (!v) return;
                        var vr = v.getBoundingClientRect();
                        if (vr.width < 160 && vr.height < 100 && !document.fullscreenElement && !document.webkitFullscreenElement) return;

                        bindWatchPartyToVideo(v);
                        if (v.currentTime > 0.5) {
                            Bridge.updateLocalPlaybackSnapshot(v.currentTime, !v.paused);
                        }

                        var wp = JSON.parse(rawWp);
                        if (!wp || !wp.active || !wp.hasState) return;

                        if (wp.seq !== lastAppliedWpSeq) {
                            lastAppliedWpSeq = wp.seq;
                            suppressWpLocalEventsUntil = Date.now() + 2400;

                            if (wp.playing === false) {
                                if (wp.targetTime > 0.5 && Math.abs(v.currentTime - wp.targetTime) > 1.2) {
                                    v.currentTime = wp.targetTime;
                                }
                                if (!v.paused) {
                                    v.pause();
                                }
                            } else {
                                if (wp.targetTime > 0.5 && Math.abs(v.currentTime - wp.targetTime) > 2.0) {
                                    v.currentTime = wp.targetTime;
                                }
                                if (v.paused) {
                                    v.play().catch(function(){});
                                }
                            }
                        } else if (wp.playing === true && !v.paused && wp.targetTime > 1.0 && Math.abs(v.currentTime - wp.targetTime) > 4.2) {
                            suppressWpLocalEventsUntil = Date.now() + 2400;
                            v.currentTime = wp.targetTime;
                        }
                    } catch(e) {}
                }, 900);

                setInterval(function() {
                    try {
                        if (isTopFrame) {
                            checkAndReportNextEpisode();
                        }
                        tryAutoPlayInCurrentFrame();

                        var videos = document.querySelectorAll('video');
                        var anyPlaying = false;
                        for (var i = 0; i < videos.length; i++) {
                            var v = videos[i];
                            var vr = v.getBoundingClientRect();
                            if (vr.width < 160 && vr.height < 100 && !document.fullscreenElement && !document.webkitFullscreenElement) {
                                continue;
                            }

                            bindWatchPartyToVideo(v);
                            var hasValidEpisodeDuration = (typeof v.duration === 'number' && isFinite(v.duration) && v.duration >= 900);

                            if (!v[endSym]) {
                                v[endSym] = true;
                                v.addEventListener('ended', function() {
                                    if (!countdownRequested && Bridge && isFinite(v.duration) && v.duration >= 900 && tickCount >= 28 && v.currentTime >= (v.duration * 0.90)) {
                                        countdownRequested = true;
                                        Bridge.onStartNetflixCountdown(10);
                                    }
                                });
                            }

                            if (!v.paused && !v.ended && v.currentTime > 0.5 && v.readyState >= 2) {
                                anyPlaying = true;
                                activePlayingVideo = v;
                                tickCount++;

                                if (Bridge) {
                                    if (!recorded && tickCount >= 2) {
                                        recorded = true;
                                        Bridge.videoPlayed(document.title || '', location.href, Math.floor(v.currentTime * 1000));
                                    } else if (tickCount % 5 === 0) {
                                        Bridge.videoProgress(location.href, Math.floor(v.currentTime * 1000));
                                    }

                                    if (tickCount % 4 === 0 && Date.now() > suppressWpLocalEventsUntil) {
                                        Bridge.onLocalWatchPartyVideoEvent('HEARTBEAT', v.currentTime, true);
                                    }

                                    if (!autoFsTriggeredInFrame && !document.fullscreenElement && !document.webkitFullscreenElement) {
                                        if (isFinite(v.duration) && v.duration >= 600 && v.currentTime > 1.5) {
                                            if (Bridge.shouldAutoFullscreenNow && Bridge.shouldAutoFullscreenNow()) {
                                                autoFsTriggeredInFrame = true;
                                                needFullscreenGesture = true;
                                                fullscreenGestureExpireAt = Date.now() + 4500;
                                                enterPlayerFullscreen(v);
                                                Bridge.requestFullscreenTap();
                                            }
                                        }
                                    }

                                    if (!countdownRequested && hasValidEpisodeDuration && tickCount >= 28 && (v.currentTime / v.duration) >= 0.92 && (v.duration - v.currentTime) <= 15) {
                                        countdownRequested = true;
                                        var rem = Math.max(8, Math.min(15, Math.floor(v.duration - v.currentTime)));
                                        Bridge.onStartNetflixCountdown(rem);
                                    }
                                }
                            }
                        }

                        if (anyPlaying && !wasPlaying) {
                            wasPlaying = true;
                            if (Bridge) Bridge.onVideoStartedPlaying();
                        } else if (!anyPlaying) {
                            wasPlaying = false;
                        }
                    } catch(e) {}
                }, 2200);

                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', function() {
                        if (isTopFrame) checkAndReportNextEpisode();
                        tryAutoPlayInCurrentFrame();
                    });
                } else {
                    if (isTopFrame) checkAndReportNextEpisode();
                    tryAutoPlayInCurrentFrame();
                }
            })();
        """.trimIndent()
    }

    private fun injectPageEnhancements() {
        web.evaluateJavascript(AdBlocker.getCosmeticHideJs(), null)

        val focusAndPlayerHelperJs = """
            (function() {
                try {
                    var sym = typeof Symbol === 'function' ? Symbol.for('__db_focus_s') : '__db_focus_s';
                    if (document.head && !document[sym]) {
                        document[sym] = true;
                        var st = document.createElement('style');
                        st.innerHTML = `
                            *:focus, *:focus-visible {
                                outline: 3px solid #00E5FF !important;
                                outline-offset: 2px !important;
                                box-shadow: 0 0 12px #00E5FF !important;
                            }
                        `;
                        document.head.appendChild(st);
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        web.evaluateJavascript(focusAndPlayerHelperJs, null)

        // DOCUMENT_START_SCRIPT desteklemeyen eski WebView sürümleri için ana sayfaya da enjekte et
        web.evaluateJavascript(getUniversalAllFramesScript(), null)
    }

    inner class DiziJsBridge {
        @JavascriptInterface
        fun postClosePlayerKn(targetUrl: String, postBody: String, refererUrl: String): String {
            var conn: HttpURLConnection? = null
            return try {
                if (!targetUrl.contains("hdfilmcehennemi.mobi/", ignoreCase = true)) return ""
                conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                    useCaches = false
                    setRequestProperty("User-Agent", desktopUserAgent)
                    setRequestProperty("Accept", "text/plain, */*; q=0.01")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    setRequestProperty("X-Requested-With", "XMLHttpRequest")
                    setRequestProperty("Origin", "https://hdfilmcehennemi.mobi")
                    setRequestProperty("Referer", refererUrl.ifBlank { targetUrl })
                }
                val cookie = CookieManager.getInstance().getCookie(targetUrl)
                if (!cookie.isNullOrBlank()) {
                    conn.setRequestProperty("Cookie", cookie)
                }
                val bodyBytes = postBody.toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bodyBytes.size)
                conn.outputStream.use { it.write(bodyBytes) }

                val code = conn.responseCode
                if (code !in 200..299) {
                    conn.disconnect()
                    return ""
                }
                val rawStream = conn.inputStream
                val decodedStream = if (conn.contentEncoding?.equals("gzip", ignoreCase = true) == true) {
                    GZIPInputStream(rawStream)
                } else {
                    rawStream
                }
                val respText = decodedStream.use { it.readBytes().toString(Charsets.UTF_8) }
                respText.trim()
            } catch (_: Exception) {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {}
                ""
            }
        }

        @JavascriptInterface
        fun getWatchPartyTargetStateJson(): String {
            return watchPartyManager.getTargetStateJsonForWebView()
        }

        @JavascriptInterface
        fun updateLocalPlaybackSnapshot(positionSec: Double, isPlaying: Boolean) {
            lastKnownPlaybackPosSec = positionSec
            lastKnownIsPlaying = isPlaying
        }

        @JavascriptInterface
        fun onLocalWatchPartyVideoEvent(action: String, positionSec: Double, isPlaying: Boolean) {
            lastKnownPlaybackPosSec = positionSec
            lastKnownIsPlaying = isPlaying
            if (!watchPartyManager.isActive) return
            runOnUiThread {
                val mainUrl = if (currentScreen == ScreenState.WEB) (web.url ?: "") else ""
                watchPartyManager.publishEvent(
                    action = action,
                    episodeUrl = mainUrl,
                    isPlaying = isPlaying,
                    positionSec = positionSec,
                    force = (action == "PLAY" || action == "PAUSE" || action == "SEEK")
                )
            }
        }

        @JavascriptInterface
        fun isAutoPlayPending(): Boolean {
            return System.currentTimeMillis() < autoNextTransitionActiveUntilMs && !autoNextPlayCompleted
        }

        @JavascriptInterface
        fun shouldAutoFullscreenNow(): Boolean {
            if (!isAutoNextEnabled || hasAutoFullscreenedCurrentPage || customFullscreenView != null) {
                return false
            }
            // Yalnızca otomatik olarak sonraki bölüme geçildiğinde (autoNextTransitionActiveUntilMs) otomatik tam ekran yap;
            // ilk açılışta kullanıcının kaynak/altyazı seçimine asla müdahale etme!
            return System.currentTimeMillis() < autoNextTransitionActiveUntilMs
        }

        @JavascriptInterface
        fun onPlayerRectFound(ratioX: Float, ratioY: Float) {
            if (ratioX in 0.15f..0.85f && ratioY in 0.15f..0.85f) {
                playerCenterRatioX = ratioX
                playerCenterRatioY = ratioY
            }
        }

        @JavascriptInterface
        fun requestFullscreenTap() {
            runOnUiThread {
                if (customFullscreenView == null && !hasAutoFullscreenedCurrentPage && System.currentTimeMillis() < autoNextTransitionActiveUntilMs) {
                    uiHandler.removeCallbacks(autoFullscreenTapRunnable)
                    uiHandler.postDelayed(autoFullscreenTapRunnable, 400L)
                }
            }
        }

        @JavascriptInterface
        fun videoPlayed(title: String, url: String, positionMs: Long) {
            runOnUiThread {
                val mainUrl = web.url?.takeIf { isSafeWebUrl(it) } ?: url
                val mainTitle = web.title?.trim()?.takeIf { it.isNotBlank() } ?: title.ifBlank { mainUrl }
                val now = System.currentTimeMillis()
                if (isSafeWebUrl(mainUrl) && (mainUrl != lastRecordedUrl || now - lastRecordAt > 30_000L)) {
                    lastRecordedUrl = mainUrl
                    lastRecordAt = now
                    recordHistoryAsync(mainUrl, mainTitle, positionMs)
                }
            }
        }

        @JavascriptInterface
        fun videoProgress(url: String, positionMs: Long) {
            if (positionMs > 5000L) {
                runOnUiThread {
                    val mainUrl = web.url?.takeIf { isSafeWebUrl(it) } ?: url
                    if (isSafeWebUrl(mainUrl)) {
                        historyIoExecutor.execute {
                            history.updatePositionByUrlOrStream(mainUrl, "", positionMs)
                        }
                    }
                }
            }
        }

        @JavascriptInterface
        fun onVideoStartedPlaying() {
            runOnUiThread {
                autoNextPlayCompleted = true
                uiHandler.removeCallbacks(autoPlayTapAssistRunnable)
                cursorOverlay.hideCursorImmediately()
            }
        }

        @JavascriptInterface
        fun onNextEpisodeDetected(nextUrl: String, label: String) {
            runOnUiThread {
                val changed = (detectedNextEpisodeUrl != nextUrl || detectedNextEpisodeLabel != label)
                detectedNextEpisodeUrl = nextUrl.trim()
                detectedNextEpisodeLabel = label.trim()
                if (changed) {
                    updateAutoNextButtonUi()
                }
            }
        }

        @JavascriptInterface
        fun onStartNetflixCountdown(secondsRemaining: Int) {
            runOnUiThread {
                startNetflixCountdown(secondsRemaining)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isOkKey = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER

        // Kullanıcı kumandadan herhangi bir yön veya geri tuşuna bastığı anda otomatik dokunuşları iptal et (Tam müdahale alanı!)
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_BACK -> {
                    uiHandler.removeCallbacks(autoPlayTapAssistRunnable)
                    uiHandler.removeCallbacks(autoFullscreenTapRunnable)
                }
            }
        }

        // Eğer Netflix tarzı Sonraki Bölüm kartı odaklanmışsa:
        if (netflixNextCard.visibility == View.VISIBLE && netflixNextCard.hasFocus()) {
            return super.dispatchKeyEvent(event)
        }

        // Web ekranındayken OK tuşu: Kısa basma (Tıkla) ve 1 sn basılı tutma (İmleci Aç/Kapat)
        if (currentScreen == ScreenState.WEB && !topBar.hasFocus() && isOkKey) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    okLongPressConsumed = false
                } else if (event.repeatCount >= 3 && !okLongPressConsumed) {
                    okLongPressConsumed = true
                    toggleCursorMode()
                }
                return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (okLongPressConsumed) {
                    okLongPressConsumed = false
                    return true
                }

                // Eğer sağ üstteki Netflix kartı görünür durumdaysa ve kullanıcı imleci bizzat kartın üzerine getirip OK'a bastıysa:
                if (netflixNextCard.visibility == View.VISIBLE && cursorOverlay.isCursorEnabled) {
                    val loc = IntArray(2)
                    netflixNextCard.getLocationOnScreen(loc)
                    val cx = cursorOverlay.cursorX
                    val cy = cursorOverlay.cursorY
                    if (cx in loc[0].toFloat()..(loc[0] + netflixNextCard.width).toFloat() &&
                        cy in loc[1].toFloat()..(loc[1] + netflixNextCard.height).toFloat()
                    ) {
                        val cancelLoc = IntArray(2)
                        netflixCancelNextBtn.getLocationOnScreen(cancelLoc)
                        if (cx in cancelLoc[0].toFloat()..(cancelLoc[0] + netflixCancelNextBtn.width).toFloat()) {
                            stopNetflixCountdown(cancelledByUser = true)
                            showFloatingCursorBanner("⏹️ Otomatik geçiş bu bölüm için iptal edildi")
                        } else {
                            triggerTransitionToNextEpisode()
                        }
                        return true
                    }
                }

                val activeTarget = customFullscreenView ?: web
                if (cursorOverlay.isCursorEnabled) {
                    cursorOverlay.performClickOnTarget(activeTarget, web)
                } else {
                    web.evaluateJavascript(
                        """
                        (function() {
                            var el = document.activeElement;
                            if (el && el !== document.body && typeof el.click === 'function') {
                                el.click();
                            } else {
                                var v = document.querySelector('video');
                                if (v) { v.paused ? v.play() : v.pause(); }
                            }
                        })();
                        """.trimIndent(),
                        null
                    )
                }
                return true
            }
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (currentScreen == ScreenState.WEB) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        web.evaluateJavascript(
                            "(function(){var v=document.querySelector('video');if(v){v.paused?v.play():v.pause();}})()",
                            null
                        )
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        web.evaluateJavascript(
                            "(function(){var v=document.querySelector('video');if(v)v.currentTime+=10;})()",
                            null
                        )
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        web.evaluateJavascript(
                            "(function(){var v=document.querySelector('video');if(v)v.currentTime=Math.max(0,v.currentTime-10);})()",
                            null
                        )
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        if (hasDetectedNextEpisode()) {
                            triggerTransitionToNextEpisode()
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_PROG_YELLOW,
                    KeyEvent.KEYCODE_PROG_BLUE,
                    KeyEvent.KEYCODE_INFO -> {
                        toggleCursorMode()
                        return true
                    }
                }
            }

            // İmleç kapalıyken sağ altta Netflix kartı görünürse SAĞ veya AŞAĞI tuşuna basınca karta odaklanabilsin
            if (currentScreen == ScreenState.WEB && !cursorOverlay.isCursorEnabled && netflixNextCard.visibility == View.VISIBLE && !netflixNextCard.hasFocus()) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    netflixPlayNextBtn.requestFocus()
                    return true
                }
            }

            if (currentScreen == ScreenState.WEB && topBar.hasFocus() && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (cursorOverlay.isCursorEnabled) {
                    activateWebCursorNow()
                    return true
                } else {
                    topBar.clearFocus()
                    web.requestFocus()
                    return true
                }
            }

            if (currentScreen == ScreenState.WEB && cursorOverlay.isCursorEnabled && !topBar.hasFocus()) {
                val activeTarget = customFullscreenView ?: web
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        cursorOverlay.visibility = View.VISIBLE
                        cursorOverlay.handleDpadMove(
                            keyCode = event.keyCode,
                            targetView = activeTarget,
                            webView = web,
                            onReachTopEdge = {
                                if (customFullscreenView == null) {
                                    cursorOverlay.visibility = View.GONE
                                    focusTopBarElementAtCursorX(cursorOverlay.cursorX)
                                }
                            }
                        )
                        return true
                    }
                }
            }

            // İmleç kapalıyken de WebView'in kendi başına koca bir paragraf/sayfa atlamasını önle; AŞAĞI/YUKARI tuşlarıyla tek satır (28px) kaydır
            if (currentScreen == ScreenState.WEB && !cursorOverlay.isCursorEnabled && !topBar.hasFocus() && customFullscreenView == null) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        web.evaluateJavascript("window.scrollBy({ top: 28, behavior: 'instant' });", null)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        web.evaluateJavascript(
                            "(function(){ var y = window.pageYOffset || document.documentElement.scrollTop || document.body.scrollTop || 0; window.scrollBy({ top: -28, behavior: 'instant' }); return y; })();"
                        ) { res ->
                            val yVal = res?.trim()?.toDoubleOrNull()?.toInt() ?: 0
                            if (yVal <= 5 && web.scrollY <= 5) {
                                focusTopBarElementAtCursorX(cursorOverlay.cursorX)
                            }
                        }
                        return true
                    }
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            // ACTION_UP olayının WebView'e sızıp sayfa içi odak sıçraması (Page Down etkisi) yaratmasını engelle
            if (currentScreen == ScreenState.WEB && !topBar.hasFocus()) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isNetflixCountdownRunning || netflixNextCard.visibility == View.VISIBLE) {
            stopNetflixCountdown(cancelledByUser = true)
            return
        }
        if (customFullscreenView != null) {
            web.webChromeClient?.onHideCustomView()
            return
        }
        if (currentScreen == ScreenState.HISTORY) {
            showHome()
            return
        }
        if (currentScreen == ScreenState.WEB) {
            if (web.canGoBack()) {
                web.goBack()
            } else {
                showHome()
            }
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        watchPartyManager.leaveRoom(silent = true)
        remoteServer?.stop()
        historyIoExecutor.shutdownNow()
        try {
            web.stopLoading()
            web.removeJavascriptInterface("DiziBridge")
            web.loadUrl("about:blank")
            web.clearHistory()
            web.removeAllViews()
            web.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
