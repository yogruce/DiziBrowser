package com.dizibrowser.tv

import android.app.Application

class DiziApplication : Application() {
    override fun getPackageName(): String {
        return try {
            val trace = Throwable().stackTrace
            for (el in trace) {
                val cls = el.className
                val method = el.methodName
                if (cls.contains("AwSettings") || method.contains("AllowListHeader")) {
                    return "com.tcl.browser"
                }
            }
            super.getPackageName()
        } catch (_: Exception) {
            super.getPackageName()
        }
    }
}
