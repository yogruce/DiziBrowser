package com.dizibrowser.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import kotlin.math.max
import kotlin.math.min

class CursorOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var isCursorEnabled: Boolean = true
        set(value) {
            field = value
            visibility = if (value) VISIBLE else GONE
            isCursorVisible = value
            if (!value) {
                uiHandler.removeCallbacks(autoHideRunnable)
            }
            invalidate()
        }

    var cursorX: Float = 500f
        private set
    var cursorY: Float = 320f
        private set

    private var stepSize = 16f
    private var lastMoveTime = 0L
    private var isCursorVisible = true

    private val uiHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable {
        isCursorVisible = false
        invalidate()
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8800E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val cursorPath = Path()

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            cursorX = w / 2f
            cursorY = h / 2f
        }
    }

    fun centerCursor() {
        if (width > 0 && height > 0) {
            cursorX = width / 2f
            cursorY = height / 2f
            wakeUpCursor()
        }
    }

    fun hideCursorImmediately() {
        isCursorVisible = false
        uiHandler.removeCallbacks(autoHideRunnable)
        invalidate()
    }

    private fun wakeUpCursor() {
        isCursorVisible = true
        uiHandler.removeCallbacks(autoHideRunnable)
        // 3.5 saniye hareketsiz kalınca imleci gizle ki videoyu kapatmasın
        uiHandler.postDelayed(autoHideRunnable, 3500L)
        invalidate()
    }

    fun handleDpadMove(
        keyCode: Int,
        targetView: View?,
        webView: WebView?,
        onReachTopEdge: () -> Unit
    ): Boolean {
        if (!isCursorEnabled || visibility != VISIBLE) return false

        wakeUpCursor()

        val now = SystemClock.uptimeMillis()
        stepSize = if (now - lastMoveTime < 180) {
            min(stepSize + 3.0f, 34f)
        } else {
            15f
        }
        lastMoveTime = now

        val topEdgeThreshold = 42f
        val bottomEdgeThreshold = (height - 48).coerceAtLeast(120).toFloat()

        // Tek basışta çok ince (1 metin satırı = 28px), basılı tutulduğunda ise 40px kaydır
        val scrollDelta = if (stepSize > 22f) 40 else 28

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (cursorY <= topEdgeThreshold) {
                    // Sayfa aşağı kaymış durumdaysa önce sayfayı 1 satır yukarı kaydır, en tepeye gelince üst menüye çık
                    if (webView != null) {
                        scrollWebPageAndCheckTop(webView, -scrollDelta, onReachTopEdge)
                    } else {
                        onReachTopEdge()
                    }
                    dispatchHoverAndMouseMove(targetView, webView)
                    invalidate()
                    return true
                }
                cursorY = max(12f, cursorY - stepSize)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (cursorY >= bottomEdgeThreshold) {
                    // Ekranın alt sınırına gelindiğinde sayfayı 1 satır (28px) aşağı kaydır
                    if (webView != null) {
                        scrollWebPage(targetView ?: webView, webView, scrollDelta)
                    } else {
                        targetView?.scrollBy(0, scrollDelta)
                    }
                } else {
                    cursorY = min((height - 16).toFloat(), cursorY + stepSize)
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                cursorX = max(12f, cursorX - stepSize)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                cursorX = min((width - 16).toFloat(), cursorX + stepSize)
            }
            else -> return false
        }

        // Video player'ların alt barını (Play, Tam Ekran, Ses) görünür yapmak için Hover/MouseMove gönder
        dispatchHoverAndMouseMove(targetView, webView)
        invalidate()
        return true
    }

    /**
     * Tüm web sitelerinde CSS scroll-snap veya smooth-scroll kaynaklı tam sayfa zıplamalarını devre dışı bırakarak
     * her tuş basışında milimetrik olarak yalnızca 1 satır (deltaY piksel, örn. 28px) kaydıran motor.
     */
    private fun scrollWebPage(targetView: View, webView: WebView, deltaY: Int) {
        val normX = if (width > 0) (cursorX / width.toFloat()).coerceIn(0f, 1f) else 0.5f
        val normY = if (height > 0) (cursorY / height.toFloat()).coerceIn(0f, 1f) else 0.5f
        webView.evaluateJavascript(
            """
            (function() {
                try {
                    if (!document.getElementById('__dizi_no_snap_scroll')) {
                        var st = document.createElement('style');
                        st.id = '__dizi_no_snap_scroll';
                        st.innerHTML = 'html, body, * { scroll-behavior: auto !important; scroll-snap-type: none !important; }';
                        if (document.head) document.head.appendChild(st);
                    }
                    var dy = $deltaY;
                    var x = Math.floor(window.innerWidth * $normX);
                    var y = Math.floor(window.innerHeight * $normY);
                    var el = document.elementFromPoint(x, y);
                    var scrolledInner = false;
                    while (el && el !== document.body && el !== document.documentElement) {
                        var style = window.getComputedStyle(el);
                        var overflowY = style ? style.overflowY : '';
                        if ((overflowY === 'auto' || overflowY === 'scroll' || overflowY === 'overlay') && el.scrollHeight > el.clientHeight + 8) {
                            var prev = el.scrollTop;
                            el.scrollTop = prev + dy;
                            if (Math.abs(el.scrollTop - prev) > 0.5) {
                                scrolledInner = true;
                                break;
                            }
                        }
                        el = el.parentElement;
                    }
                    if (!scrolledInner) {
                        var scroller = document.scrollingElement || document.documentElement || document.body;
                        var prevTop = window.pageYOffset || scroller.scrollTop || 0;
                        window.scrollTo({ top: Math.max(0, prevTop + dy), behavior: 'instant' });
                        var afterTop = window.pageYOffset || scroller.scrollTop || 0;
                        if (Math.abs(afterTop - prevTop) < 0.5) {
                            scroller.scrollTop = Math.max(0, prevTop + dy);
                        }
                    }
                } catch(e) {}
            })();
            """.trimIndent(),
            null
        )
    }

    private fun scrollWebPageAndCheckTop(webView: WebView, deltaY: Int, onReachTopEdge: () -> Unit) {
        val normX = if (width > 0) (cursorX / width.toFloat()).coerceIn(0f, 1f) else 0.5f
        val normY = if (height > 0) (cursorY / height.toFloat()).coerceIn(0f, 1f) else 0.5f

        webView.evaluateJavascript(
            """
            (function() {
                try {
                    var currentTop = window.pageYOffset || document.documentElement.scrollTop || document.body.scrollTop || 0;
                    var x = Math.floor(window.innerWidth * $normX);
                    var y = Math.floor(window.innerHeight * $normY);
                    var el = document.elementFromPoint(x, y);
                    while (el && el !== document.body && el !== document.documentElement) {
                        var style = window.getComputedStyle(el);
                        var overflowY = style ? style.overflowY : '';
                        if ((overflowY === 'auto' || overflowY === 'scroll') && el.scrollTop > 0) {
                            currentTop = Math.max(currentTop, el.scrollTop);
                            break;
                        }
                        el = el.parentElement;
                    }
                    return currentTop;
                } catch(e) { return 0; }
            })();
            """.trimIndent()
        ) { result ->
            val topVal = result?.trim()?.toDoubleOrNull()?.toInt() ?: 0
            if (topVal > 5 || webView.scrollY > 5) {
                scrollWebPage(webView, webView, deltaY)
            } else {
                onReachTopEdge()
            }
        }
    }

    private fun dispatchHoverAndMouseMove(targetView: View?, webView: WebView?) {
        if (targetView != null) {
            val now = SystemClock.uptimeMillis()
            val hoverMove = MotionEvent.obtain(
                now,
                now,
                MotionEvent.ACTION_HOVER_MOVE,
                cursorX,
                cursorY,
                0
            ).apply {
                source = InputDevice.SOURCE_MOUSE
            }
            targetView.dispatchGenericMotionEvent(hoverMove)
            hoverMove.recycle()
        }

        // JS katmanında video player kontrollerini (JWPlayer, VideoJS, Plyr, ArtPlayer vb.) uyandır
        if (webView != null && width > 0 && height > 0) {
            val normX = (cursorX / width.toFloat()).coerceIn(0f, 1f)
            val normY = (cursorY / height.toFloat()).coerceIn(0f, 1f)
            webView.evaluateJavascript(
                """
                (function() {
                    try {
                        var x = Math.floor(window.innerWidth * $normX);
                        var y = Math.floor(window.innerHeight * $normY);
                        var el = document.elementFromPoint(x, y);
                        if (el) {
                            var evMove = new MouseEvent('mousemove', {
                                view: window, bubbles: true, cancelable: true,
                                clientX: x, clientY: y, screenX: x, screenY: y
                            });
                            var evOver = new MouseEvent('mouseover', {
                                view: window, bubbles: true, cancelable: true,
                                clientX: x, clientY: y, screenX: x, screenY: y
                            });
                            el.dispatchEvent(evOver);
                            el.dispatchEvent(evMove);
                            if (typeof PointerEvent !== 'undefined') {
                                el.dispatchEvent(new PointerEvent('pointermove', {
                                    bubbles: true, clientX: x, clientY: y, pointerType: 'mouse'
                                }));
                            }
                        }
                    } catch(e) {}
                })();
                """.trimIndent(),
                null
            )
        }
    }

    fun performClickOnTarget(targetView: View?, webView: WebView?) {
        if (!isCursorEnabled || targetView == null) return
        wakeUpCursor()

        val downTime = SystemClock.uptimeMillis()
        val eventTime = downTime + 45

        // Önce imlecin olduğu yere hover gönder ki gizli player butonları aktifleşsin
        dispatchHoverAndMouseMove(targetView, webView)

        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
        val upEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, cursorX, cursorY, 0)
        targetView.dispatchTouchEvent(downEvent)
        targetView.dispatchTouchEvent(upEvent)
        downEvent.recycle()
        upEvent.recycle()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isCursorEnabled || !isCursorVisible) return

        canvas.save()
        canvas.translate(cursorX, cursorY)

        // Dış hedef halkası
        canvas.drawCircle(0f, 0f, 20f, ringPaint)

        // Fare oku çizimi
        cursorPath.reset()
        cursorPath.moveTo(0f, 0f)
        cursorPath.lineTo(0f, 30f)
        cursorPath.lineTo(8f, 22f)
        cursorPath.lineTo(15f, 34f)
        cursorPath.lineTo(21f, 31f)
        cursorPath.lineTo(14f, 19f)
        cursorPath.lineTo(24f, 19f)
        cursorPath.close()

        canvas.drawPath(cursorPath, fillPaint)
        canvas.drawPath(cursorPath, strokePaint)
        canvas.drawCircle(0f, 0f, 2.5f, centerDotPaint)
        canvas.restore()
    }
}
