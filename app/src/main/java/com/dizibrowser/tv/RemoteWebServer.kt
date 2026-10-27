package com.dizibrowser.tv

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

class RemoteWebServer(
    private val port: Int = 8989,
    private val onNavigateUrl: (String) -> Unit,
    private val onAddFavorite: (String, String) -> Unit
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                while (running) {
                    val client = ss.accept()
                    Thread { handleClient(client) }.start()
                }
            } catch (_: Exception) {
                running = false
            }
        }.start()
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val pathWithQuery = if (parts.size >= 2) parts[1] else "/"

            var messageBanner = ""
            if (pathWithQuery.startsWith("/open?")) {
                val params = parseQuery(pathWithQuery.substringAfter("?"))
                val url = params["url"]?.trim() ?: ""
                if (url.isNotEmpty()) {
                    onNavigateUrl(url)
                    messageBanner = "✅ Link TV ekranında açıldı!"
                }
            } else if (pathWithQuery.startsWith("/fav?")) {
                val params = parseQuery(pathWithQuery.substringAfter("?"))
                val title = params["title"]?.trim() ?: ""
                val url = params["url"]?.trim() ?: ""
                if (url.isNotEmpty()) {
                    onAddFavorite(title.ifBlank { url }, url)
                    messageBanner = "⭐ Favorilere eklendi!"
                }
            }

            val html = buildMobileHtml(messageBanner)
            val bytes = html.toByteArray(Charsets.UTF_8)
            val out: OutputStream = socket.getOutputStream()
            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (_: Exception) {
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        query.split("&").forEach { pair ->
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                map[k] = v
            }
        }
        return map
    }

    private fun buildMobileHtml(banner: String): String {
        val alertHtml = if (banner.isNotEmpty()) {
            "<div style='background:#1f6feb;color:#fff;padding:12px;border-radius:8px;margin-bottom:16px;font-weight:bold;'>$banner</div>"
        } else ""

        return """
            <!DOCTYPE html>
            <html lang="tr">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Dizi Browser TV Kumandası</title>
                <style>
                    body { background:#0d1117; color:#f0f6fc; font-family:sans-serif; padding:20px; margin:0; }
                    .card { background:#161b22; border:1px solid #30363d; border-radius:12px; padding:18px; margin-bottom:16px; }
                    h2 { margin-top:0; color:#00e5ff; font-size:20px; }
                    input { width:100%; box-sizing:border-box; padding:12px; margin:8px 0; border-radius:8px; border:1px solid #30363d; background:#0d1117; color:#fff; font-size:16px; }
                    button { width:100%; padding:14px; border:none; border-radius:8px; background:#00e5ff; color:#000; font-weight:bold; font-size:16px; cursor:pointer; margin-top:6px; }
                </style>
            </head>
            <body>
                $alertHtml
                <div class="card">
                    <h2>📺 TV'de Site veya Bölüm Aç</h2>
                    <form action="/open" method="get">
                        <input type="text" name="url" placeholder="https://... veya dizi adı yazın" required>
                        <button type="submit">TV Ekranında Aç 🚀</button>
                    </form>
                </div>
                <div class="card">
                    <h2>⭐ TV Favorilerine Site Ekle</h2>
                    <form action="/fav" method="get">
                        <input type="text" name="title" placeholder="Site Adı (Örn: Dizi Sitesi)">
                        <input type="text" name="url" placeholder="https://..." required>
                        <button type="submit" style="background:#ffd700;">Favorilere Kaydet ⭐</button>
                    </form>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    companion object {
        fun getLocalIpAddress(): String? {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    if (!intf.isUp || intf.isLoopback) continue
                    val addrs = intf.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                                return ip
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            return null
        }

        fun generateQrBitmap(content: String, sizePx: Int = 360): Bitmap? {
            return try {
                val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
                val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
                for (x in 0 until sizePx) {
                    for (y in 0 until sizePx) {
                        bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                    }
                }
                bmp
            } catch (_: Exception) {
                null
            }
        }
    }
}
