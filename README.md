# 📺 Dizi Browser TV (Android TV & Smart Box)

**Dizi Browser TV**, Android TV, Google TV, Mi Box ve tüm Android tabanlı akıllı kutular/cihazlar için özel olarak geliştirilmiş **reklamsız, korumalı ve kumanda dostu** bir dizi/film izleme tarayıcısıdır.

---

## ✨ Temel Özellikler

* **🖱️ Akıllı Kumanda İmleci (D-Pad Cursor + Hover):**
  * Kumandanın yön tuşlarıyla hareket eden sanal fare imleci.
  * İmleç videonun üzerine geldiğinde gerçek `Hover` ve `mousemove` sinyalleri göndererek sitedeki video oynatıcının (Play, Tam Ekran, Ses, Altyazı) kontrollerini otomatik olarak görünür yapar.
  * Ekranın en altına inildiğinde sayfayı otomatik aşağı kaydırır, en üste çıkıldığında sayfayı yukarı kaydırır ve üst menü çubuğuna geçer.
  * Video izlerken 5 saniye hareketsiz kalındığında imleç kendiliğinden gizlenir.
* **🛡️ Gelişmiş Reklam & Zararlı Kalkanı:**
  * Popup ve yeni sekme (`window.open`) reklamlarını tamamen engeller.
  * Açılışta güncellenen binlerce reklam/izleyici sunucusunu (`AdBlocker`) ağ katmanında bloklar.
  * Sayfa içi şeffaf reklam katmanlarını ve banner kutularını gizler.
  * **APK / Zararlı Dosya İndirme Kilidi:** Saldırıya uğramış veya sahte sitelerin TV'ye gizlice `.apk`, `.xapk`, `.exe`, `.jar` gibi zararlı dosyalar indirmesini engeller.
* **🕘 10 Günlük Bölüm Hafızası & Kaldığı Yerden Devam:**
  * İzlenen bölümlerin başlığını, site adresini ve **kaçıncı dakikada/saniyede kaldığınızı** yerel cihaz hafızasında tutar.
  * 10 günü geçen kayıtları otomatik olarak temizler.
  * Aynı bölümü tekrar açtığınızda videoyu kaldığınız saniyeden devam ettirir.
* **📱 Telefondan QR Kod ile Link Gönderme (Sunucusuz / Modem Ayarsız):**
  * TV kumandasıyla uzun site adresleri yazmak yerine, ekrandaki QR kodu telefon kamerasıyla okutarak aynı Wi-Fi ağı üzerinden tek tuşla TV ekranında link açabilir veya favori site ekleyebilirsiniz.

---

## 📦 Kurulum (APK)

Hazır derlenmiş kurulum dosyasını doğrudan indirip Android TV veya akıllı kutunuza kurabilirsiniz:
* **[DiziBrowser-TV-v1.0.apk](DiziBrowser-TV-v1.0.apk)**

---

## 🛠️ Kaynak Koddan Derleme

```bash
./gradlew assembleDebug assembleRelease
```

Derlenen APK dosyası `app/build/outputs/apk/release/app-release.apk` konumunda oluşturulur.
