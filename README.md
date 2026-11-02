# 🔌 Kitsugi Plugins

Kitsugi Android & Android TV uygulaması için özel olarak derlenmiş, optimize edilmiş ve güncellenmiş Cloudstream 3 (CS3) eklenti havuzudur.

---

## ✅ Aktif Kaynaklar (8)

Depo **yalnızca aşağıdaki 8 kaynağı** yayınlar. Bunların dışındaki tüm eklentiler **silinmemiştir**; sadece devre dışıdır (manifeste yazılmaz, derlenmez).

| Kategori | Eklenti | Sürüm |
| --- | --- | --- |
| Anime | **Anizium** | 25 |
| Anime | **Anizm** | 5 |
| Anime | **AnimeciX** | 24 |
| Anime | **TrAnimeIzle** | 44 (kaynak kodu; yayınlanması derlemeye bağlı) |
| Film / Dizi | **FilmMakinesi** | 72 |
| Film / Dizi | **HDFilmCehennemi** | 63 |
| Dizi | **DiziBox** | 27 |
| Film / Dizi | **HDFilmİzle** | 11 |

> **TrAnimeIzle v44 (kaynak kodu):** CAPTCHA ve kaynak zinciri için düzeltmeler içerir.
> Bu sürümün cihazlara ulaşması için derlenip yayınlanması gerekir; canlı oynatma
> henüz doğrulanmamıştır.
>
> **Anizm (v5)** ve **TrAnimeIzle (v41)** video kaynak zinciri bu sürümde tamamen yenilendi:
> kaynaklar artık **paralel** çözülüyor (30+ saniyelik beklemeler bitti), sitenin kendi
> oynatıcı uçları birebir kullanılıyor ve **HLS master listeleri kalite varyantlarına**
> ayrılıyor. Ayrıntı: [`research/anime-plugin-rewrite-2026-10-25.md`](research/anime-plugin-rewrite-2026-10-25.md).
>
> **TrAnimeIzle v39**: sitenin HTML/API sözleşmesi bağımsız istemcilerle (Aniyomi
> `yuzono/anime-extensions`, CloudStream `UmayTrade/MyTV`) çapraz doğrulandı. Captcha
> artık WebView diyaloğu açmadan HTTP ile çözülüyor; `embed2/?id=` ve
> `downloadru2.php?url=<base64>` sarmalları açılıyor; OpTraco/AitrVip, Vidmoly ve
> SendVid için yerel çözücüler eklendi. Sayfa/uç/oynatıcı tablosu:
> [`research/tranimeizle-html-haritasi-2026-10-28.md`](research/tranimeizle-html-haritasi-2026-10-28.md).
>
> **TrAnimeIzle v40**: captcha kapısı artık **yanıt adresinden** de tanınıyor
> (`/api/CaptchaChallenge`); HTTP gövdesi tek kez okunuyor (çift okuma sayfayı
> boşaltabiliyordu) ve `postForm` yolu da captcha tekrarına dâhil. Sahadaki
> "kaynak eksik/boş" şikâyetinin iki sessiz nedeni bu iki hataydı.
>
> **TrAnimeIzle v41 — "eklenti çalışma zamanı uyumsuzluğu" düzeltmesi:** eklenti
> v17'den beri taşınan **WebView + AlertDialog "bot kontrolü" diyaloğunu**
> (`BotKontrol.kt`, 948 satır) yüklüyordu. Bu, eklentiyi `android.webkit`,
> `android.app/view/widget` ve `SharedPreferences` sınıflarına bağlıyordu —
> **çalışan hiçbir eklentide bu bağımlılıklar yok**. Uygulamanın eklenti sınıf
> yükleyicisi bu sınıfları çözemediğinde *LinkageError (KTS-CSP-9110)* verip
> eklentiyi hiç açmıyordu; kullanıcı bunu "eklenti çalışma zamanı uyumsuzluğu"
> olarak görüyordu. Katman tamamen kaldırıldı: BotKontrol artık saf Kotlin,
> `SharedPreferences` okuması yok, `android.util.Base64` yerine saf çözücü.
> Paket 163,8 KB → 140,8 KB; dex metot sayısı 1319 → 1102.

> **FilmEkseni** ve **HDFilmDelisi** devre dışı bırakılmış, yerlerine **DiziBox** ve **HDFilmİzle** etkinleştirilmiştir. Eski kaynak klasörleri silinmemiştir. Eklentilerin uygulamada görünmesi için bu depoyu ekleyip uygulama içinden depoyu yenilemeniz gerekir. Kaynak atıfları için [`THIRD_PARTY_SOURCES.md`](THIRD_PARTY_SOURCES.md) dosyasına bakın.

> Sürüm sütunu bilgilendirme amaçlıdır; `plugins.json`'a yazılan gerçek sürüm her zaman `.cs3` dosyasının içindeki `manifest.json`'dan okunur.

> ⚠️ Depoyu uygulamada güncelledikten sonra daha önce kurduğunuz diğer eklentiler listeden kaybolur; cihazda kurulu kalanları **Ayarlar → Eklentiler** üzerinden kaldırabilirsiniz.

---

## 🔁 Eklenti Açma / Kapatma

Tüm eklenti yönetimi tek dosyadan yapılır: **`enabled_plugins.txt`**

- **Devre dışı bırakmak** → satırı silin (klasörü silmeyin).
- **Tekrar açmak** → klasör adını bir satır olarak ekleyin.
- Push sonrası `Derleyici` iş akışı `builds` dalındaki `plugins.json`'ı otomatik yeniden üretir.

Bu dosyayı okuyan yerler:

| Dosya | Görevi |
| --- | --- |
| `enabled_plugins.txt` | Tek doğruluk kaynağı (eklenti listesi) |
| `settings.gradle.kts` | Sadece listedeki dizinleri derler |
| `merge_plugins.py` | `plugins.json`'a sadece listedekileri yazar, URL/hash/sürümü gerçek `.cs3` dosyasından üretir, tanıtım verisini `prebuilt_plugins.json`'daki küratörlü kayıttan alır |
| `.github/workflows/Derleyici.yml` | Sadece listedeki dizinler için derleme tetikler |

---

## 🏷️ Tanıtım Verisi ve `status`

Aktif eklentilerin `plugins.json` kaydındaki **tanıtım alanları** (`description`, `iconUrl`, `tvTypes`, `language`, `authors`, `name`, `apiVersion`) `prebuilt_plugins.json` içindeki **küratörlü kayıttan** alınır. Gradle'ın `makePluginsJson` çıktısı bu alanları üretmediği için eskiden kayıt ya boş kalıyordu ya da bozuk değer taşıyordu (`&sz=%size%` gibi doldurulmamış ikon adresi, sadece `["Movie"]` görünen dizi kaynakları, hiç açıklaması olmayan Anizium).

**Küratörlü kayıttan alınmayanlar:** `url`, `fileSize`, `fileHash`, `version`. Bunlar depodaki gerçek `.cs3` dosyasından üretilir; başka bir depodan (ör. Kraptor) gelen değer kopyalanırsa uygulama indirdiği dosyayı doğrulayamaz ve kurulum sessizce başarısız olur.

`status` değerleri CloudStream karşılıklarıyla: `0` Down, `1` Ok, `2` Slow, `3` Beta only. **`status: 3` kayıtlar uygulamanın normal sürümünde listelenmez**; bu depoda devre dışı bırakma işi `enabled_plugins.txt`'ye ait olduğu için `merge_plugins.py` manifeste yazarken `3 → 1` düzeltmesi yapar (status hiç yazılmamışsa da `1` kabul edilir).

---

## ✅ Yayın Doğrulama ve CDN Önbelleği

`builds` dalı `git commit --amend` + force-push ile güncellendiği için **jsDelivr** gibi CDN'ler eski
`plugins.json`'ı önbellekten döndürebiliyor. Kitsugi uygulaması depoyu okurken sırasıyla
`raw.githubusercontent.com` → `api.github.com` → `cdn.jsdelivr.net` → Codeberg aynasını dener ve
**ilk başarılı yanıtı** kullanır; "GitHub Vekil Sunucu (jsDelivr)" ayarı açıkken jsDelivr **birinci**
sıradadır. Önbellek bayat kaldığında manifestte bulunan eklentiler (ör. `OpenAnime`, `TrAnimeIzle`)
uygulamada "depoda yok" gibi görünüyordu.

Bu yüzden iki otomatik adım eklendi:

| Adım | Ne yapar |
| --- | --- |
| `tools/purge_cdn.py` | Yayından sonra `repo.json`, `plugins.json` ve tüm `.cs3` dosyaları için jsDelivr önbelleğini temizler, HTTP 200 yanıtındaki `throttled`/sağlayıcı sonuçlarını da denetler ve ham GitHub ile aynayı karşılaştırır |
| `tools/verify_publish.py` | `repo.json → plugins.json → .cs3` zincirini **uygulamanın kullandığı adreslerden** indirip doğrular: etkin listedeki her eklenti tam bir kez mi var, fazladan/yinelenen kayıt sızmış mı, `status` 1 mi, URL bu depoyu mu gösteriyor, boyut + sha256 tutuyor mu, ZIP içinde `manifest.json`/`classes.dex` var mı ve iç manifestin `name`/`version` değeri listeyle aynı mı |

- `Derleyici` iş akışı yayından sonra `tools/purge_cdn.py` çalıştırır; purge doğrulanamazsa yayın işi başarısız olur, yeşil görünmez.
- `Yayin Dogrulama` iş akışı her derlemeden sonra, `main`'e dokunan push'larda ve 6 saatte bir
  `tools/verify_publish.py` çalıştırır; eksik/bayat bir kayıt varsa kırmızı olur (jsDelivr bayatsa
  önce purge edip yeniden dener).

Yerelde denemek için:

```bash
python3 tools/purge_cdn.py --check   # aynalar aynı mı?
python3 tools/verify_publish.py      # uçtan uca yayın doğrulaması
```

> **Not:** Canlı site testi (arama → bölüm → video/altyazı) GitHub Actions runner'larından
> yapılamıyor: `openani.me` ve `tranimeizle.io/.live` Cloudflare üzerinden veri merkezi IP'lerine
> `403 Access denied` dönüyor. Bu yüzden video/ses/altyazı doğrulaması cihaz üzerinde yapılmalıdır.

---

## 📥 Eklenti Deposunu Uygulamaya Ekleme

Kitsugi uygulamasında eklentileri yükleyebilmek için aşağıdaki depo bağlantı adresini (Repository URL) uygulamanıza eklemeniz gerekmektedir:

### 🔗 Depo Bağlantı Adresi (Repository URL)
```text
https://raw.githubusercontent.com/f26901964-eng/Kitsugi-Plugins/builds/repo.json
```

### 🛠️ Kurulum Adımları
1. **Kitsugi** uygulamasını açın.
2. Ana sayfadan **Ayarlar** (Settings) sekmesine gidin.
3. **Eklentiler** (Addons) -> **Cloudstream Uzantıları** (Cloudstream Extensions) menüsünü açın.
4. Sağ üstte yer alan veya menüdeki **Repo Ekle** (Add Repository) seçeneğine tıklayın.
5. Yukarıdaki **Depo Bağlantı Adresi**'ni kopyalayıp ilgili alana yapıştırın ve **Ekle** (Add) butonuna basın.
6. Depo başarıyla eklendikten sonra listeden dilediğiniz dizi/anime sağlayıcı eklentisini tek tıkla yükleyebilirsiniz.

---

## 🔄 Otomatik Yönlendirme ve Geçiş
Eski eklenti depolarını (`keyiflerolsun`, `maarrem/cs-Kekik` vb.) kullanan kullanıcılar için Kitsugi uygulaması otomatik yönlendirme sunar. Eski depoları eklediğinizde uygulama bunu algılar ve otomatik olarak bu güncel depoya (`f26901964-eng/Kitsugi-Plugins`) yönlendirerek eklentileri en güncel sürümleriyle günceller.

---

## 🛠️ Geliştiriciler İçin Derleme Süreci

Bu depodaki eklentiler Kotlin diliyle yazılmış kaynak kodlarından otomatik olarak derlenmektedir.
- **`main` Branch**: Eklentilerin kaynak kodlarını barındırır.
- **`builds` Branch**: GitHub Actions (`Derleyici.yml`) tarafından otomatik olarak derlenen `.cs3` eklenti dosyalarını ve eklentilerin listelendiği `plugins.json` / `repo.json` dosyalarını barındırır.

Eklentiler üzerinde değişiklik yapmak için `main` branch'ine yapılan push işlemleri, eklentileri otomatik olarak derleyip `builds` branch'ine aktaracaktır.
