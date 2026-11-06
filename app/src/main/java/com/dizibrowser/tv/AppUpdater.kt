package com.dizibrowser.tv

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object AppUpdater {

    private const val VERSION_JSON_URL =
        "https://raw.githubusercontent.com/yogruce/DiziBrowser/main/version.json"
    private const val DEFAULT_APK_URL =
        "https://raw.githubusercontent.com/yogruce/DiziBrowser/main/DiziBrowser-TV-v1.0.apk"

    fun getCurrentVersionCode(activity: Activity): Int {
        return try {
            val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (_: Exception) {
            1
        }
    }

    fun getCurrentVersionName(activity: Activity): String {
        return try {
            val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
            pInfo.versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }

    fun checkForUpdates(activity: Activity, silentIfUpToDate: Boolean = false) {
        if (!silentIfUpToDate) {
            Toast.makeText(activity, "🔄 GitHub üzerinden yeni sürüm kontrol ediliyor...", Toast.LENGTH_SHORT).show()
        }

        Thread {
            try {
                // Önbelleğe takılmamak için zaman damgası ekle
                val checkUrl = URL("$VERSION_JSON_URL?t=${System.currentTimeMillis()}")
                val conn = (checkUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("User-Agent", "DiziBrowserTV-Updater")
                }

                val jsonText = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val obj = JSONObject(jsonText)
                val remoteVersionCode = obj.optInt("versionCode", 0)
                val remoteVersionName = obj.optString("versionName", "")
                val releaseNotes = obj.optString("changelog", "Performans ve kararlılık iyileştirmeleri.")
                val apkUrl = obj.optString("apkUrl", DEFAULT_APK_URL).ifBlank { DEFAULT_APK_URL }

                val currentCode = getCurrentVersionCode(activity)
                val currentName = getCurrentVersionName(activity)

                activity.runOnUiThread {
                    if (remoteVersionCode > currentCode) {
                        AlertDialog.Builder(activity)
                            .setTitle("🚀 Yeni Güncelleme Mevcut! (v$remoteVersionName)")
                            .setMessage(
                                "Mevcut Sürüm: v$currentName (Kod: $currentCode)\n" +
                                    "Yeni Sürüm: v$remoteVersionName (Kod: $remoteVersionCode)\n\n" +
                                    "Yenilikler:\n$releaseNotes\n\n" +
                                    "Not: Güncelleme yapıldığında favorileriniz ve 10 günlük izleme geçmişiniz silinmez."
                            )
                            .setPositiveButton("Hemen İndir ve Güncelle 🔄") { _, _ ->
                                downloadAndInstallApk(activity, apkUrl)
                            }
                            .setNegativeButton("Daha Sonra", null)
                            .show()
                    } else if (!silentIfUpToDate) {
                        AlertDialog.Builder(activity)
                            .setTitle("✅ Uygulamanız Güncel")
                            .setMessage(
                                "Şu anda en güncel sürümü (v$currentName - Sürüm Kodu: $currentCode) kullanıyorsunuz.\n\n" +
                                    "Yine de GitHub'daki son APK dosyasını yeniden indirip üzerine kurmak ister misiniz?"
                            )
                            .setPositiveButton("Yeniden İndir ve Kur") { _, _ ->
                                downloadAndInstallApk(activity, apkUrl)
                            }
                            .setNegativeButton("Tamam", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                if (!silentIfUpToDate) {
                    activity.runOnUiThread {
                        AlertDialog.Builder(activity)
                            .setTitle("🔄 Doğrudan Güncelleme")
                            .setMessage(
                                "Sürüm bilgisi okunamadı (${e.localizedMessage ?: "Bağlantı hatası"}).\n\n" +
                                    "Doğrudan GitHub deponuzdaki en güncel APK dosyasını indirip kurmak ister misiniz?"
                            )
                            .setPositiveButton("Son APK'yı İndir ve Kur") { _, _ ->
                                downloadAndInstallApk(activity, DEFAULT_APK_URL)
                            }
                            .setNegativeButton("İptal", null)
                            .show()
                    }
                }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun downloadAndInstallApk(activity: Activity, apkUrl: String) {
        // Android 8.0+ için "Bilinmeyen Uygulamaları Yükleme" izni kontrolü
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(
                    activity,
                    "Güncelleme için lütfen 'Bu kaynaktan uygulama yüklemeye izin ver' seçeneğini açın.",
                    Toast.LENGTH_LONG
                ).show()
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                    activity.startActivity(intent)
                } catch (_: Exception) {}
                return
            }
        }

        val progressDialog = ProgressDialog(activity).apply {
            setTitle("🔄 Güncelleme İndiriliyor...")
            setMessage("GitHub üzerinden yeni APK indiriliyor, lütfen bekleyin...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            setCancelable(false)
            show()
        }

        Thread {
            try {
                val url = URL("$apkUrl?t=${System.currentTimeMillis()}")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "DiziBrowserTV-Updater")
                }
                conn.connect()

                val totalLength = conn.contentLength
                val outFile = File(activity.cacheDir, "DiziBrowser-update.apk")
                if (outFile.exists()) outFile.delete()

                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var count: Int
                        while (input.read(buffer).also { count = it } != -1) {
                            downloaded += count
                            output.write(buffer, 0, count)
                            if (totalLength > 0) {
                                val pct = ((downloaded * 100) / totalLength).toInt()
                                activity.runOnUiThread {
                                    progressDialog.progress = pct
                                }
                            }
                        }
                        output.flush()
                    }
                }

                activity.runOnUiThread {
                    progressDialog.dismiss()
                    launchApkInstaller(activity, outFile)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(
                        activity,
                        "İndirme başarısız: ${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun launchApkInstaller(activity: Activity, apkFile: File) {
        try {
            val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(
                activity,
                "Kurulum ekranı açılamadı: ${e.localizedMessage}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
