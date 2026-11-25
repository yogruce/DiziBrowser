package com.dizibrowser.tv

import android.app.Application

class DiziApplication : Application() {

    companion object {
        const val REAL_PACKAGE_NAME = "com.dizibrowser.tv"
        const val SPOOFED_BROWSER_PACKAGE = "com.tcl.browser"

        @Volatile
        var spoofPackageNameForWebView: Boolean = true

        fun resolvePackageName(defaultPkg: String): String {
            if (!spoofPackageNameForWebView) return defaultPkg
            return try {
                val trace = Throwable().stackTrace
                for (el in trace) {
                    val cls = el.className
                    if (cls == "com.dizibrowser.tv.DiziApplication" ||
                        cls == "com.dizibrowser.tv.DiziApplication\$Companion" ||
                        cls == "com.dizibrowser.tv.MainActivity" ||
                        cls == "android.content.ContextWrapper" ||
                        cls == "android.content.Context" ||
                        cls == "java.lang.Thread"
                    ) {
                        continue
                    }
                    // Yalnızca Android işletim sisteminin kendi iç servisleri gerçek paket adını görmeli;
                    // WebView / Trichrome / R8 ile gizlenmiş tüm Chromium sınıfları (ne adla gizlenmiş olursa olsun) com.tcl.browser görmeli!
                    if (cls.startsWith("android.app.") ||
                        cls.startsWith("android.os.") ||
                        cls.startsWith("android.view.") ||
                        cls.startsWith("android.content.") ||
                        cls.startsWith("androidx.core.") ||
                        cls.startsWith("androidx.appcompat.") ||
                        cls.startsWith("com.dizibrowser.tv.")
                    ) {
                        return defaultPkg
                    }
                    return SPOOFED_BROWSER_PACKAGE
                }
                defaultPkg
            } catch (_: Exception) {
                defaultPkg
            }
        }
    }

    override fun getPackageName(): String {
        return resolvePackageName(super.getPackageName())
    }
}
