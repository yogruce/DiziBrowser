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
            isCursorVisible = true
            invalidate()
        }

    var cursorX: Float = 500f
        private set
    var cursorY: Float = 320f
        private set

    private var stepSize = 20f
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

    private fun wakeUpCursor() {
        isCursorVisible = true
        uiHandler.removeCallbacks(autoHideRunnable)
        // Video izlerken 5 saniye hareket olmazsa imleci gizle ki görüntüyü kapatmasın
        uiHandler.postDelayed(autoHideRunnable, 5000L)
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
        stepSize = if (now - lastMoveTime < 190) {
            min(stepSize + 4.5f, 54f)
        } else {
            18f
        }
        lastMoveTime = now

        val topEdgeThreshold = 36f
        val bottomEdgeThreshold = (height - 48).coerceAtLeast(100).toFloat()

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (cursorY <= topEdgeThreshold) {
                    // Önce sayfa aşağı kaymışsa yukarı kaydır
                    if (webView != null && webView.scrollY > 0) {
                        scrollWebPage(webView, -220)
                        dispatchHoverAndMouseMove(targetView, webView)
                        return true
                    } else {
                        // Sayfanın en tepesindeysek ve tekrar yukarı basıldıysa üst menü çubuğuna çık
                        onReachTopEdge()
                        return true
                    }
                }
                cursorY = max(8f, cursorY - stepSize)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (cursorY >= bottomEdgeThreshold) {
                    // Ekranın en altına gelindiğinde sayfayı otomatik aşağı kaydır
                    if (webView != null) {
                        scrollWebPage(webView, 220)
                    } else {
                        targetView?.scrollBy(0, 220)
                    }
                } else {
                    cursorY = min((height - 12).toFloat(), cursorY + stepSize)
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                cursorX = max(8f, cursorX - stepSize)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                cursorX = min((width - 12).toFloat(), cursorX + stepSize)
            }
            else -> return false
        }

        // Video player'ların alt barını (Play, Tam Ekran, Ses) görünür yapmak için Hover/MouseMove gönder
        dispatchHoverAndMouseMove(targetView, webView)
        invalidate()
        return true
    }

    private fun scrollWebPage(webView: WebView, deltaY: Int) {
        // Hem native WebView kaydırması hem de JS window.scrollBy (modern siteler ve iç container'lar için)
        val newScrollY = (webView.scrollY + deltaY).coerceAtLeast(0)
        webView.scrollTo(webView.scrollX, newScrollY)
        webView.evaluateJavascript(
            """
            (function() {
                window.scrollBy({ top: $deltaY, left: 0, behavior: 'auto' });
                if (document.scrollingElement) {
                    document.scrollingElement.scrollTop += $deltaY;
                }
            })();
            """.trimIndent(),
            null
        )
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
