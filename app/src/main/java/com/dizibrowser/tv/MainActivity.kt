package com.dizibrowser.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var host: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var cursorOverlay: CursorOverlayView
    private lateinit var floatingCursorHint: TextView
    private lateinit var backButton: Button
    private lateinit var addressBar: EditText
    private lateinit var statusBar: TextView
    private lateinit var blockedCounterText: TextView
    private lateinit var cursorButton: Button
    private lateinit var favButton: Button

    private lateinit var history: WatchHistory
    private var remoteServer: RemoteWebServer? = null

    private var currentScreen = ScreenState.HOME
    private var customFullscreenView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var desktopUserAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private var lastRecordedUrl = ""
    private var lastRecordAt = 0L

    // OK tuşuna basılı tutulduğunda web sayfasının yanlışlıkla tıklama algılamasını tamamen önleyen bayrak
    private var okLongPressConsumed = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideHintRunnable = Runnable {
        floatingCursorHint.visibility = View.GONE
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

        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        host = findViewById(R.id.contentHost)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        cursorOverlay = findViewById(R.id.cursorOverlay)
        floatingCursorHint = findViewById(R.id.floatingCursorHint)
        backButton = findViewById(R.id.backButton)
        addressBar = findViewById(R.id.addressBar)
        statusBar = findViewById(R.id.statusBar)
        blockedCounterText = findViewById(R.id.blockedCounterText)
        cursorButton = findViewById(R.id.cursorButton)
        favButton = findViewById(R.id.favButton)

        setupWebView()
        setupTopBarButtons()
        startRemoteControlServer()

        // Arka planda güncel reklam ve zararlı alan adı listesini çek
        AdBlocker.updateRemoteFilterList(this) { totalRules ->
            runOnUiThread {
                statusBar.text = "🛡️ Kalkan Aktif ($totalRules filtre) • OK Basılı Tut: İmleç Aç/Kapat"
            }
        }

        // Harici link ile açıldıysa doğrudan o adrese git
        val incomingUrl = intent?.dataString
        if (!incomingUrl.isNullOrBlank() && isSafeWebUrl(incomingUrl)) {
            addressBar.setText(incomingUrl)
            navigateFromAddress()
        } else {
            showHome()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        web = WebView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
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
            setSupportMultipleWindows(true) // onCreateWindow ile yakalayıp popup engellemek için
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

        // Zararlı / APK / EXE dosya indirme kilidi
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
                    runOnUiThread { updateBlockedCounterUi() }
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
                statusBar.text = "🛡️ Kalkan Aktif • OK Basılı Tut: İmleci Aç/Kapat • En alta inince sayfa kayar"
                injectPageEnhancements()
                super.onPageFinished(view, url)
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
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                // Popup reklam pencerelerini tamamen engelle
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
                // Tam ekrana geçince imleci ekrandan hemen gizle ve bilgi notunu 4 saniye göster
                cursorOverlay.bringToFront()
                cursorOverlay.hideCursorImmediately()
                showFloatingCursorBanner("🎬 Tam Ekran • İmleci Açmak/Kapatmak için OK tuşuna basılı tutun")
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

    private fun showFloatingCursorBanner(msg: String) {
        floatingCursorHint.text = msg
        floatingCursorHint.visibility = View.VISIBLE
        floatingCursorHint.bringToFront()
        uiHandler.removeCallbacks(hideHintRunnable)
        uiHandler.postDelayed(hideHintRunnable, 4000L)
    }

    private fun setupTopBarButtons() {
        backButton.setOnClickListener { handleTopBackAction() }
        findViewById<Button>(R.id.homeButton).setOnClickListener { showHome() }
        findViewById<Button>(R.id.goButton).setOnClickListener { navigateFromAddress() }
        findViewById<Button>(R.id.historyButton).setOnClickListener { showHistory() }
        findViewById<Button>(R.id.qrButton).setOnClickListener { showQrRemoteDialog() }

        cursorButton.setOnClickListener {
            toggleCursorMode()
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

        // Sayfa açılır açılmaz odağı üst çubuktan alıp imlece ver!
        cursorOverlay.centerCursor()
        activateWebCursorNow()
        showFloatingCursorBanner("🖱️ İmleci Açmak/Kapatmak için OK tuşuna 1 sn basılı tutabilirsiniz")
        web.loadUrl(raw)
    }

    private fun showHome() {
        currentScreen = ScreenState.HOME
        updateBackButtonVisual()
        // Ana ekranda kumandayla butonlar arasında standart geçiş yapılabilmesi için imleci gizle
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

        // Başlık ve Özet Kartı
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "📺 DİZİ BROWSER TV"
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

        val qrQuickBtn = Button(this).apply {
            text = "📱 Telefondan Link Gönder (QR)"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.tv_button_bg)
            setPadding(24, 14, 24, 14)
            setOnClickListener { showQrRemoteDialog() }
        }
        headerRow.addView(qrQuickBtn)
        rootPanel.addView(headerRow)

        // 1. FAVORİ SİTELER BÖLÜMÜ
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

        // 2. SON İZLENEN BÖLÜMLER (10 GÜNLÜK GEÇMİŞ & KALDIĞI YERDEN DEVAM)
        rootPanel.addView(TextView(this).apply {
            text = "🕘 SON İZLENEN BÖLÜMLER (10 GÜN OTOMATİK SAKLANIR)"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 14, 0, 10)
        })

        val recentItems = history.getHistory()
        if (recentItems.isEmpty()) {
            rootPanel.addView(TextView(this).apply {
                text = "Henüz izleme kaydı yok. Herhangi bir dizi/film sitesinde video başlattığınızda bölüm adı, site adresi ve kaldığınız dakika otomatik olarak burada görünür. 10 günü geçen kayıtlar kendiliğinden silinir."
                textSize = 13f
                setTextColor(Color.parseColor("#8B949E"))
                setPadding(16, 16, 16, 16)
                setBackgroundResource(R.drawable.tv_button_bg)
            })
        } else {
            val df = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
            recentItems.take(8).forEach { item ->
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
        updateBackButtonVisual()
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
                text = "Henüz kayıtlı bölüm yok. Bir diziyi izlemeye başladığınızda otomatik olarak kaydedilir ve 10 gün sonra silinir."
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
        // 1. Kozmetik reklam gizleyici CSS + İmleç kapalıyken odaklanan (focus) nesneleri parlak çerçeve içine alma
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

        // 2. HTML5 video izleme takipçisi ve kaldığı saniyeyi hatırlama
        val savedResumeSec = (history.getResumePosition(web.url ?: "", "") / 1000L)
        val trackerJs = """
            (function() {
                if (window.__diziTrackerInstalled) return;
                window.__diziTrackerInstalled = true;
                var recorded = false;
                var playedSeconds = 0;
                var resumeTarget = $savedResumeSec;
                var resumeApplied = false;
                var wasPlaying = false;

                setInterval(function() {
                    try {
                        var videos = document.querySelectorAll('video');
                        var anyPlaying = false;
                        for (var i = 0; i < videos.length; i++) {
                            var v = videos[i];
                            if (!resumeApplied && resumeTarget > 15 && v.readyState >= 1 && v.currentTime < 5) {
                                v.currentTime = resumeTarget;
                                resumeApplied = true;
                            }
                            if (!v.paused && !v.ended && v.currentTime > 0) {
                                anyPlaying = true;
                                playedSeconds++;
                                if (!recorded && playedSeconds >= 10) {
                                    recorded = true;
                                    DiziBridge.videoPlayed(document.title || location.hostname, location.href, Math.floor(v.currentTime * 1000));
                                } else if (playedSeconds % 15 === 0) {
                                    DiziBridge.videoProgress(location.href, Math.floor(v.currentTime * 1000));
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
                }, 1000);
            })();
        """.trimIndent()
        web.evaluateJavascript(trackerJs, null)
    }

    inner class DiziJsBridge {
        @JavascriptInterface
        fun videoPlayed(title: String, url: String, positionMs: Long) {
            runOnUiThread {
                val now = System.currentTimeMillis()
                if (isSafeWebUrl(url) && (url != lastRecordedUrl || now - lastRecordAt > 30_000L)) {
                    history.addOrUpdate(url, title, positionMs)
                    lastRecordedUrl = url
                    lastRecordAt = now
                    statusBar.text = "✅ Bölüm geçmişe kaydedildi (10 gün saklanır)"
                }
            }
        }

        @JavascriptInterface
        fun videoProgress(url: String, positionMs: Long) {
            runOnUiThread {
                if (isSafeWebUrl(url) && positionMs > 5000L) {
                    history.updatePositionByUrlOrStream(url, "", positionMs)
                }
            }
        }

        @JavascriptInterface
        fun onVideoStartedPlaying() {
            runOnUiThread {
                // Video oynamaya başladığında imleci hemen gizle ki görüntünün ortasında kalmasın
                cursorOverlay.hideCursorImmediately()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isOkKey = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER

        // Web ekranındayken OK tuşu hem kısa basma (Tıkla) hem de 1 sn basılı tutma (İmleci Aç/Kapat) için tamamen burada yönetilir.
        // Böylece uzun basıldığında arkadaki sitede yanlışlıkla ikinci bir linkin açılması %100 engellenir!
        if (currentScreen == ScreenState.WEB && !topBar.hasFocus() && isOkKey) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    okLongPressConsumed = false
                } else if (event.repeatCount >= 3 && !okLongPressConsumed) {
                    okLongPressConsumed = true
                    toggleCursorMode()
                }
                // Her durumda ACTION_DOWN olayını yutuyoruz ki WebView kendisi erken tıklama yapmasın
                return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (okLongPressConsumed) {
                    // Uzun basma ile imleç açıldı/kapandı; parmak çekildiğinde sitede hiçbir yere tıklama!
                    okLongPressConsumed = false
                    return true
                }
                // Kısa basıldıysa:
                val activeTarget = customFullscreenView ?: web
                if (cursorOverlay.isCursorEnabled) {
                    cursorOverlay.performClickOnTarget(activeTarget, web)
                } else {
                    // İmleç kapalıyken kısa OK basıldıysa seçili HTML elemanına veya videoya standart tıklama gönder
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
                    // Kumandadaki Menü, Sarı/Mavi renkli tuşlar veya Info tuşlarıyla da imleç açılıp kapatılabilir
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_PROG_YELLOW,
                    KeyEvent.KEYCODE_PROG_BLUE,
                    KeyEvent.KEYCODE_INFO -> {
                        toggleCursorMode()
                        return true
                    }
                }
            }

            // Üst çubukta iken AŞAĞI basıldığında hemen web sayfasındaki imlece (veya sayfaya) in
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

            // Web ekranında ve üst bar odaklanmamışken Sanal Kumanda İmleci kontrolü
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
                                    addressBar.requestFocus()
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
