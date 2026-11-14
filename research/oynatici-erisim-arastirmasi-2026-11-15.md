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
