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
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
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
    private lateinit var historyButton: Button
    private lateinit var qrButton: Button
    private lateinit var updateButton: Button
    private lateinit var addressBar: EditText
    private lateinit var statusBar: TextView
    private lateinit var blockedCounterText: TextView
    private lateinit var cursorButton: Button
    private lateinit var favButton: Button

    private lateinit var history: WatchHistory
    private var remoteServer: RemoteWebServer? = null

    private val historyIoExecutor = Executors.newSingleThreadExecutor()

    private var currentScreen = ScreenState.HOME
    private var customFullscreenView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var desktopUserAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private var lastRecordedUrl = ""
    private var lastRecordAt = 0L

    // Otomatik Sonraki Bölüm ve Netflix Geri Sayım Durumu
    private var isAutoNextEnabled = true
    private var detectedNextEpisodeUrl: String = ""
    private var detectedNextEpisodeLabel: String = ""
    private var shouldAutoStartVideoAfterNextTransition = false
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
            netflixNextSubtitle.text = "Sonraki bölüm $netflixCountdownSeconds saniye içinde başlıyor... (OK ile hemen geç)"
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
        historyButton = findViewById(R.id.historyButton)
        qrButton = findViewById(R.id.qrButton)
        updateButton = findViewById(R.id.updateButton)
        addressBar = findViewById(R.id.addressBar)
        statusBar = findViewById(R.id.statusBar)
        blockedCounterText = findViewById(R.id.blockedCounterText)
        cursorButton = findViewById(R.id.cursorButton)
        favButton = findViewById(R.id.favButton)

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
        netflixCountdownSeconds = initialSeconds.coerceIn(3, 15)
        uiHandler.removeCallbacks(hideNetflixPeekRunnable)

        netflixCancelNextBtn.text = "İptal"
        netflixNextCard.visibility = View.VISIBLE
        netflixNextCard.bringToFront()
        cursorOverlay.bringToFront()
        netflixPlayNextBtn.requestFocus()

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
        web = WebView(this).apply {
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

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)

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

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()

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

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                detectedNextEpisodeUrl = ""
                detectedNextEpisodeLabel = ""
                isNetflixCountdownCancelledForCurrentPage = false
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

                injectPageEnhancements()
                super.onPageFinished(view, url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (!isReload && shouldRecordPageInHistory(url)) {
                    isNetflixCountdownCancelledForCurrentPage = false
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
            if (nextUrl.isNotBlank() && isSafeWebUrl(nextUrl)) {
                showFloatingCursorBanner("⏭️ Sonraki bölüme geçiliyor ve otomatik başlatılıyor...")
                statusBar.text = "⏭️ Sonraki bölüm açılıyor: $nextUrl"
                addressBar.setText(nextUrl)
                web.loadUrl(nextUrl)
            } else {
                showFloatingCursorBanner("⏭️ Sonraki bölüm butonuna tıklanıyor...")
                web.evaluateJavascript(
                    "if (window.__diziClickNextEpisode) window.__diziClickNextEpisode();",
                    null
                )
            }
        }
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
        cursorButton.text = if (next) "🖱️ İmleç: Açık" else "🖱️ İmleç: Kapalı"
        cursorButton.setTextColor(if (next) Color.parseColor("#00E5FF") else Color.LTGRAY)

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
        favButton.text = if (fav) "★ Favoride" else "☆ Favori"
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
        cursorButton.text = "🖱️ İmleç: Açık"
        cursorButton.setTextColor(Color.parseColor("#00E5FF"))
        updateBackButtonVisual()

        host.removeAllViews()
        (web.parent as? ViewGroup)?.removeView(web)
        host.addView(web, FrameLayout.LayoutParams(-1, -1))
        web.visibility = View.VISIBLE

        cursorOverlay.centerCursor()
        activateWebCursorNow()
        showFloatingCursorBanner("🖱️ İmleci Açmak/Kapatmak için OK tuşuna 1 sn basılı tutabilirsiniz")
        web.loadUrl(raw)
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

    private fun injectPageEnhancements() {
        web.evaluateJavascript(AdBlocker.getCosmeticHideJs(), null)

        val focusAndPlayerHelperJs = """
            (function() {
                try {
                    var focusStyleId = '__dizi_tv_focus_css';
                    if (!document.getElementById(focusStyleId)) {
                        var st = document.createElement('style');
                        st.id = focusStyleId;
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

        if (shouldAutoStartVideoAfterNextTransition) {
            shouldAutoStartVideoAfterNextTransition = false
            web.evaluateJavascript(
                """
                (function() {
                    var attempts = 0;
                    var autoPlayTimer = setInterval(function() {
                        attempts++;
                        var v = document.querySelector('video');
                        if (v) {
                            v.play().catch(function(){});
                            clearInterval(autoPlayTimer);
                        } else {
                            var playBtns = document.querySelectorAll('.jw-icon-display, .vjs-big-play-button, .plyr__control--overlaid, [aria-label*="Play"], [title*="Oynat"]');
                            if (playBtns.length > 0) {
                                playBtns[0].click();
                                clearInterval(autoPlayTimer);
                            }
                        }
                        if (attempts > 8) clearInterval(autoPlayTimer);
                    }, 1200);
                })();
                """.trimIndent(),
                null
            )
        }

        val savedResumeSec = (history.getResumePosition(web.url ?: "", "") / 1000L)
        val trackerAndNextEpisodeJs = """
            (function() {
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

                window.__diziClickNextEpisode = function() {
                    var found = findNextEpisodeElement();
                    if (found && found.el) {
                        found.el.click();
                        return true;
                    }
                    return false;
                };

                function checkAndReportNextEpisode() {
                    try {
                        var found = findNextEpisodeElement();
                        if (found) {
                            DiziBridge.onNextEpisodeDetected(found.url || '', found.label || 'Sonraki Bölüm');
                        } else {
                            DiziBridge.onNextEpisodeDetected('', '');
                        }
                    } catch(e) {}
                }

                checkAndReportNextEpisode();
                setTimeout(checkAndReportNextEpisode, 3000);

                if (window.__diziTrackerInstalled) return;
                window.__diziTrackerInstalled = true;
                var recorded = false;
                var tickCount = 0;
                var resumeTarget = $savedResumeSec;
                var resumeApplied = false;
                var wasPlaying = false;
                var countdownRequested = false;

                setInterval(function() {
                    try {
                        checkAndReportNextEpisode();
                        var videos = document.querySelectorAll('video');
                        var anyPlaying = false;
                        for (var i = 0; i < videos.length; i++) {
                            var v = videos[i];
                            if (!v.__diziEndedBound) {
                                v.__diziEndedBound = true;
                                v.addEventListener('ended', function() {
                                    if (!countdownRequested && v.duration > 120) {
                                        countdownRequested = true;
                                        DiziBridge.onStartNetflixCountdown(5);
                                    }
                                });
                            }
                            if (!resumeApplied && resumeTarget > 15 && v.readyState >= 1 && v.currentTime < 5) {
                                v.currentTime = resumeTarget;
                                resumeApplied = true;
                            }
                            if (!v.paused && !v.ended && v.currentTime > 0) {
                                anyPlaying = true;
                                tickCount++;
                                if (!recorded && tickCount >= 2) {
                                    recorded = true;
                                    DiziBridge.videoPlayed(document.title || location.hostname, location.href, Math.floor(v.currentTime * 1000));
                                } else if (tickCount % 6 === 0) {
                                    DiziBridge.videoProgress(location.href, Math.floor(v.currentTime * 1000));
                                }
                                // Bölümün bitmesine 15 saniye veya daha az kaldıysa Netflix geri sayım kartını ekrana çıkar!
                                if (!countdownRequested && v.duration > 240 && (v.duration - v.currentTime) <= 15) {
                                    countdownRequested = true;
                                    var rem = Math.max(4, Math.floor(v.duration - v.currentTime));
                                    DiziBridge.onStartNetflixCountdown(rem);
                                }
                            }
                        }
                        if (anyPlaying && !wasPlaying) {
                            wasPlaying = true;
                            DiziBridge.onVideoStartedPlaying();
                        } else if (!anyPlaying) {
                            wasPlaying = false;
                        }
                    } catch (e) {}
                }, 4000);
            })();
        """.trimIndent()
        web.evaluateJavascript(trackerAndNextEpisodeJs, null)
    }

    inner class DiziJsBridge {
        @JavascriptInterface
        fun videoPlayed(title: String, url: String, positionMs: Long) {
            val now = System.currentTimeMillis()
            if (isSafeWebUrl(url) && (url != lastRecordedUrl || now - lastRecordAt > 30_000L)) {
                lastRecordedUrl = url
                lastRecordAt = now
                recordHistoryAsync(url, title, positionMs)
            }
        }

        @JavascriptInterface
        fun videoProgress(url: String, positionMs: Long) {
            if (isSafeWebUrl(url) && positionMs > 5000L) {
                historyIoExecutor.execute {
                    history.updatePositionByUrlOrStream(url, "", positionMs)
                }
            }
        }

        @JavascriptInterface
        fun onVideoStartedPlaying() {
            runOnUiThread {
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

        // Eğer Netflix tarzı Sonraki Bölüm kartı odaklanmışsa veya geri sayım çalışıyorsa:
        if (netflixNextCard.visibility == View.VISIBLE && netflixNextCard.hasFocus()) {
            return super.dispatchKeyEvent(event)
        }

        // Geri sayım aktifken odak kartta olmasa bile kısa OK tuşuna basılırsa hemen sonraki bölüme geç!
        if (isNetflixCountdownRunning && isOkKey && event.action == KeyEvent.ACTION_UP && !okLongPressConsumed) {
            triggerTransitionToNextEpisode()
            return true
        }

        // Web ekranındayken OK tuşu hem kısa basma (Tıkla + Netflix Sonraki Bölüm Kartını Göster) hem de 1 sn basılı tutma (İmleci Aç/Kapat)
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

                // Tam ekranda veya video izlerken OK tuşuna basıldığında, sitede sonraki bölüm varsa
                // sağ altta Netflix tarzı "Sonraki Bölüme Geç" kartını da çıkar!
                if (customFullscreenView != null || !cursorOverlay.isCursorEnabled) {
                    showNetflixNextEpisodePeek()
                }

                // Eğer imleç sağ alttaki Netflix kartının üzerindeyse ve OK'a basıldıysa doğrudan sonraki bölüme geç
                if (netflixNextCard.visibility == View.VISIBLE && cursorOverlay.isCursorEnabled) {
                    val loc = IntArray(2)
                    netflixNextCard.getLocationOnScreen(loc)
                    val cx = cursorOverlay.cursorX
                    val cy = cursorOverlay.cursorY
                    if (cx in loc[0].toFloat()..(loc[0] + netflixNextCard.width).toFloat() &&
                        cy in loc[1].toFloat()..(loc[1] + netflixNextCard.height).toFloat()
                    ) {
                        triggerTransitionToNextEpisode()
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
