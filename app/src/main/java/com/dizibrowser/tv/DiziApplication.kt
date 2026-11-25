package com.dizibrowser.tv

import android.app.Application

class DiziApplication : Application() {

    companion object {
        @Volatile
        var spoofPackageNameForWebView: Boolean = true
    }

    override fun getPackageName(): String {
        if (spoofPackageNameForWebView) {
            try {
                val trace = Throwable().stackTrace
                for (el in trace) {
                    val cls = el.className
                    if (cls == "com.dizibrowser.tv.DiziApplication" ||
                        cls == "com.dizibrowser.tv.MainActivity" ||
                        cls == "android.content.ContextWrapper" ||
                        cls == "android.content.Context"
                    ) {
                        continue
                    }
                    // İlk gerçek çağırıcı WebView / Chromium (R8 ile WV.* olarak gizlenmiş sınıflar dahil) ise:
                    if (cls.startsWith("WV.") ||
                        cls.startsWith("org.chromium.") ||
                        cls.startsWith("com.android.webview.")
                    ) {
                        return "com.tcl.browser"
                    }
                    break
                }
            } catch (_: Exception) {}
        }
        return super.getPackageName()
    }
}
