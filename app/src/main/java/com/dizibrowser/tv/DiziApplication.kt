package com.dizibrowser.tv

import android.app.Application

class DiziApplication : Application() {

    companion object {
        const val REAL_PACKAGE_NAME = "com.dizibrowser.tv"
        const val SPOOFED_BROWSER_PACKAGE = "com.tcl.browser"

        @Volatile
        var spoofPackageNameForWebView: Boolean = false

        fun resolvePackageName(defaultPkg: String): String {
            if (!spoofPackageNameForWebView) return defaultPkg
            return try {
                val trace = Throwable().stackTrace
                for (el in trace) {
                    val cls = el.className
                    val method = el.methodName
                    if (cls.contains("AwSettings") || method.contains("AllowListHeader")) {
                        return SPOOFED_BROWSER_PACKAGE
                    }
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
