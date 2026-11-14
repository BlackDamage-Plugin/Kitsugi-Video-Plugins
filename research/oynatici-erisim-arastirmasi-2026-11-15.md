# FilmMakinesi / HDFilmCehennemi oynatıcı araştırması — 15 Kasım 2026

## Kanıt ve kapsam

- Kullanıcının Drive'da paylaştığı Supernatural 1. sezon 1. bölüm HTML'inde
  FilmMakinesi Close (`closeload.filmmakinesi.to/video/embed/…`) ve Rapid
  (`rapid.filmmakinesi.to/embed-…`) adresleri; HDFilmCehennemi sayfasında
  Close iframe'i ve `rapidrame_id` vardır. Bölüm sayfası iframe'in kendisinin
  içeriği değildir; yalnızca oyuncu adresini gösterir.
- HDFilmCehennemi Rapid oyuncusunun **güncel** HTML'i TR çıkışlı GitHub Actions
  denemesinde alındı ve kullanıcıya ait Drive klasörüne kaydedildi. Yeni `data-v`
  kontrolü tespit edilip iki eklentide düzeltildi; gerçek Rapid HTML'iyle Kotlin
  testi run 43653715248'de geçti. Bu sadece o örnek ve o oynatıcıdır.
- FilmMakinesi Close, FilmMakinesi Rapid ve HDFilmCehennemi Close için 78 adet
  Türkiye olarak listelenen HTTP/SOCKS çıkışıyla iki kez, TLS doğrulaması açık
  ve oturum bilgisi olmadan deneme yapıldı (GitHub Actions 43654450874).
  Bağlantı hataları ve 403/404/451 yanıtları dışında geçerli oyuncu HTML'i
  alınamadı. 404 gibi yanıtlar yanlış/ulaşılamayan adres de gösterebilir;
  sunulan sonuç herhangi bir koruma türünü kesinleştirmez.
- `src/test/resources/live/` altındaki Close/Rapid fixture'ları eski tarihlidir.
  Yararlı regresyon örnekleridir, güncel canlı HTML gibi sunulamaz.

## Yayın ve ayrıştırma zinciri

1. FilmMakinesi sayfasında `data-video_url` kaynakları; HDFilmCehennemi'nde
   `data-video` düğmeleri ve `/video/{id}/` yanıtı veya varsayılan iframe okunur.
2. Embed sayfası bölüm sayfası Referer'ı ile alınır. Oynatıcıdaki JWPlayer
   `sources` nesnesi/ifadesi yorumlanır; rastgele isimli veya hosta bağlı
   karartılmış fonksiyonlar çözülebilir. JSON-LD'deki bağımsız adres güvenilir
   video kaynağı sayılmaz; önceki araştırmada tuzak URL saptanmıştır.
3. Akış için oynatıcı URL'si Referer, oyuncu kökü Origin olarak kullanılır;
   altyazı track'leri ayrıca döndürülür. Bu, geçerli mevcut uygulamayı ve eski
   test örneklerini açıklar; üç eksik endpoint için bugün çalıştığını kanıtlamaz.

## Dış kaynak karşılaştırması

- Önceki gerçek örnekler ve tarayıcı/Kotlin/CDN karşılaştırmaları:
  https://github.com/f26901964-eng/Kitsugi-Plugins/pull/19
  (Ekim 2026; güncel endpoint doğrulaması değil).
- Bağımsız kaynak olarak https://github.com/JustSadY/Cloudstream-Lykia
  (31 Ekim 2026 itibarıyla), oyuncu scriptlerini okuyup `sources` ifadesinden
  çıkarma ve Referer kullanma yaklaşımını belgeliyor. Kaynak incelenmiş olması
  bugün çalıştığının kanıtı değildir; kod bu projeye kopyalanmadı.
- Chrome dokümantasyonuna göre Network yalnızca DevTools açıkken kayıt yapar;
  Preserve log yönlendirmeleri korur, Group by frame iframe isteklerini gösterir.
  https://developer.chrome.com/docs/devtools/network/reference
  `All` seçilip sayfa yenilenmeli ve iframe'in Document yanıtı incelenmelidir;
  F12'de istek görülmemesi tek başına koruma olduğunu kanıtlamaz.

## Açık kalanlar

Üç güncel oyuncu HTML'i ve kullanıcı cihazında uçtan uca oynatma henüz
kanıtlanmadı. Eklenti sürümleri FilmMakinesi 74 ve HDFilmCehennemi 65;
uygulamada gerçekten bu sürümlerin kurulu olması ve aynı bölüm/alternatifle
sonucun raporlanması önemlidir. Canlı HTML alınmadan yeni çözücü değişikliğinin
sorunu gidereceği varsayılmamalıdır. HTTP yanıtları, kullanılacak bilgisayarın
konumuna, Cloudflare durumuna ve istek bağlamına göre farklılık gösterebilir.

## Kullanıcının cihaz testi ve geçmiş karşılaştırması

Kullanıcının Supernatural S08E01 ekran görüntülerinde FilmMakinesi için
KTS-CSP-9104, 15 saniyelik eklenti zaman aşımı ve sıfır video görünüyor;
HDFilmCehennemi için bir Rapidrame DUAL bağlantısı listeleniyor. Görüntüler
oynatmanın başarısını, yüklü eklenti sürümlerini veya zaman aşımının hangi
istekte gerçekleştiğini göstermiyor.

Git geçmişi tamamlanıp `3ea10e4` (25 Ekim, PR #19) ile mevcut üretim kaynakları
karşılaştırıldı. Eklentilerin ana kaynak alma ve paralel çözüm akışı aynı;
sonraki üretim kodu farkı her iki modülde MiniJs/PlayerDecoder `data-v` desteği.
Dolayısıyla eski çalışan sürüme dönmek bu kanıtlardan hareketle Close veya
FilmMakinesi problemini giderecek diye söylenemez.

FilmMakinesi bölüm isteğinin zaman aşımı 20 saniye, oynatıcı çözümünün sınırı
35 saniye; paralel çözüm sonuçları `awaitAll` sonrasında callback'e veriliyor.
Dolayısıyla yavaş bir oyuncu hızlı çözülen diğerinin de bildirilmesini
geciktirebilir ve uygulamanın 15 saniyelik sınırıyla çakışabilir. Bu koddan
çıkan olası mekanizmadır; mevcut cihazda gecikmenin bu aşamada olduğu veya tek
nedenin bu olduğu kanıtlanmış değildir. HD akışında da API ve oyuncular için
benzer bekleme sınırları bulunur. Güncel oyuncu yanıtları ve cihazda uçtan uca
sınama olmadan dört kaynağın çalıştığı iddia edilemez.

## S08E01 için ek erişim denemesi

GitHub Actions 43655350292 ve 43655425626: kullanıcının ekranındaki bölüm
için FilmMakinesi (önceki S01 adresinin sezon yolu temel alınarak denenmiş,
kimliği bağımsız doğrulanmamış) ve arama sonucunda bulunan HDFilmCehennemi
S08E01 sayfaları 78 TR listeli proxy üzerinden denendi. İlk denemede HD 301
alındı; yönlendirme takibi eklenen ikinci denemede FilmMakinesi 1×403 ve 77 ağ
hatası, HD 78 ağ hatası verdi. Geçerli bölüm/oynatıcı HTML'i alınamadı; bu
sonuçlar sayfanın var olmadığına veya cihazın aynı ağ sorununu yaşadığına
kanıt değildir. Doğrudan web alımı FilmMakinesi'nde 451, HD'de 500 döndü.
