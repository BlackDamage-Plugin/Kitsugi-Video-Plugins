# HDFilmCehennemi Close (CloseLoad) kaynağı — erişim incelemesi (15 Kasım 2026)

## Şikâyet

"CloseLoad video veri kaynağı bir süre sonra HDFilmCehennemi eklentisi üzerinden
gelmiyor." Aynı sayfada Rapidrame görünmeye devam ediyor; yani sorun tek bir
kaynakta ve sessiz.

## Koddan çıkan nedenler (kanıt: `HDFilmCehennemi/src/main`)

1. **Tek User-Agent, host adına sabit.** `PlayerPageUserAgent.kt` yalnızca
   `hdfilmcehennemi.mobi` host'u için kısa `Mozilla/5.0` gönderiyordu. Close
   host'u masaüstü kimliğiyle 404 döndüğü için, site oynatıcı host'unu
   değiştirdiğinde (ya da `.mobi` TR'de erişilemez hâle geldiğinde) Close isteği
   404 alıyor ve kaynak **sessizce** listeden düşüyordu. Rapidrame farklı host'ta
   olduğu için etkilenmiyordu — şikâyetin "bir süre sonra" ve "yalnız Close"
   olmasıyla birebir uyuşan mekanizma budur.
2. **Tek Referer.** İstek her zaman film/bölüm sayfasını Referer olarak
   gönderiyordu. Host kuralları değişip yalnız kök Referer kabul edildiğinde aynı
   sonuç: 403/404 → kaynak yok.
3. **Ölü adres denetlenmiyordu.** Çözülen HLS adresi (ör.
   `srvN.cdnimagesNNNN.cyou/.../master.txt`) imzalı/kısa ömürlü olabiliyor; eklenti
   denetlemeden sunuyordu. "Kaynak listede var ama açılmıyor" durumu buradan gelir.
4. **Tek adaya mahkûmiyet.** Hedefli çözüm (`sources` ifadesi) bir adres
   ürettiğinde genel yol (jwplayer `setup()` yakalama) hiç denenmiyordu; o adres
   ölüyse alternatif yoktu.
5. **Oynatıcı yolu tanıma dardı.** `HdfcSources.isPlayerUrl` yalnız
   `/video/embed/`, `/rplayer/` ve `closeload/rapidrame` host'larını kabul ediyordu;
   `/embed-`, `/e/`, `/player/` gibi yeni yollar iframe olarak görülmüyordu.
6. **API host'u sayfadan bağımsız.** `/video/{id}/` çağrısı `mainUrl` üzerinden
   gidiyordu; sayfa yedek alan adından (`.la` vb.) açıldığında API yanlış host'a
   gidebiliyordu.
7. **Sabitlenmiş alan adı.** `domainResolved` bir kez true olduktan sonra alan adı
   ölse bile yeniden çözülmüyordu; bu durumda kaynak sayfası hiç açılmaz.

## Yapılan düzeltmeler

- `PlayerFetch`: oynatıcı isteği **varyant**larla yapılır — kısa/masaüstü/mobil UA
  ve film sayfası/kök Referer. Bir UA 401/403/404/429/451 alırsa o UA atlanır ve
  yedek kimliğe geçilir; host adı değişse de Close erişimi sürer.
- `StreamCheck`: sunulmadan önce HLS adresine oynatıcının başlıklarıyla küçük bir
  aralık isteği yapılır. `#EXTM3U` → oynatılabilir, HTML/4xx → ölü, ağ hatası →
  bilinmiyor (kaynak yine sunulur). Çalışan Referer başlığı doğrudan bağlantıya
  yazılır.
- `PlayerResolver`: varyant döngüsü + ölü adres durumunda genel `setup()` yolu
  ikinci aday; kaynak başına 8,5 sn bütçe (uygulamanın 15 sn sınırının altında).
- `HdfcSources`: oynatıcı host/yol tanıma genişletildi, kaynak adı host'tan türetilir.
- `HDFilmCehennemi.loadLinks`: istekler önbelleğe alınmaz (`cacheTime = 0`), API
  çağrısı sayfanın kendi host'una gider, Close düğmeleri önce denenir, sayfa
  alınamazsa alan adı yeniden çözülüp bir kez tekrarlanır, çözülemeyen kaynak
  `HDCH`/`KitsugiPlayer` etiketiyle loglanır.
- Sürüm 73. Regresyon testleri: `PlayerFetchTest` (varyant sırası, UA/Referer
  kapsamı, oynatıcı yolu tanıma, çalma listesi denetimi kapsamı).

## Doğrulanan / doğrulanamayan

- **Doğrulandı:** Kotlin derlemesi ve tüm birim testleri (37 test) GitHub Actions
  üzerinde geçti; canlı sayfa fixture'larıyla çözücü davranışı değişmedi.
- **Doğrulanamadı:** Bugünkü canlı Close HTML'i. Bu ortamdan site erişimi kapalı;
  GitHub runner'ları TR dışından `HTTP 451` alıyor (`.nl` kökü 451, `.mobi` 403),
  TR vekil listesiyle önceki denemelerde de gövde alınamadı. Bu yüzden yeni bir
  oynatıcı şablonu (yeni JS guard) varsa çözücünün onu okuyup okuyamadığı
  bilinmiyor.
- Cihazda uçtan uca oynatma doğrulanmadı.

## Ölçüm aracı

`tools/hdfc_close_probe.py` — TR ağından çalıştırılınca site kökü, kaynak düğmeleri,
`/video/{id}/` yanıtı, oynatıcı sayfasının üç User-Agent ile HTTP kodu/boyutu/
`Cache-Control` değeri ve sayfadaki CDN host'larını (sorgu dizgeleri gizlenmiş hâlde)
raporlar. Bu ortamdan ve GitHub runner'larından site 451/403 verdiği için canlı
gövde alınamadı; aracın kendisi bu raporda üretildi.

## Kullanıcıdan gereken (varsa hâlâ sorun)

Uygulama günlüğünde `KitsugiPlayer` / `HDCH` satırları sorunu tek adımda ayırır:

- `oynatıcı HTTP 404/403 ...` → host/UA kuralı yine değişmiş (satır hangi UA ve
  Referer denendiğini yazar).
- `adres çözülemedi ... hedefli=true` → sayfa geldi, JS şablonu değişmiş.
- `akış HTTP 403/404` → adres çözüldü ama CDN vermiyor (imza/Referer).
- `hiçbir varyant sonuç vermedi` → oynatıcı sayfası hiç açılmadı.
