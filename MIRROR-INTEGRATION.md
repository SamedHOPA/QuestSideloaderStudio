# Public mirror entegrasyonu — 1.1.0

Referans: nerdunit/androidsideloader v3.4, `1bdd87fc82e2010e9d8fa7657cd6a5a7d98c512c`.

## Kullanım

1. Bulut Kütüphanesi sekmesini açın. Public JSON'u içe aktarın veya HTTPS sunucu adresini ve JSON'daki Base64 arşiv parolasını girin.
2. Sunucunun verdiği API anahtarını ayrı alana girin. Listeyi Güncelle, ayarları kaydeder ve kataloğu indirir. Anahtar Windows DPAPI ile aynı Windows hesabına bağlı şifrelenir; başka bilgisayara kopyalandığında yeniden girilmesi gerekir. Alternatif olarak `QUEST_MIRROR_API_KEY` ortam değişkeni desteklenir.
3. İndir seçeneği cihaz olmadan da çalışır. İndir ve Kur, ADB cihazı gerektirir. Duraklat, kısmi dosyaları korur; Devam Et aynı işlem niyetiyle sürdürür. Uygulama kapatılıp açıldıktan sonra aynı paketin İndir düğmesi de kalan dosyalardan devam eder.

Referansın açık kaynak dosyalarında kullanılabilir API anahtarı bulunmuyor. Derleme metadata'sı boş ve fallback `<YOUR-KEY>`. Bu sürüme gerçek anahtar gömülmedi; anahtarsız erişimin çalıştığı iddia edilmez.

## Uygulanan akış

- Public katalog: doğrudan `meta.7z` indirme, 7z imza kontrolü, şifreli arşiv çıkarma, `*ameList.txt` ayrıştırma. Başarısız yenileme mevcut katalog önbelleğini bozmaz.
- API başlığı Rclone'a `RCLONE_HEADER` ortam değişkeniyle verilir; değer komut satırına eklenmez. Tüm ilgili Rclone HTTP isteklerinde `X-API-Key` gönderilir. Hız sınırı saniyede 1 istek ve burst 3.
- Paket yolu: küçük harf hex MD5(UTF-8(katalogdaki Release Name + LF)). Görünen oyun adı veya Android paket adı kullanılmaz.
- İndirme: Rclone salt okunur localhost HTTP proxy; dosya listesi ve HEAD; yerel kısmi dosya/validator kaydı; Range devamı. Sunucu 200 dönerse baştan yazılır, 206 Content-Range başlangıcı doğrulanır, 416 boyut eşleşmesi olmadan başarı sayılmaz. Ağ/429/uygun 5xx hatalarında en fazla üç deneme; 429 Retry-After 1–300 saniye aralığında kullanılır.
- Parçalı `<hash>.7z.001` arşivleri, Base64 çözülmüş parola ile açılır. Arşiv yolları ve bağlantılar çıkarma öncesi kontrol edilir. Çıkarma tamamlanmadan APK kurulumuna geçilmez.
- İndirilen metadata yan dosyaları snapshot altında saklanır. Arayüz katalog adı, sürüm adı, paket adı, boyut ve aramayı gösterir; referansın galeri/fragman/diğer genel arayüz özelliklerinin kopyası değildir.
- Anahtar/parola hata mesajlarından maskelenir. API anahtarı yoksa uzak istek başlatılmadan açıklayıcı hata gösterilir. Protokol User-Agent'i değiştirilmedi; paketli Rclone v1.75.2 kullanılır.

## Dosyalar

`MirrorProtocol.kt`: model, JSON, referans hash'i, Windows anahtar kasası.
`RcloneController.kt`: katalog önbelleği ve paket iş akışı.
`MirrorProcess.kt`: süreç yaşam döngüsü, maskeleme, arşiv doğrulama/çıkarma.
`ResumableDownload.kt`: proxy, listeleme, HTTP devamı ve yeniden deneme.
`MainScreen.kt`: ayarlar, indirme/kurma ve duraklat/devam arayüzü.

## Doğrulama

23 Ekim 2026: 12 otomatik test, 0 hata; `createDistributable` başarılı. Testler gerçek paketli Rclone/7-Zip ve yerel HTTP sunucusuyla çalışır. Tam katalog → şifreli parçalı paket → çıkarılmış APK dosyası akışı, upstream API başlığı, alt dizinli BaseUri, bozuk katalogda önbelleğin korunması, Range 206/200 davranışı, hatalı Content-Range, yanlış parola ve DPAPI saklama doğrulandı.

Taşınabilir 1.1.0 uygulaması açıldı; Bulut Kütüphanesi görünümü ve anahtar eksikliği mesajı arayüzde doğrulandı. Gerçek servis bağlantısının başarı testi için geçerli API anahtarı, fiziksel kurulum testi için bağlı cihaz gerekiyor. Referansın özel Rclone remote modu bu public HTTP entegrasyonuna dahil değildir.

## Geri dönüş

Taşınabilir dizindeki `backups/update-before-1.1.0-20261023` klasöründe önceki `app` klasörü ve çalıştırılabilir dosya var. Uygulama kapalıyken bu ikisi geri kopyalanarak eski sürüme dönülebilir. İndirmeler, cihaz yedekleri ve sunucu adresi/parolası korunmuştur. Güncel runtime'ın modül dosyası önceki runtime ile aynı SHA-256 değerine sahip olduğundan runtime değiştirilmedi.
