package com.kraptor

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.JsonAsString
import com.lagradost.nicehttp.Session
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TrAnimeIzle ana API (DEX'teki com.kraptor.TrAnimeIzle'in kaynak yeniden yazımı).
 *
 * Akış: arama/anasayfa/bölüm listesi → `load` (metadata + Kitsu zenginleştirme +
 * bölümler) → `loadLinks` (fansub → sourcePlayer → iframe → loadExtractor).
 * Bot kontrolü [BotKontrolKt] üzerinden yapılır.
 *
 * v17 (cihaz kanıtı + site arşivi 22.09.2026):
 *  - Site Cloudflare Error 1005 (ASN ban) arkasında; cihaz WebView oturumu
 *    tek yasal yol. Tüm site istekleri [fetchHtml] üzerinden: tarayıcı
 *    başlıkları (Firefox UA, Referer, Accept-Language) + kayıtlı oturum;
 *    bot/başarısız cevapta WebView diyaloğuyla oturum yenileme + tek tekrar.
 *  - sourcePlayer: site kendi JS'inde TEK PARAMETRELİ `POST /api/sourcePlayer/{id}`
 *    kullanıyor (data-id) → birincil yol; eski iki parametreli form yedek.
 *  - Sonuç kartları tıklamada `data-href`'e gidiyor → URL çözümü önce data-href.
 *  - episodeId: input#EpisodeId yoksa `animeWatch.initialize(aId, eId, ...)`
 *    script çağrısından regex ile.
 *  - Arama sorgusu URL-kodlanıyor.
 *  - Her adım log'a yazılıyor (kraptor_TrAnime) — cihaz kanıtı için.
 *  - Harici (potansiyel olarak eski) domain listesi kaldırıldı.
 *
 * v18:
 *  - Arama page 1: ÇIPLAK `/arama/{sorgu}` (site'nin kendi JS'i ?page= kullanmıyor).
 *  - Sonuç kartları flx-block ile gelmezse href deseni (…-bolum-izle /
 *    …-anime-izle) + resim tabanlı yedek çözümleme (parseGenericCards).
 *  - isBotBody: Cloudflare challenge başlıkları ("Just a moment…" vb.) da
 *    bot cevabı sayılır.
 *  - BotKontrol: CF challenge sayfası "yüklendi" sayılmaz (WebView kendisi
 *    çözer, beklenir).
 *
 * v19:
 *  - Cihaz kanıtı (v18): arama 0 sonuç + HİÇ diyalog açılmadı → site
 *    oturumsuz isteklere bot sayfası değil BOŞ 200 sayfa dönüyor.
 *    DEX davranışı geri getirildi: arama/ana sayfa/load/loadLinks
 *    isteklerinden ÖNCE oturum garantisi (sessionBootstrap; kayıtlı
 *    oturum varsa diyalog açılmaz, yoksa WebView ~5 sn'de kapanır).
 *
 * v20:
 *  - Cihaz kanıtı (v19): arama YİNE 0 sonuç + diyalog YİNE yok → v19'un
 *    hatası: kayıtlı ESKİ oturum çerezi (v17'den) doğrulanmadan kullanıldı;
 *    site eski/geçersiz oturuma sessizce BOŞ sayfa döndürüyor.
 *  - Düzeltme (3 katman, her koşulda içerik garantisi):
 *    1) ensureValidSession(): epoch başında ANA SAYFA PROB'U (GET).
 *       Kart linki var → oturum GEÇERLİ, diyalog yok. Boş/bot →
 *       eski çerezler TEMİZLENİR + ana sayfada taze WebView diyaloğu
 *       (challenge varsa çözülür; ana sayfanın içeriği garantili).
 *    2) GET boş içerik döndürürse (site document-dışı isteğe farklı
 *       dönebilir) → aynı URL WebView'da açılır, sayfanın TAM DOM'u
 *       (outerHTML) oradan alınır ve O çözülür.
 *    3) BotKontrol diyaloğu artık çerez seti + sayfa DOM HTML'i döndürür
 *       (WebFetchResult); botKontrolFetch(wipeFirst) taze oturum açar.
 *  - HTTP istekleri Firefox document/XHR imzasıyla (sec-fetch-* başlıkları).
 *
 * v21 (cihaz kanıtı: arama + bölüm SAYFASI ÇALIŞIYOR, KAYNAK LİSTESİ BOŞ):
 *  - sourcePlayer cevabındaki oynatıcı ÜÇ formatta çözümlenir:
 *    <iframe> (kayıtlı extractor'lar), <video>/<source> düz URL,
 *    HTML/JS içindeki mp4/m3u8 adresi (site video.js kullanıyor →
 *    iframe'siz doğrudan oynatıcı mümkün).
 *  - iframe için kayıtlı extractor yoksa iframe sayfası DOĞRUDAN çekilir
 *    ve içindeki düz video adresi aranır (host agnostik yedek).
 *  - fansub/source seçicileri toleranslı (yapı değişse bile data-fid /
 *    data-id'li öğe bulunur — site.js 22.09.2026 davranışı).
 *  - Hata artık EKRANDA görünür: her basamağın nedeni kaynak panelinde
 *    ErrorLoadingException mesajı olarak gösterilir (KTS-CSP-9103 yerine).
 *  - loadLinks bölüm sayfası da içerik garantili (webViewFallback=true).
 *  - (v21.1: derleyici tetikleyici dokunuş — kod değişikliği yok.)
 *
 * v22 (KANIT MODU — cihazdaki gerçek HTML'in ekranda görünmesi):
 *  - Araştırma (20.07.2026 + 22.09.2026 arşivleri, site.js TAMI birebir aynı;
 *    videoSource.css: #videoPlayer > iframe; altyazı/kalite API'si YOK):
 *    kaynak zinciri = bölüm sayfası (data-fid öğeleri) →
 *    POST /api/fansubSources {EpisodeId,FansubId} → HTML #sourceList
 *    (li.sourceBtn: data-id/data-eid/p.title = KALİTE/FANSUB adı) →
 *    POST /api/sourcePlayer/{id} → {source: HTML} → #videoPlayer > iframe.
 *  - KANIT MODU: kaynak boşsa hata mesajı artık AŞAMALI GERÇEK HTML içerir:
 *    KANIT[1] sayfa + fansub bölgesi parçası, KANIT[2] fansubSources
 *    cevabı parçası, KANIT[3] sourcePlayer cevabı parçası, KANIT[4]
 *    iframe/direct/embed adresleri → cihaz ekran görüntüsüyle sorun
 *    SONUNA KADAR teşhis edilir.
 *  - Kalite: kaynak adı 1080p/720p/4K/HD içeriyorsa ExtractorLink.quality.
 *  - Altyazı: source HTML + iframe sayfasındaki <track> ve .vtt/.srt
 *    adresleri subtitleCallback ile eklenir (varsa).
 *
 * v23 (TÜM KAYNAK ZİNCİRİ ONARIMI — "akış gelmedi" kök nedeni):
 *  - KÖK NEDEN (kanıt: site.js axios kodu + NiceHttp 0.4.13 kaynak kodu):
 *    fansubSources POST'u FORM gövdeyle + sahte Content-Type: application/json
 *    başlığıyla gidiyordu; site GERÇEK JSON gövde bekliyor
 *    ({"EpisodeId":123,"FansubId":456}) → sunucu gövdeyi okuyamadı →
 *    kaynak listesi v17'den beri hep boştu. Artık JsonAsString ile BİREBİR
 *    axios JSON gövdesi; FORM gövde + JSON-sarmal cevap yedekleri de denenir.
 *  - sourcePlayer: cevap JSON değil düz HTML ise o HTML doğrudan oynatıcı
 *    HTML'i sayılır.
 *  - iframe'ler: base URL ile çözülür (göreli src artık bozulmaz); TÜM
 *    iframe'ler sırayla denenir (reklam iframe'i ön planda olsa bile).
 *  - Sayfada sunucu tarafı kaynak butonu varsa API'ye gerek kalmadan çözülür.
 *  - SON KATMAN: tüm API yolları boşsa bölüm sayfası WebView'de AÇILIR,
 *    render edilen DOM (tarayıcıda görünenin birebiri) çözülür; oturum
 *    taze çerezle yenilenip API yeniden denenir.
 *  - Oynatma başlıklarına User-Agent eklendi (CDN koruması için).
 *
 * v24 (EKSİKSİZ KAYNAK — site listesindeki HER kaynak link olsun):
 *  - Cihaz kanıtı (V23): JSON gövde düzeltmesi kaynakları GETİRDİ, ama
 *    site 10 kaynak sunarken sadece iframe/MP4 tabanlı 3'ü çözüldü.
 *    Dosya-host kaynakları (Gdrive, Mega, SendVid, Voe, Ok.RU, Byandex,
 *    Sibnet, MailRu…) HTML içinde iframe DEĞİL düz dosya linki taşır →
 *    önceki sürümler onları "iframe/video yok" diye atlıyordu.
 *  - v24: oynatıcı HTML'indeki TÜM adresler taranır; video host'una
 *    uyan ilk adres (drive.google/mega.nz/ok.ru/sendvid/voe.sx/sibnet/
 *    byandex/vudeo/upstream…) kaynağın linki olarak eklenir.
 *  - Host sayfaları (sendvid/voe gibi) iç stream adresi taşır: sayfa
 *    çekilip içinde mp4/m3u8 aranır; JSON anahtarları da çözülür
 *    ("file":"...mp4" / "src":"...m3u8"). Gdrive /file/d/{id} →
 *    doğrudan indirme adresine çevrilir.
 *  - Çözülemediyse bile HAM host adresi link olarak eklenir — kaynak
 *    listesinde TEK bir kaynak bile boş kalmaz.
 *
 * v25 (SERTLEŞTİRME + SİTENİN KENDİ 2. OYNATICI YOLU):
 *  - site.js satır-satır yeniden incelendi (iki dönem BİREBİR aynı):
 *    video ile ilgili TÜM uçlar: fansubSources (JSON gövde),
 *    sourcePlayer/{id} (boş gövde → {source, contributerNotes}),
 *    preVideoAd/{id} (boş gövde → DÜZ oynatıcı HTML'i; site günün
 *    ilk tıklamasında bunu kullanır).
 *  - YENİ YOL: sourcePlayer boş/bozuk dönerse site'nin kendi
 *    ilk-tıklama yolu olan POST /api/preVideoAd/{id} denenir
 *    (bağımsız 2. oynatıcı kaynağı).
 *  - TÜM ağ isteklerine zaman aşımı eklendi (POST 15 sn, sayfa 10 sn) —
 *    takılan host/iframe artık zinciri BOĞMAZ (v24'te "bir anda hata"
 *    veren en olası mekanizma: sınırsız host sayfası çekimleri).
 *  - CancellationException YUTULMAZ — uygulama loadLinks'i iptal
 *    ettiğinde eklenti anında durur (arka planda işe devam edip son
 *    anda hata vermesi engellenir).
 *  - Host sayfası çekimi YALNINCA sayfasında stream taşıyan hostlar
 *    için (sendvid/voe/streamtape); Gdrive adres dönüşümü çekimsiz,
 *    diğer hostlar çekimsiz ham link.
 *  - Beklenmedik hata mesajına istisna türü eklenir (teşhis).
 *
 * v26 (DOMAIN ROTASYONU — "tak diye hata" KÖK NEDENİ):
 *  - KANIT (25.10.2026): site artık www.tranimeizle.io adresinde yayın
 *    yapıyor (izleani.me canlı takipçi — 3 saat önceki bölümlerin linkleri
 *    .io; Semrush: .io 14.47M ziyaret/ay; cs-kraptor'un her gün güncellenen
 *    domain dosyası .io). Eklenti tek domain'e (.live) KİLİDLİYDİ — site
 *    domain değiştirdiğinde tüm istekler ölü domain'e gitti → veri hiç
 *    gelmedi, "tak diye hata". (.live son çalışması: V23 cihaz kanıtı.)
 *  - Düzeltme: domain rotasyonu. Aday listesi [.io, .live, .top]; ana
 *    sayfa PROB'u ile canlı domain otomatik seçilir (8 sn zaman aşımı),
 *    sonuç prefs'e kalıcı yazılır. Ağ hatasında domain değiştirilip
 *    istek BİR kez tekrarlanır. mainPage/arama URL'leri mevcut domain'e
 *    haritalanır. Varsayılan domain .io.
 *
 * v27 - "genel dusme" raporu sonrasinda tam denetim (Drive 01-04 +
 *      ANIZM-ARASTIRMA + V23-V26 raporlari satir satir okundu) ve kalici
 *      cozum. Kanit standardi 04 C/E: gercek HTTP/cihaz bulgusu.
 *   1. KOK NEDEN (cokme): bot kontrolu UI thread'lerde latch ile bekliyordu
 *      (await(2-3 sn) x 45 iterasyon) -> ANR. Tum JS okumalari artik
 *      `evalJs` uzerinden suspend; `setAcceptThirdPartyCookies` API 21+.
 *   2. KOK NEDEN (cokme): java.time API 26+ gerektirir; minSdk 21 oldugu
 *      icin eski cihazda NoSuchMethodError. LocalDate/DateTimeFormatter
 *      kalkti, SimpleDateFormat + try/catch (yalnizca yerel tarih).
 *   3. COKME YOLU: search/getMainPage istisna firratirdiginda uygulama
 *      eklenti cagrisinda dusuyordu -> ikisi de korumali, CancellationException
 *      geri atilir; bos sonuc artik kullaniciya 400+ karakter ham HTML
 *      basmiyor (KANIT yalnizca logcat'e, kisa mesaj + take(600)).
 *   4. TAKILMA: sayfa istekleri timeout=15, Kitsu 12, extractor 10.
 *   5. OYNATICI: source/name alanlari birbirine girmisti -> emitPlayerLink
 *      helper'i (source = saglayici adi, name = eklenti adi) + referer + UA
 *      + kalite; 3 kopya blok tek kaynaga indirildi.
 *   6. DEPRECATION: SubtitleFile(...) -> newSubtitleFile(...).
 *
 * v39 (sitenin HTML sözleşmesi bağımsız istemcilerle karşılaştırıldı):
 *  - Kanıt kaynakları: Aniyomi `yuzono/anime-extensions` (tr/tranimeizle,
 *    `ShittyCaptchaInterceptor` dâhil) ve CloudStream `UmayTrade/MyTV`
 *    (TrAnimeIzle v4, IconCaptcha + optraco + downloadru2 + embed2 çözümü).
 *    Ayrıntılı tablo: `research/tranimeizle-html-haritasi-2026-10-28.md`.
 *  - **Captcha yanlış pozitifi giderildi**: gövdede geçen herhangi bir
 *    `/api/Captcha` metni "bot" sayılıyordu; sitenin kendi JS'i bu uçtan söz
 *    ettiği için NORMAL sayfalar bot sanılıp boş dönüyordu. Artık yalnızca
 *    gerçek kapı işaretleri (`captcha-holder`, `iconCaptcha`,
 *    `CaptchaChallenge`) aranıyor.
 *  - **Captcha artık HTTP ile çözülüyor**: kapıya düşen istek, WebView
 *    diyaloğu AÇILMADAN 5 görselin MD5'i üzerinden çözülüp BİR kez tekrar
 *    ediliyor (Aniyomi'nin `cID=0/rT=1/tM=light` akışı birebir).
 *  - Oynatıcı hedefleri tek yerde toplandı ([TrAnimeIzleSite.playerTargets]);
 *    `embed2/?id=` ve `downloadru2.php?url=<base64>` sarmalları açılıyor.
 *  - Yeni yerel çözücüler: OpTraco/AitrVip, Vidmoly, SendVid.
 *
 * v40 (sahadan gelen eksik-kaynak şikâyeti sonrası):
 *  - **Captcha artık YANIT ADRESİNDEN de yakalanıyor** (`/api/CaptchaChallenge`).
 *    Kanıt: Aniyomi `ShittyCaptchaInterceptor` korumalı isteği URL'den tanıyor;
 *    gövde bazen boş/kısa JSON oluyor. Eskiden yalnızca gövdeye bakıldığı için
 *    kapı kaçıyor, uç boş dönüyor ve kaynak listesi sessizce eksiliyordu.
 *  - **Gövde tek okunuyor**: `resp.text` iki kez okunuyordu; nicehttp gövdesi
 *    tüketilebilir olduğundan ikinci okuma `null` dönüp sayfayı boşaltabiliyordu.
 *    Tüm HTTP yüzeyi (GET/XHR/POST-JSON/POST/POST-FORM) tek okumaya çevrildi.
 *  - `postForm` da captcha tekrarına dâhil edildi (form gövdeli uçlar da kapıya
 *    düşebiliyor; eskiden yalnızca JSON/form çağrısının ilk yolu korunuyordu).
 *
 * v41 — **"eklenti çalışma zamanı uyumsuzluğu" (KTS-CSP-9110) düzeltmesi**:
 *  - Kanıt: Kitsugi-Beta sürüm notları bu hatayı "Linkage/runtime incompatibility"
 *    olarak tanımlıyor ve eklenti yüklenmeden önce MainAPI/KSerializer/Json
 *    sınıflarının plugin classloader'ından görünürlüğünü doğruluyor.
 *  - Ölçüm (yayındaki v40 dex'i vs çalışan eklentiler):
 *      android.webkit  → v40 VAR | Anizm yok | HDFilmCehennemi yok
 *      android.app/view/widget → v40 VAR | Anizm yok | HDFilmCehennemi yok
 *      android.content.SharedPreferences → v40 VAR | ikisinde yok
 *    Bu sınıfların tamamı v17–v40 WebView "bot kontrolü" diyaloğundan geliyordu.
 *  - Çözüm: WebView/AlertDialog katmanı **tamamen kaldırıldı** (BotKontrol artık
 *    saf Kotlin), SharedPreferences okuması kaldırıldı, `android.util.Base64`
 *    yerine saf Kotlin çözücü, `Semaphore` yerine kendi eşzamanlılık kapısı.
 *  - Neden kayıp yok: WebView yalnızca **veri merkezi IP engeli** (Cloudflare
 *    Error 1005) içindi; kullanıcı telefondan bağlanıyor, engel yok. Captcha
 *    zaten v39'da HTTP ile çözülüyor.
 *
 * v42 — **KTS-CSP-9110 HÂLÂ DEVAM ETTİ: asıl neden sürüm uyumsuz API'ler**
 *  - Ölçüm yöntemi: yayındaki dex'in `method_ids` tablosu, depodaki 8 çalışan
 *    eklentinin dex'leriyle karşılaştırıldı. "Yalnızca TrAnimeIzle'de geçen"
 *    21 metot referansı çıktı; içlerinde **sürüme duyarlı** olanlar:
 *
 *    | Metot | Kaynak | Kitsugi'de |
 *    | --- | --- | --- |
 *    | `LoadResponse#getSyncData` | CloudStream'e sonradan eklendi | riskli |
 *    | `NiceResponse#getCookies` | NiceHttp 0.4.13 (uygulama 0.4.4 gömüyor) | riskli |
 *    | `NiceResponse#getBody` | NiceHttp 0.4.13 | riskli |
 *    | `MainAPIKt#addDate` | sürüme duyarlı | riskli |
 *
 *    Kitsugi uygulaması `com.lagradost.api:library-android:1.0.2-local` +
 *    `NiceHttp 0.4.4` gömüyor; bu depo `cloudstream3:pre-release` +
 *    `NiceHttp 0.4.13` ile derliyor. Yeni API'ler istemcide yoksa
 *    `NoSuchMethodError` → **KTS-CSP-9110**.
 *  - Düzeltme: `syncData`, `addDate`, `resp.cookies`, `resp.body` tamamen
 *    kaldırıldı; ham bayt indirme saf `HttpURLConnection`'a çevrildi.
 *  - Ayrıca `ExtractorApi` mirası (Optraco/Hdvid/Hexload sınıfları) kaldırıldı:
 *    soyut metot imzaları sürüme duyarlı; uyuşmazlıkta **yükleme anında**
 *    `AbstractMethodError` üretiyordu. Üç host artık düz fonksiyon.
 */
class TrAnimeIzle : MainAPI(), TrStreamHttp {

    override var mainUrl: String = DEFAULT_DOMAIN
    override var name = "TrAnimeIzle"

    // v26: domain rotasyonu durumu.
    // - domPrefs: kalıcı tercih (son doğrulanan domain).
    // - mainPageBase: mainPage haritasının kurulumda hangi domainle
    //   üretildiği (run-time domain değişince URL'ler buna göre haritalanır).
    // - lastDomainCheckAt: canlı domain kontrolü sıklığı (TTL).
    private var mainPageBase: String = mainUrl
    private var lastDomainCheckAt = 0L
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Anime)

    // ! Yollar DEX'teki ile birebir aynı (mainUrl + suffix); listeler/* yolları
    //   22.09.2026 ana sayfa arşivinde doğrulandı (/listeler/eklenen/sayfa-1 vb.).
    override val mainPage = mainPageOf(
        "$mainUrl/listeler/yenibolum/sayfa-" to "Yeni Bölümler",
        "$mainUrl/listeler/eklenen/sayfa-" to "Yeni Animeler",
        "$mainUrl/listeler/populer/sayfa-" to "Popüler Animeler",
        "$mainUrl/animeizle/aksiyon-anime-izle-" to "Aksiyon",
        "$mainUrl/animeizle/askeri-anime-izle-" to "Askeri",
        "$mainUrl/animeizle/bilim-kurgu-anime-izle-" to "Bilim Kurgu",
        "$mainUrl/animeizle/buyu-anime-izle-" to "Büyü",
        "$mainUrl/animeizle/dogaustu-gucler-anime-izle-" to "Doğaüstü Güçler",
        "$mainUrl/animeizle/dram-anime-izle-" to "Dram",
        "$mainUrl/animeizle/dovus-anime-izle-" to "Dövüş",
        "$mainUrl/animeizle/ecchi-anime-izle-" to "Ecchi",
        "$mainUrl/animeizle/fantastik-anime-izle-" to "Fantastik",
        "$mainUrl/animeizle/gerilim-anime-izle-" to "Gerilim",
        "$mainUrl/animeizle/gizem-anime-izle-" to "Gizem",
        "$mainUrl/animeizle/harem-anime-izle-" to "Harem",
        "$mainUrl/animeizle/komedi-anime-izle-" to "Komedi",
        "$mainUrl/animeizle/korku-anime-izle-" to "Korku",
        "$mainUrl/animeizle/macera-anime-izle-" to "Macera",
        "$mainUrl/animeizle/mecha-anime-izle-" to "Mecha",
        "$mainUrl/animeizle/movie-anime-izle-" to "Film",
        "$mainUrl/animeizle/okul-anime-izle-" to "Okul",
        "$mainUrl/animeizle/psikolojik-anime-izle-" to "Psikolojik",
        "$mainUrl/animeizle/romantizm-anime-izle-" to "Romantizm",
        "$mainUrl/animeizle/seinen-anime-izle-" to "Seinen",
        "$mainUrl/animeizle/shoujo-anime-izle-" to "Shoujo",
        "$mainUrl/animeizle/shoujo-ai-anime-izle-" to "Shoujo Ai",
        "$mainUrl/animeizle/shounen-anime-izle-" to "Shounen",
        "$mainUrl/animeizle/shounen-ai-anime-izle-" to "Shounen Ai",
        "$mainUrl/animeizle/yasamdan-kesitler-anime-izle-" to "Yaşamdan Kesitler",
        "$mainUrl/animeizle/spor-anime-izle-" to "Spor"
    )

    // ------------------------------------------------------------------
    // Arama + ana sayfa
    // ------------------------------------------------------------------

    /**
     * v40: **arama yolu** kodlaması.
     *
     * Kanıt — sitenin kendi istemci kodu (`/js/site.js`, arşiv 31.08.2026):
     * ```
     * window.location.href = "/arama/" + searchBar.value
     * ```
     * Tarayıcı bu ham metni **yol parçası** olarak kodlar → boşluk `%20` olur.
     * `URLEncoder` ise boşluğu `+` yapar; yol parçasında `+` düz artı
     * karakteridir → sunucu "one+piece" diye arar ve **0 sonuç** döner.
     * Bu düzeltme, boşluklu aramaların (hemen her anime adı) sessizce boş
     * dönmesini engeller.
     */
    private fun encodeSearchPath(raw: String): String =
        URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (!TrAnimeIzleHelper.isAllowedVersion) {
            return newSearchResponseList(emptyList(), false)
        }
        // v27: arama ASLA istisna fırlatmaz — kontrolsüz istisna eklenti
        // çubuğundan yayılır ve uygulamayı çökertir (kullanıcı raporu).
        return try {
            searchInternal(query, page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "search('$query') hatası: ${e::class.simpleName}: ${e.message}")
            newSearchResponseList(emptyList(), false)
        }
    }

    /** Katalog önbelleği (site araması boş dönerse isim eşleştirmesi için). */
    private var catalogueCache: Pair<Long, List<TrCard>>? = null

    /**
     * Site araması + isim eşleştirme.
     *
     * Kitsugi kaynağı KENDİ başlığıyla arar ("The Apothecary Diaries Season 3"),
     * site ise Türkçe/romanize adlarla ("The Apothecary Diaries 3. Sezon") çalışır.
     * Bu yüzden:
     *  1. Sorgunun varyantları ([TrAnimeIzleMatch.queryVariants]) sırayla denenir
     *     ("Sezon 3" atılmış, ":" sonrası atılmış, aksan sadeleştirilmiş...).
     *  2. Hiç sonuç gelmezse site kataloğu (popüler/yeni bölümler listeleri, 30 dk
     *     önbellek) indirilip **yerel** isim eşleştirmesi yapılır — arama ucu
     *     bozuk/boş olsa bile doğru anime bulunur.
     *  3. Sonuçlar [TrAnimeIzleMatch.rank] ile puanlanıp en iyi eşleşme başa alınır;
     *     uygulamanın kendi başlık eşleştiricisi doğru kartı ilk sırada görür.
     */
    private suspend fun searchInternal(query: String, page: Int): SearchResponseList {
        ensureDomainAlive()
        val variants = TrAnimeIzleMatch.queryVariants(query)
        if (variants.isEmpty()) return newSearchResponseList(emptyList(), false)

        val collected = LinkedHashMap<String, TrCard>()
        fun absorb(cards: List<TrCard>) {
            for (card in cards) {
                val key = TrAnimeIzleMatch.seriesKey(card.url)
                if (key.isEmpty()) continue
                val existing = collected[key]
                if (existing == null) collected[key] = card
                else if ((existing.episodeHint ?: 0) < (card.episodeHint ?: 0)) {
                    collected[key] = existing.copy(
                        episodeHint = card.episodeHint,
                        seasonHint = card.seasonHint ?: existing.seasonHint
                    )
                }
            }
        }

        for (variant in variants) {
            if (page > 1) break
            if (collected.size >= 8) break
            val enc = encodeSearchPath(variant)
            val url = mainUrl + "/arama/" + enc
            val html = try {
                fetchHtml(url, mainUrl + "/", ensureSession = false, webViewFallback = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(LOG_TAG, "search('$variant') isteği başarısız: ${e.message}")
                null
            }
            if (html.isNullOrBlank()) continue
            val document = Jsoup.parse(html)
            absorb(TrAnimeIzleSite.parseCards(document, mainUrl + "/", 40))
            Log.d(LOG_TAG, "search('$variant') → toplam ${collected.size} kart")
        }
        if (page > 1) {
            // Sayfa > 1: tek istek (ham sorgu) — sonsuz liste için hasNext korunur.
            val enc = encodeSearchPath(query)
            val html = try {
                fetchHtml(mainUrl + "/arama/" + enc + "?page=" + page, mainUrl + "/", true, true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
            if (!html.isNullOrBlank()) absorb(TrAnimeIzleSite.parseCards(Jsoup.parse(html), mainUrl + "/", 40))
        }

        // v30: İngilizce başlık ↔ sitenin romanize adı.
        // Kullanıcı/uygulama "Attack on Titan" arar, site "Shingeki no Kyojin"
        // altında listeler. Kitsu'nun alternatif adları bu köprüyü kurar.
        val synonyms = if (page <= 1) titlesFromKitsu(query) else emptyList()
        if (collected.size < 3 && synonyms.isNotEmpty()) {
            for (alt in synonyms.take(4)) {
                if (collected.size >= 8) break
                if (variants.any { it.equals(alt, ignoreCase = true) }) continue
                val html = try {
                    fetchHtml(
                        mainUrl + "/arama/" + encodeSearchPath(alt),
                        mainUrl + "/",
                        ensureSession = false,
                        webViewFallback = false
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.d(LOG_TAG, "eş anlamlı arama başarısız ('$alt'): ${e.message}")
                    null
                }
                if (html.isNullOrBlank()) continue
                absorb(TrAnimeIzleSite.parseCards(Jsoup.parse(html), mainUrl + "/", 40))
            }
            Log.d(LOG_TAG, "eş anlamlı arama sonrası ${collected.size} kart (${synonyms.size} ad)")
        }

        // Site araması boş/az → katalog üzerinden yerel eşleştirme.
        if (collected.size < 3) {
            val catalogue = loadCatalogue()
            if (catalogue.isNotEmpty()) {
                val rankedLocal = TrAnimeIzleMatch.rank(
                    query,
                    catalogue.map { Triple(it.name, it.url, emptyList()) },
                    threshold = 0.45
                )
                val byUrl = catalogue.associateBy { it.url }
                for ((url, _) in rankedLocal) {
                    val card = byUrl[url] ?: continue
                    val key = TrAnimeIzleMatch.seriesKey(card.url)
                    if (key.isNotEmpty() && !collected.containsKey(key)) collected[key] = card
                }
                Log.d(LOG_TAG, "search('$query') katalog yedeği: ${collected.size} kart")
            }
        }

        // Sıralama: sorgu + alternatif adların EN İYİ skoru (böylece
        // "Attack on Titan" sorgusu "Shingeki no Kyojin" kartını üste alır).
        val queries = (listOf(query) + synonyms).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val scored = collected.values.map { card ->
            val best = queries.maxOfOrNull { TrAnimeIzleMatch.score(it, card.name) } ?: 0.0
            card to best
        }.sortedByDescending { it.second }
        val results = scored.filter { it.second >= 0.35 }
            .map { it.first.toSearchResponse() }
            .ifEmpty { scored.take(12).map { it.first.toSearchResponse() } }
            .ifEmpty { collected.values.take(24).map { it.toSearchResponse() } }
        Log.d(LOG_TAG, "search('$query') → ${results.size} sonuç")
        return newSearchResponseList(results, true)
    }

    private fun TrCard.toSearchResponse(): AnimeSearchResponse {
        val card = this
        return newAnimeSearchResponse(card.name, card.url, TvType.Anime) {
            this.posterUrl = card.poster
            this.score = Score.from10(card.score)
        }
    }

    /** Kitsu ad önbelleği (sorgu → alternatif adlar). */
    private val kitsuTitleCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /**
     * Kitsu'dan **alternatif adlar**: canonicalTitle + `titles{}` (en/en_jp/ja_jp…)
     * + `abbreviatedTitles`. Türkçe siteler romanize/İngilizce adlarla listelediği
     * için arama bu adlarla genişletilir ("Attack on Titan" → "Shingeki no Kyojin").
     * Tek istek, 8 sn zaman aşımı, sonuç önbellekli.
     */
    private suspend fun titlesFromKitsu(query: String): List<String> {
        val key = query.lowercase().trim()
        if (key.length < 2) return emptyList()
        kitsuTitleCache[key]?.let { return it }
        val out = LinkedHashSet<String>()
        try {
            val url = "https://kitsu.io/api/edge/anime?filter[text]=" +
                URLEncoder.encode(key, "UTF-8") + "&page[limit]=3"
            val data = JSONObject(app.get(url, timeout = 8).text).optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val attrs = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                    attrs.optString("canonicalTitle").takeIf { it.isNotBlank() }?.let(out::add)
                    attrs.optJSONObject("titles")?.let { titles ->
                        for (lang in titles.keys()) {
                            titles.optString(lang).takeIf { it.isNotBlank() }?.let(out::add)
                        }
                    }
                    attrs.optJSONArray("abbreviatedTitles")?.let { arr ->
                        for (j in 0 until arr.length()) {
                            arr.optString(j).takeIf { it.isNotBlank() }?.let(out::add)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.d(LOG_TAG, "Kitsu adları alınamadı ('$query'): ${e.message}")
        }
        val list = out.map { it.trim() }
            .filter { it.length in 2..120 }
            .distinct()
            .take(8)
        kitsuTitleCache[key] = list
        return list
    }

    /**
     * Katalog: site araması sonuç vermediğinde isim eşleştirmesi için popüler +
     * yeni bölüm + yeni animeler listeleri (30 dakika önbellekli).
     */
    private suspend fun loadCatalogue(): List<TrCard> {
        val cached = catalogueCache
        if (cached != null && System.currentTimeMillis() - cached.first < 30 * 60 * 1000L) {
            return cached.second
        }
        val out = LinkedHashMap<String, TrCard>()
        val paths = listOf(
            "/listeler/populer/sayfa-",
            "/listeler/yenibolum/sayfa-",
            "/listeler/eklenen/sayfa-"
        )
        for (path in paths) {
            for (n in 1..2) {
                val html = try {
                    fetchHtml(mainUrl + path + n, mainUrl + "/", ensureSession = false, webViewFallback = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(LOG_TAG, "katalog ${path}${n} alınamadı: ${e.message}")
                    null
                }
                if (html.isNullOrBlank()) continue
                for (card in TrAnimeIzleSite.parseCards(Jsoup.parse(html), mainUrl + "/", 60)) {
                    val key = TrAnimeIzleMatch.seriesKey(card.url)
                    if (key.isNotEmpty() && !out.containsKey(key)) out[key] = card
                }
                if (out.size >= 240) break
            }
            if (out.size >= 240) break
        }
        val list = out.values.toList()
        if (list.isNotEmpty()) catalogueCache = System.currentTimeMillis() to list
        Log.d(LOG_TAG, "katalog: ${list.size} anime")
        return list
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            if (!TrAnimeIzleHelper.isAllowedVersion) {
                return newHomePageResponse(request.name, emptyList(), false)
            }
            // v26: canlı domain garantisi + mainPage URL'ini mevcut domain'e haritala.
            ensureDomainAlive()
            val url = remapDomain(request.data) + page
            val html = fetchHtml(url, mainUrl + "/", ensureSession = false, webViewFallback = true)
            val document = Jsoup.parse(html)
            var results = TrAnimeIzleSite.parseCards(document, mainUrl + "/", 60)
                .map { it.toSearchResponse() }
            if (results.isEmpty()) {
                results = parseGenericCards(document)
                Log.w(LOG_TAG, "mainPage('${request.name}'): kart 0 → genel çözümleme: ${results.size}")
            }
            Log.d(LOG_TAG, "mainPage('${request.name}') → ${results.size} sonuç")
            newHomePageResponse(request.name, results, true)
        } catch (e: CancellationException) {
            // v27: iptal istisnası YUTULMAZ — yutulursa scope sonucun
            // dönmesini bekleyip takılır (kullanıcıda "çökme" izlenimi).
            throw e
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "getMainPage('${request.name}') hatası: ${e::class.simpleName}: ${e.message}")
            throw ErrorLoadingException(
                "Siteye ulaşılamıyor! Tarayıcıdan test edin. (${e::class.simpleName})"
            )
        }
    }

    private fun toMainPageResult(el: Element): AnimeSearchResponse? {
        val title = el.selectFirst("h4")?.text()?.replace("İzle", "")?.takeIf { it.isNotBlank() }
            ?: return null
        // v17 (site.js kanıtı, 22.09.2026): .flx-block kartları tıklamada
        // `data-href` özniteliğine yönlendiriyor — <a href> ile aynı olmak zorunda
        // değil; önce data-href, sonra <a href> denenir.
        val url = fixUrlNull(el.attr("data-href"))
            ?: fixUrlNull(el.selectFirst("a")?.attr("href")) ?: return null
        val poster = fixUrlNull(el.selectFirst("img")?.attr("src"))
        val rating = el.selectFirst("rating")?.attr("data-src")
        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.score = Score.from10(rating)
        }
    }

    private fun toRecommendationResult(el: Element): AnimeSearchResponse? {
        val title = el.selectFirst("a img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: return null
        val url = fixUrlNull(el.selectFirst("a")?.attr("href")) ?: return null
        val poster = fixUrlNull(el.selectFirst("a img")?.attr("data-src"))
        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    /**
     * v18 yedek kart çözümleme: sonuç/liste kartlarının sınıfları değişse bile,
     * site'nin URL desenine (…-bolum-izle / …-anime-izle) + kart içindeki
     * resme dayanarak sonuç üretir. (Ana sayfa arşivi 22.09.2026 bu deseni
     * doğruluyor: her kartta img + izle-sonekli href var.)
     */
    private fun parseGenericCards(document: Document): List<AnimeSearchResponse> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<AnimeSearchResponse>()
        for (a in document.select("a[href]")) {
            val href = a.attr("href")
            if (!href.contains("bolum-izle", ignoreCase = true) &&
                !href.contains("anime-izle", ignoreCase = true)
            ) continue
            val url = fixUrlNull(href) ?: continue
            if (!seen.add(url)) continue
            val img = a.selectFirst("img") ?: continue
            val poster = fixUrlNull(img.attr("src"))?.ifBlank { null }
                ?: fixUrlNull(img.attr("data-src"))
            val title = (a.selectFirst("h4,h3,h5")?.text() ?: img.attr("alt"))
                ?.replace("İzle", "")?.trim()?.takeIf { it.isNotBlank() } ?: continue
            out += newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // v17: oturum + tarayıcı başlıkları + bot cevabı işleme
    // ------------------------------------------------------------------

    /** Son bilinen oturum çerez seti (fetchHtml tarafından güncellenir). */
    private var lastCookie: String = ""

    /**
     * Tarayıcı benzeri başlıklar. CloudStream'in varsayılan UA'sı bot imzası
     * olduğu için tüm site isteklerinde Firefox UA + Referer + Accept-Language
     * ile gidiyoruz (BotKontrol'un WebView'inde kullandığı ile aynı UA).
     */
    private fun browserHeaders(
        cookie: String,
        referer: String = mainUrl + "/",
        isXhr: Boolean = false
    ): Map<String, String> = buildMap {
        put("User-Agent", BotKontrolKt.UA_FIREFOX)
        put(
            "Accept",
            if (isXhr) "application/json, text/plain, */*"
            else "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        )
        put("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
        put("Referer", referer)
        if (isXhr) {
            // v20: Firefox'un XHR imzası (site kendi axios isteklerini böyle yapar).
            put("sec-fetch-dest", "empty")
            put("sec-fetch-mode", "cors")
            put("sec-fetch-site", "same-origin")
            put("X-Requested-With", "XMLHttpRequest")
        } else {
            // v20: Firefox'un document navigasyon imzası — WAF'ların
            // "gerçek tarayıcı mı?" testinde baktığı başlıklar.
            put("sec-fetch-dest", "document")
            put("sec-fetch-mode", "navigate")
            put("sec-fetch-site", "same-origin")
            put("sec-fetch-user", "?1")
            put("priority", "u=0,i")
            put("Upgrade-Insecure-Requests", "1")
        }
        if (cookie.isNotEmpty()) put("Cookie", cookie)
    }

    // ------------------------------------------------------------------
    // v30: TrStreamHttp — akış zincirinin kullandığı hızlı HTTP yüzeyi
    // ------------------------------------------------------------------
    //
    // Not: v17–v29'daki WebView/"kanıt katmanı" burada YOK. Sitelerin oynatıcı
    // uçları (/api/fansubSources, /api/sourcePlayer) düz HTTP ile çalışıyor;
    // WebView her turda 5+ saniye bekletiyor ve çoğu zaman boş DOM döndürüyordu.

    private suspend fun <T> guarded(tag: String, block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.d(LOG_TAG, "$tag → ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /** Yanıt çerezlerini (ör. Cloudflare/oturum) jar'a ekler. */
    // v42: `mergeCookies(resp.cookies)` KALDIRILDI — `NiceResponse.cookies`
    // NiceHttp sürümüne bağlı bir API; uygulamadaki 0.4.4'te yoksa
    // `NoSuchMethodError` üretiyordu. Oturum çerezlerini NiceHttp'in kendi
    // `app` istemcisi (cookie jar) yönetiyor; bizim ek bir şey yapmamız gerekmiyor.

    // A single HTTP session must serve the page, captcha images and XHRs.
    // NiceHttp's default Requests client does not necessarily retain cookies;
    // its stock Session jar also ignores cookie domains. Respect domain/path/expiry.
    private val siteCookies = object : CookieJar {
        private val entries = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (cookie in cookies) {
                entries.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (cookie.expiresAt > System.currentTimeMillis()) entries.add(cookie)
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            entries.removeAll { it.expiresAt <= System.currentTimeMillis() }
            return entries.filter { it.matches(url) }
        }
    }
    private val siteHttp by lazy {
        Session(app.baseClient).apply {
            baseClient = baseClient.newBuilder().cookieJar(siteCookies).build()
        }
    }

    /** Captcha çözümü sıraya alınır: 10 paralel kaynak isteği aynı anda
     *  çözmeye kalkarsa siteye 10 ayrı doğrulama isteği gider (hem yavaş
     *  hem de WAF'ı kızdırır). */
    private val captchaSolveLock = Mutex()

    // A challenge is evidence that the previous clearance is no longer sufficient.
    // Do not suppress a new challenge with a time-based cache.
    private suspend fun ensureCaptchaSolved(challengeUrl: String? = null): Boolean =
        captchaSolveLock.withLock { solveCaptchaAt(challengeUrl) }

    private suspend fun captchaRetry(body: String?, url: String? = null): Boolean =
        (isCaptchaUrl(url) || isCaptchaPage(body)) &&
            ensureCaptchaSolved(url?.takeIf { isCaptchaUrl(it) })

    override suspend fun get(url: String, referer: String?): String? =
        guarded("GET ${url.take(110)}") {
            var resp = siteHttp.get(
                url,
                headers = browserHeaders(lastCookie, referer ?: mainUrl + "/"),
                timeout = 12
            )
            // v40: gövde TEK kez okunur (nicehttp gövdesi tüketilebilir; ikinci
            // okuma null dönüp sayfayı sessizce boşaltıyordu).
            var text = resp.text
            if (captchaRetry(text, resp.url.toString())) {
                Log.d(LOG_TAG, "captcha çözüldü → GET tekrar: ${url.take(90)}")
                resp = siteHttp.get(
                    url,
                    headers = browserHeaders(lastCookie, referer ?: mainUrl + "/"),
                    timeout = 12
                )
                text = resp.text
            }
            if (!resp.isSuccessful || isCaptchaUrl(resp.url.toString()) || isCaptchaPage(text)) null else text
        }

    override suspend fun getXhr(url: String, referer: String?): String? =
        guarded("XHR ${url.take(110)}") {
            var resp = siteHttp.get(
                url,
                headers = browserHeaders(lastCookie, referer ?: mainUrl + "/", isXhr = true),
                timeout = 12
            )
            var text = resp.text
            if (captchaRetry(text, resp.url.toString())) {
                resp = siteHttp.get(
                    url,
                    headers = browserHeaders(lastCookie, referer ?: mainUrl + "/", isXhr = true),
                    timeout = 12
                )
                text = resp.text
            }
            if (!resp.isSuccessful || isCaptchaUrl(resp.url.toString()) || isCaptchaPage(text)) null else text
        }

    override suspend fun getWith(url: String, extra: Map<String, String>): String? =
        guarded("GET(w) ${url.take(110)}") {
            val base = url.substringBefore("/api/").ifEmpty { mainUrl }
            val resp = siteHttp.get(
                url,
                headers = browserHeaders(lastCookie, "$base/") + extra,
                timeout = 12
            )
            if (!resp.isSuccessful) null else resp.text
        }

    override suspend fun postJson(url: String, json: String, referer: String?): String? =
        guarded("POST-JSON ${url.take(110)}") {
            // site.js (axios) birebir JSON gövdesi gönderir; form gövde kaynak
            // listesini boş döndürüyordu (v23'te kanıtlandı).
            var resp = siteHttp.post(
                url,
                headers = browserHeaders(lastCookie, referer ?: mainUrl + "/", isXhr = true),
                json = JsonAsString(json),
                timeout = 15
            )
            var text = resp.text
            if (captchaRetry(text, resp.url.toString())) {
                Log.d(LOG_TAG, "captcha çözüldü → POST-JSON tekrar: ${url.take(90)}")
                resp = siteHttp.post(
                    url,
                    headers = browserHeaders(lastCookie, referer ?: mainUrl + "/", isXhr = true),
                    json = JsonAsString(json),
                    timeout = 15
                )
                text = resp.text
            }
            if (!resp.isSuccessful || isCaptchaUrl(resp.url.toString()) || isCaptchaPage(text)) null else text
        }

    override suspend fun postEmpty(url: String, referer: String?): String? =
        guarded("POST ${url.take(110)}") {
            var resp = siteHttp.post(
                url,
                headers = browserHeaders(lastCookie, referer ?: mainUrl + "/", isXhr = true),
                timeout = 15
            )
            var text = resp.text
            if (captchaRetry(text, resp.url.toString())) {
                Log.d(LOG_TAG, "captcha çözüldü → POST tekrar: ${url.take(90)}")
                resp = siteHttp.post(
                    url,
                    headers = browserHeaders(lastCookie, referer ?: mainUrl + "/", isXhr = true),
                    timeout = 15
                )
                text = resp.text
            }
            if (!resp.isSuccessful || isCaptchaUrl(resp.url.toString()) || isCaptchaPage(text)) null else text
        }

    override suspend fun postForm(
        url: String,
        form: Map<String, String>,
        referer: String?
    ): String? = guarded("POST-FORM ${url.take(110)}") {
        var resp = siteHttp.post(
            url,
            headers = browserHeaders(lastCookie, referer ?: mainUrl + "/", isXhr = true),
            data = form,
            timeout = 12
        )
        var text = resp.text
        if (captchaRetry(text, resp.url.toString())) {
            resp = siteHttp.post(
                url,
                headers = browserHeaders(lastCookie, referer ?: mainUrl + "/", isXhr = true),
                data = form,
                timeout = 12
            )
            text = resp.text
        }
        if (!resp.isSuccessful || isCaptchaUrl(resp.url.toString()) || isCaptchaPage(text)) null else text
    }

    /** Binary responses must use the same cookie jar as captcha POSTs. */
    override suspend fun bytes(url: String, referer: String?): ByteArray? =
        guarded("BYTES ${url.take(110)}") {
            val response = siteHttp.get(
                url,
                headers = browserHeaders(lastCookie, referer ?: mainUrl + "/"),
                timeout = 12
            )
            if (!response.isSuccessful) {
                response.body.close()
                null
            } else {
                response.body.use { it.bytes() }
            }
        }

    /**
     * `/api/Captcha` doğrulaması: sunucu bir grup görsel kimliği verir; aralarından
     * **en farklı** olan seçilmelidir. Aynı yöntemi Aniyomi istemcisi
     * (`ShittyCaptchaInterceptor`) kullanıyor: her görselin MD5'i alınır, hash'i
     * en seyrek görünen görsel "farklı" olandır.
     */
    override suspend fun solveCaptcha(): Boolean = ensureCaptchaSolved()

    private suspend fun solveCaptchaAt(challengeUrl: String?): Boolean {
        val solved = guarded("CAPTCHA") {
            val site = mainUrl.trimEnd('/')
            val xhr = browserHeaders(lastCookie, challengeUrl ?: "$site/", isXhr = true)
            val listBody = siteHttp.post(
                "$site/api/Captcha/",
                headers = xhr,
                data = mapOf("cID" to "0", "rT" to "1", "tM" to "light"),
                timeout = 12
            ).text

            val ids = try {
                val array = JSONArray(listBody)
                (0 until array.length()).map { array.getString(it) }.filter { it.isNotBlank() }
            } catch (_: Exception) {
                return@guarded false
            }
            if (ids.size < 3) return@guarded false

            val hashes = ids.mapNotNull { id ->
                val data = bytes("$site/api/Captcha/?cid=0&hash=$id", "$site/") ?: return@mapNotNull null
                if (data.size < 64) return@mapNotNull null
                id to md5Hex(data)
            }
            // A missing image or an ambiguous tie cannot identify the odd one out.
            if (hashes.size != ids.size) return@guarded false
            val counts = hashes.groupingBy { it.second }.eachCount()
            val rarest = counts.entries.singleOrNull { it.value == 1 }?.key
                ?: return@guarded false
            val chosen = hashes.firstOrNull { it.second == rarest }?.first ?: return@guarded false

            val answer = siteHttp.post(
                "$site/api/Captcha/",
                headers = xhr,
                data = mapOf("cID" to "0", "rT" to "2", "pC" to chosen),
                timeout = 12
            )
            if (!answer.isSuccessful) return@guarded false
            // The challenge page redirects via JavaScript after success. A second
            // GET of that page is not a confirmation; retry the original request.
            Log.d(LOG_TAG, "captcha yanıtı gönderildi (${hashes.size} görsel)")
            true
        }
        return solved == true
    }

    private fun md5Hex(data: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

    /**
     * v41: oturum çerezi = bu oturumda toplanan çerez seti ([lastCookie]).
     *
     * Eskiden CookieManager (Android WebView) üzerinden çerez okunuyordu; bu da
     * eklentiyi `android.webkit`'e bağlıyor ve çalışma zamanı uyumsuzluğuna yol
     * açıyordu. Site zaten anonim çalışıyor: çerezler yalnızca captcha/oturum
     * devamlılığı için; HTTP yanıtlarından toplananlar yeterli.
     */
    private fun cachedSession(): String = lastCookie

    // ------------------------------------------------------------------
    // v20: oturum doğrulama (epoch) + WebView DOM çekme katmanı
    // ------------------------------------------------------------------

    /**
     * Sayfadaki içerik kart linki sayısı (…-bolum-izle / …-anime-izle).
     * Ana sayfa/liste/arama sayfasında oturum GEÇERLİYSE bu sayfa > 0'dır
     * (ana sayfa arşivi 22.09.2026: kartlar garantili).
     */
    private fun izleCount(html: String?): Int {
        if (html.isNullOrBlank()) return 0
        return try {
            val document = Jsoup.parse(html)
            // 1) Klasik kart yapısı: kartlar tıklamada data-href kullanıyor.
            val blocks = document.select("div.flx-block, .flx-block").size
            if (blocks > 0) return blocks
            // 2) Doğrudan bağlantılı kartlar.
            val links = document.select("a[href*='bolum-izle'], a[href*='anime-izle']").size
            if (links > 0) return links
            // 3) data-href taşıyan öğeler.
            document.select("[data-href]").count { element ->
                val href = element.attr("data-href")
                href.contains("bolum-izle") || href.contains("anime-izle") ||
                    href.endsWith("-izle") || href.endsWith("-izle-hd")
            }
        } catch (_: Exception) {
            0
        }
    }

    // ------------------------------------------------------------------
    // v26: DOMAIN ROTASYONU (site domain'i sık değişiyor — BTK engelleri)
    // ------------------------------------------------------------------

    /**
     * v26: bu domain YAŞIYOR mu? = ana sayfa GET 200 + bot değil + içerik
     * kart linki taşıyor. 8 sn zaman aşımı — ölü domain zinciri boğmaz.
     */
    private suspend fun probeDomainAlive(base: String): Boolean {
        return try {
            val resp = siteHttp.get(base, headers = browserHeaders(cachedSession(), base + "/"), timeout = 8)
            val text = resp.text ?: ""
            // v28 (canlı ölçüm 25.10.2026): site veri merkezi IP'lerine Cloudflare
            // **Error 1005** (ASN engeli) dönüyor. Bu, domainin ÖLDÜĞÜ anlamına
            // GELMEZ — cihazın kendi bağlantısıyla (WebView oturumu) çalışır.
            // Eskiden 403 "ölü domain" sayılıp gereksiz yere domain değiştiriliyordu.
            val cfBlocked = resp.code == 403 || resp.code == 503 ||
                text.contains("Error 1005", ignoreCase = true) ||
                text.contains("error 1005", ignoreCase = true) ||
                isBotBody(text)
            if (cfBlocked) {
                Log.w(
                    LOG_TAG,
                    "domain probe: $base → Cloudflare/IP engeli (${resp.code}); domain KORUNUYOR " +
                        "(oturum katmanı çözer)"
                )
                return true
            }
            val ok = resp.isSuccessful && izleCount(text) > 0
            Log.d(LOG_TAG, "domain probe: $base → ${if (ok) "ALIVE" else "DEAD"} (status=${resp.code})")
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "domain probe istisnası: $base → ${e.message}")
            false
        }
    }

    /**
     * v26: mevcut domain ölü mü? → adayları sırayla dene; canlı olanı
     * benimsa (mainUrl + prefs'e kalıcı yaz + oturumu GEÇERSİZ kıl —
     * çerezler domain bazlıdır). Mevcut (değişmiş olabilecek) domaini döndürür.
     */
    private suspend fun ensureDomainAlive(force: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!force && now - lastDomainCheckAt < DOMAIN_CHECK_TTL_MS) return mainUrl
        lastDomainCheckAt = now
        if (probeDomainAlive(mainUrl)) return mainUrl
        Log.w(LOG_TAG, "MEVCUT DOMAIN ÖLÜ ($mainUrl) → adaylar deneniyor...")
        for (c in DOMAIN_CANDIDATES) {
            if (c == mainUrl) continue
            if (probeDomainAlive(c)) {
                Log.w(LOG_TAG, "DOMAIN DEĞİŞTİRİLDİ: $mainUrl → $c")
                mainUrl = c
                // v41: SharedPreferences yazımı kaldırıldı (Android çerçeve bağımlılığı).
                // Domain yalnızca bu oturum için değişir; sonraki açılışta
                // DEFAULT_DOMAIN'den başlanır ve canlı doğrulama yine çalışır.
                // yeni domain → yeni oturum (eski çerezler eski domainin)
                sessionValidated = false
                lastCookie = ""
                return c
            }
        }
        Log.e(LOG_TAG, "aday domainlerden canlısı yok — $mainUrl ile devam (1 dk sonra tekrar dene)")
        lastDomainCheckAt = now - DOMAIN_CHECK_TTL_MS + 60_000
        return mainUrl
    }

    /**
     * v26: kurulumda eski domainle üretilmiş bir URL'in önünü mevcut
     * domainle değiştirir (mainPage listesi, bookmark'dan gelen URL).
     */
    private fun remapDomain(url: String): String {
        if (url.startsWith(mainPageBase)) return mainUrl + url.substring(mainPageBase.length)
        // v32: kayıtlı URL eski domainde (.io/.top) kalmış olabilir — host'u
        // bilinen aday listesiyle eşleştirip mevcut domaine taşı, YOL KORUNUR.
        // (Domain 301 ile taşındığı için eski adresler yavaş/çerez kaybıyla
        // açılıyordu; arama ve kaynak uçları bu yüzden boş dönüyordu.)
        val knownHosts = DOMAIN_CANDIDATES.mapNotNull { candidate ->
            runCatching { java.net.URI(candidate).host?.lowercase() }.getOrNull()
        }.toSet()
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return url
        if (host !in knownHosts) return url
        val tail = url.substringAfter("://").substringAfter("/", "")
        return if (tail.isEmpty()) "$mainUrl/" else "$mainUrl/$tail"
    }

    private var sessionValidated = false

    /**
     * v20 oturum doğrulaması (epoch başında BİR kez):
     *  1) HIZLI PROB: ana sayfa GET (kayıtlı çerezle). Kart linki varsa
     *     oturum GEÇERLİ → diyalog yok, anında dön.
     *     (v19'un hatası: kayıtlı çerez GEÇERLİLİĞİNE bakılmadan kullanıldı;
     *     site eski oturuma sessizce boş sayfa döndürüyordu → 0 sonuç +
     *     diyalog hiç açılmadı.)
     *  2) Prob boş/bot → ESKİ ÇEREZLER TEMİZLENİR + ana sayfada taze WebView
     *     diyaloğu (challenge varsa otomatik/elle çözülür). Ana sayfanın
     *     içeriği garanti olduğundan, içerik gelmesi = oturumun tarayıcı
     *     kanıtıyla geçerli olduğu demektir.
     *  3) Diyaloğta da içerik yoksa → elimizdeki en iyi setle devam (log).
     */
    private suspend fun ensureValidSession(): String {
        if (sessionValidated) return lastCookie
        sessionValidated = true
        var cookie = lastCookie.ifBlank { cachedSession() }
        var probe = try {
            // v26: 10 sn zaman aşımı — ölü domain probu zinciri boğmasın.
            siteHttp.get(mainUrl, headers = browserHeaders(cookie, mainUrl + "/"), timeout = 10)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Ana sayfa prob hatası: ${e.message}")
            null
        }
        if (probe == null) {
            // Ağ gecikmesi olmasın diye: prob hatasında bir kez daha dene.
            delay(1_500)
            probe = try {
                siteHttp.get(mainUrl, headers = browserHeaders(cookie, mainUrl + "/"), timeout = 10)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Ana sayfa prob 2. deneme hatası: ${e.message}")
                null
            }
        }
        val probeText = probe?.text ?: ""
        val probeLinks = izleCount(probeText)
        if (probe != null && probe.isSuccessful && probeLinks > 0) {
            Log.d(LOG_TAG, "Oturum GEÇERLİ (ana sayfa prob: $probeLinks kart linki) — diyalog yok")
            lastCookie = cookie
            return cookie
        }
        Log.w(
            LOG_TAG,
            "Ana sayfa prob GEÇERSİZ (isSuccess=${probe?.isSuccessful ?: "?"}, $probeLinks kart linki, " +
                "botCevabı=${isBotBody(probeText)}) → taze oturum diyaloğu (eski çerezler temizleniyor)"
        )
        // v41: WebView diyaloğu YOK. Taze oturum = çerezleri temizleyip ana
        // sayfayı yeniden çekmek (site anonim oturumda da içerik veriyor).
        lastCookie = ""
        val fresh = try {
            val retry = siteHttp.get(
                mainUrl,
                headers = browserHeaders("", mainUrl + "/"),
                timeout = 12
            )
            retry.text ?: ""
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Taze oturum isteği hatası: ${e.message}")
            ""
        }
        val freshLinks = izleCount(fresh)
        Log.d(LOG_TAG, "Taze oturum denemesi: $freshLinks kart linki, set ${lastCookie.length} karakter")
        return lastCookie
    }

    /**
     * v41: oturumu HTTP ile yeniler (WebView diyaloğu kaldırıldı).
     * Çerezleri temizleyip ana sayfayı bir kez daha çeker; yeni çerez seti
     * [lastCookie]'ye yazılır.
     */
    private suspend fun refreshSession(@Suppress("UNUSED_PARAMETER") url: String): String {
        sessionValidated = false
        lastCookie = ""
        return try {
            ensureValidSession()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Oturum yenileme hatası: ${e.message}")
            ""
        }
    }


    /**
     * Yanıt, sitenin **ikon-captcha** kapısına mı düştü? (v39)
     *
     * Site istemciyi `/api/CaptchaChallenge` sayfasına yönlendiriyor; bu sayfada
     * `div.captcha-holder` ve `iconCaptcha` betiği bulunur (MyTV ve Aniyomi
     * istemcilerinin tespit ölçütüyle birebir aynı).
     *
     * Eski sürüm gövdede geçen **herhangi bir** `/api/Captcha` metnini "bot"
     * sayıyordu. Sitenin kendi JS paketi bu uçtan söz ettiği için NORMAL
     * sayfalar da bot sanılıp gereksiz WebView diyaloğu açılıyor, bazı
     * durumlarda içerik hiç gelmiyordu. Artık yalnızca gerçek kapı işaretleri
     * aranıyor ve kapı HTTP ile sessizce çözülüyor.
     */
    private fun isCaptchaPage(text: String?): Boolean {
        val body = text ?: return false
        if (body.isEmpty() || body.length > 400_000) return false
        return body.contains("captcha-holder", ignoreCase = true) ||
            body.contains("iconCaptcha", ignoreCase = true) ||
            body.contains("CaptchaChallenge", ignoreCase = true)
    }

    /**
     * v40: captcha kapısı **yanıt adresinden** de anlaşılır.
     *
     * Kanıt (Aniyomi, `ShittyCaptchaInterceptor.kt`): sunucu korumalı isteği
     * `…/api/CaptchaChallenge` adresine yönlendiriyor; gövde bazen boş/kısa JSON
     * oluyor (`{"status":false}`), bu yüzden yalnızca gövdeye bakan kontrol
     * kapıyı **kaçırıyor** ve boş içerik sessizce yutuluyordu.
     */
    private fun isCaptchaUrl(url: String?): Boolean =
        url != null && url.contains("/api/CaptchaChallenge", ignoreCase = true)

    /**
     * İçerik bot/captcha/Cloudflare challenge sayfası mı?
     * v18: site Cloudflare arkasında (Error 1005 ASN ban kanıtlı) → CF
     * managed challenge başlıkları da bot cevabı sayılır. (Sadece TITLE
     * bazlı kontrol — gövdedeki cf- işaretleri her CF korumalı sayfada
     * normalde de bulunur, yanlış pozitif vermemek için.)
     * v39: captcha kapısı artık GENEL metinle değil, [isCaptchaPage] ile
     * tespit edilir (yanlış pozitif giderildi).
     */
    private fun isBotBody(text: String?): Boolean {
        val t = text ?: ""
        if (t.isEmpty()) return true
        if (isCaptchaPage(t)) return true
        val title = try {
            Jsoup.parse(t).selectFirst("title")?.text() ?: ""
        } catch (_: Exception) {
            ""
        }
        return title.contains("Bot", ignoreCase = true) ||
            title.contains("Just a moment", ignoreCase = true) ||
            title.contains("Checking your browser", ignoreCase = true) ||
            title.contains("Attention Required", ignoreCase = true) ||
            Regex("Error\\s?100[5678]|Error\\s?1015|Error\\s?1020|Access denied")
                .containsMatchIn(title)
    }

    /**
     * v17 çekirdek GET: önce kayıtlı oturumla dener; başarısız/bot/captcha
     * cevabı gelirse WebView diyaloğu ile oturumu yenileyip TEK bir kez
     * tekrar dener. Her adımda log bırakır (cihaz kanıtı için).
     */
    private suspend fun fetchHtml(
        rawUrl: String,
        rawReferer: String,
        ensureSession: Boolean = false,
        webViewFallback: Boolean = false
    ): String {
        var url = rawUrl
        var referer = rawReferer
        // v20: ensureSession=true → epoch oturum doğrulaması (geçerlilik kanıtıyla).
        var cookie = lastCookie.ifBlank {
            if (ensureSession) ensureValidSession() else cachedSession()
        }
        var resp = try {
            // v27: zaman aşımı yoksa ölü host zinciri BOĞAR (v24 "bir anda
            // hata" bulgusu) — sayfa çekimleri 15 sn ile sınırlı.
            siteHttp.get(url, headers = browserHeaders(cookie, referer), timeout = 15)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // v26: ağ seviyesi hata (bağlantı/timeout) — domain değişmiş
            // olabilir: aday domainler denenir, canlısına geçilip BİR kez
            // tekrarlanmazsa hata yukarı fırlatılır.
            Log.w(LOG_TAG, "GET ağ hatası: $url → ${e.message}")
            val before = mainUrl
            ensureDomainAlive(force = true)
            if (mainUrl != before) {
                Log.w(LOG_TAG, "ağ hatası → domain değişti ($before → $mainUrl), tekrar deniyor")
                url = remapDomain(url)
                referer = remapDomain(referer)
                cookie = cachedSession()
                try {
                    siteHttp.get(url, headers = browserHeaders(cookie, referer), timeout = 15)
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: Exception) {
                    Log.e(LOG_TAG, "GET tekrar denemesi de başarısız: ${e2.message}")
                    throw e
                }
            } else {
                throw e
            }
        }
        // v39: sitenin ikon-captcha kapısı artık WebView diyaloğu AÇMADAN,
        // HTTP ile (5 görselin MD5'i → en farklı olan) çözülür ve sayfa BİR kez
        // tekrar istenir. Çözülemezse aşağıdaki bot/oturum yoluna düşer.
        if (captchaRetry(resp.text, resp.url.toString())) {
            Log.d(LOG_TAG, "captcha çözüldü → sayfa tekrar: $url")
            resp = try {
                siteHttp.get(url, headers = browserHeaders(lastCookie, referer), timeout = 15)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "captcha sonrası GET hatası: ${e.message}")
                resp
            }
        }

        if (!resp.isSuccessful || isBotBody(resp.text)) {
            // v26: 403/bot sayfası da ölü domain belirtisi olabilir → önce
            // domain rotasyonu, sonra oturum yenileme.
            val before = mainUrl
            ensureDomainAlive(force = true)
            if (mainUrl != before) {
                Log.w(LOG_TAG, "bot/403 → domain değişti ($before → $mainUrl), tekrar deniyor: $url")
                url = remapDomain(url)
                referer = remapDomain(referer)
                cookie = cachedSession()
                resp = try {
                    siteHttp.get(url, headers = browserHeaders(cookie, referer), timeout = 15)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "domain sonrası GET hatası: ${e.message}")
                    throw IOException("GET başarısız: $url (${e.message})")
                }
            }
            if (!resp.isSuccessful || isBotBody(resp.text)) {
                Log.e(LOG_TAG, "Bot/başarısız cevap → oturum yenileniyor: $url")
                cookie = refreshSession(url)
                // v27: eski sürümde bu GET zamansızdı ve hata loglanmadan
                // yukarı çıkıyordu → kullanıcı "sebepsiz çökme" görüyordu.
                resp = try {
                    siteHttp.get(url, headers = browserHeaders(cookie, referer), timeout = 15)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Oturum sonrası GET hatası: ${e.message}")
                    throw ErrorLoadingException("Siteye ulaşılamadı (${e::class.simpleName})")
                }
            }
        }
        lastCookie = cookie
        val ok = resp.isSuccessful
        val text = resp.text
        Log.d(
            LOG_TAG,
            "GET $url → ${if (ok) "başarılı" else "BAŞARISIZ"}, ${text?.length ?: 0} karakter, " +
                "${izleCount(text)} kart linki"
        )

        // v20 SON KATMAN: GET, oturum geçerli olmasına rağmen BOŞ içerik
        // dönebiliyor (site document-dışı isteklere farklı dönebilir).
        // Aynı URL WebView'da açılır; tarayıcının render ettiği TAM DOM
        // (outerHTML) alınır ve o çözülür — kullanıcı tarayıcıda ne
        // görüyorsa eklenti de onu görür.
        // v30: WebView yalnızca sayfa GERÇEKTEN boşsa (bot/boş gövde) devreye girer.
        // Eskiden "kart linki yok" ölçütü bölüm sayfalarında da tetikleniyor, her
        // sayfa açılışında 5+ sn'lik diyalog açıyordu.
        // v41: WebView DOM yedeği KALDIRILDI (Android UI bağımlılığı → çalışma
        // zamanı uyumsuzluğu). Onun yerine: boş/kısa gövdede istek bir kez daha
        // denenir; captcha kapısıysa zaten yukarıdaki HTTP çözümü devreye girer.
        if (webViewFallback && ok && !isBotBody(text) && (text?.length ?: 0) < 3000) {
            Log.w(LOG_TAG, "GET boş içerik getirdi → tek tekrar: $url")
            val retry = guarded("GET-tekrar ${url.take(90)}") {
                siteHttp.get(url, headers = browserHeaders(lastCookie, referer ?: mainUrl + "/"), timeout = 12)
            }
            if (retry != null && retry.isSuccessful) {
                val retryText = retry.text ?: ""
                if (retryText.length > (text?.length ?: 0)) {
                    Log.d(LOG_TAG, "Tek tekrar ${retryText.length} karakter döndürdü")
                    return retryText
                }
            }
        }
        if (isCaptchaUrl(resp.url.toString()) || isCaptchaPage(text)) {
            throw ErrorLoadingException("Site bot doğrulaması istedi; doğrulama tamamlanamadı.")
        }
        if (text.isNullOrBlank()) {
            throw ErrorLoadingException("Siteye ulaşılamadı.")
        }
        return text
    }

    // ------------------------------------------------------------------
    // Bölüm sayfası (load)
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        if (!TrAnimeIzleHelper.isAllowedVersion) return null

        // v26: canlı domain garantisi; URL eski (ölü) domaini taşıyorsa
        // (arama öncesinden kalan / bookmark) mevcut domain'e haritalanır.
        ensureDomainAlive()
        val url = remapDomain(url)

        // v17: fetchHtml → kayıtlı oturumla dener, bot cevabı alırsa
        // WebView diyaloğuyla oturumu yeniler ve bir kez tekrar dener.
        val html = try {
            fetchHtml(url, mainUrl + "/", ensureSession = false, webViewFallback = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Throwable) {
            // v27: beklenmedik istisna arayuzde "cokme" olarak gorunmesin.
            Log.e(LOG_TAG, "load($url) sayfa hatası: ${e::class.simpleName}: ${e.message}")
            throw ErrorLoadingException("Sayfa yüklenemedi (${e::class.simpleName})")
        }
        var document = Jsoup.parse(html)

        val banasayfa = fixUrlNull(
            document.selectFirst("ol.breadcrumb li + li:not(.active) a")?.attr("href")
        )
        Log.d(LOG_TAG, "banasayfa = $banasayfa")
        if (!banasayfa.isNullOrEmpty()) {
            // v27: zaman asimi — banasayfa takilirse butun akis beklerdi.
            val banHtml = try {
                siteHttp.get(banasayfa, headers = browserHeaders(lastCookie), timeout = 10).text
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "banasayfa çekilemedi: ${e.message}")
                null
            }
            if (!banHtml.isNullOrBlank()) {
                document = Jsoup.parse(banHtml)
            }
        }

        // v28: detay + bölüm çözümlemesi tek bir saf modülde (TrAnimeIzleSite):
        //  - başlık/postür/özet/yıl/puan/etiketler için ÇOKLU seçici (tema değişimi)
        //  - bölüm listesi üç ayrı yerden toplanır ve her bölümde season+number GARANTİ
        //    edilir (Kitsugi'nin bölüm eşleştiricisi ikisi de dolu olmayan bölümü atar)
        val detail = TrAnimeIzleSite.parseDetail(document, mainUrl + "/", url)
        val title = detail.title
        val poster = detail.poster
        val description = detail.plot
        val year = detail.year
        val tags = detail.tags
        val rating = detail.score?.toString()
        val duration = document.selectFirst("div.col-sm-7 dt:contains(dk)")?.text()
            ?.split(" ")?.firstOrNull()?.toIntOrNull()
        val recommendations = document.select("div.srelacionados article")
            .mapNotNull { toRecommendationResult(it) }
        val actors = document.select("span.valor a").map { it.text() }
        val trailer = Regex("embed/(.*)\\?rel").find(document.html())
            ?.groupValues?.getOrNull(1)?.let { "https://www.youtube.com/embed/$it" }
        val animeTipi = detail.typeText
            ?: document.selectFirst("div.col-sm-5 dd:contains(Anime Tipi) + dt")?.text()

        // "Bölüm Sayısı: 24/24" → tamamı yayınlanmış (completed) sayılır.
        val totalEpText = document.selectFirst("dd:contains(Bölüm Sayısı) + dt")?.text()?.trim()
        val isCompleted = if (totalEpText != null) {
            val parts = totalEpText.split("/")
            parts.size == 2 && parts[0].trim() == parts[1].trim()
        } else false

        val meta = fetchKitsuDataByTitle(title)

        // İlk bölüm URL'si (film tipi için data alanı).
        val firstEpisodeUrl = detail.episodes.firstOrNull()?.url
            ?: fixUrlNull(document.selectFirst("div.animeDetail-playlist li a")?.attr("href"))

        // Bölüm listesi.
        val episodes = mutableListOf<Episode>()
        // v27: java.time (LocalDate/DateTimeFormatter) API 26+ ister; modulun
        // minSdk'i 21 → eski cihazda NoClassDefFoundError ile UYGULAMA COKERDI.
        // java.text.SimpleDateFormat her API seviyesinde mevcut.
        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.forLanguageTag("tr"))
        fun normaliseDate(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return try {
                synchronized(dateFormat) { dateFormat.parse(raw) }
                    ?.let { d -> SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d) }
            } catch (_: Exception) {
                null
            }
        }
        for (ep in detail.episodes) {
            val kitsuEp = meta?.episodes?.get(ep.number)
            episodes.add(
                newEpisode(ep.url) {
                    this.name = kitsuEp?.title?.ifBlank { null } ?: ep.name
                    this.posterUrl = kitsuEp?.thumb?.ifBlank { null } ?: ep.poster
                    if (kitsuEp != null) this.description = kitsuEp.description
                    // v28: sezon HER ZAMAN dolu (site "3. Sezon" yazmıyorsa 1).
                    this.season = ep.season
                    this.episode = ep.number
                    // v42: `addDate` KALDIRILDI (MainAPIKt#addDate, sürüme duyarlı API).
                    // Tarih, bölüm adının yanında zaten gösteriliyor.
                }
            )
        }
        Log.d(LOG_TAG, "load($url): ${episodes.size} bölüm, tip=${animeTipi ?: "?"}")

        val metaTitle = meta?.title
        // v28: "Dizi" (TV) dizisi film sayılıp TEK PARÇA olarak sunuluyordu → kullanıcı
        // dizinin yalnızca 1. bölümünü "film" olarak görüyordu. Artık yalnızca gerçek
        // film ("Film"/"Movie" etiketi) veya tek bölümlü yapımlar film sayılır.
        val isFilmType = animeTipi?.let {
            it.contains("film", ignoreCase = true) || it.contains("movie", ignoreCase = true)
        } == true
        val useMovieResponse = metaTitle != null && (isFilmType || episodes.size <= 1)
        return if (useMovieResponse) {
            // Film / tek bölümlük yapım (tek parça) olarak sunulur.
            newMovieLoadResponse(
                metaTitle,
                url,
                TvType.AnimeMovie,
                firstEpisodeUrl ?: ""
            ) {
                this.posterUrl = meta?.cover?.ifBlank { null } ?: poster
                this.plot = meta?.description?.ifBlank { null } ?: description
                this.year = meta?.year ?: year
                this.tags = tags.distinct()
                this.score = meta?.score?.let { Score.from(it, 0) } ?: Score.from10(rating)
                this.duration = meta?.duration ?: duration
                this.recommendations = recommendations
                addActors(actors.map { Actor(it, null) })
                val finalTrailer = meta?.trailerUrl ?: trailer
                if (finalTrailer != null) addTrailer(finalTrailer)
                // v42: `syncData` KALDIRILDI — CloudStream'e SONRADAN eklenen alan;
                // Kitsugi'nin gömülü `library-android:1.0.2` sürümünde yoksa
                // `NoSuchMethodError: LoadResponse.getSyncData` → KTS-CSP-9110.
                // Kitsu kimliği artık yalnızca log'a yazılıyor.
                meta?.kitsuId?.let { Log.d(LOG_TAG, "kitsuId=$it ($url)") }
                meta?.banner?.ifBlank { null }?.let { this.backgroundPosterUrl = it }
            }
        } else {
            newAnimeLoadResponse(
                meta?.title?.ifBlank { null } ?: title,
                url,
                TvType.Anime,
                !isCompleted
            ) {
                this.posterUrl = meta?.cover?.ifBlank { null } ?: poster
                this.plot = meta?.description?.ifBlank { null } ?: description
                this.year = meta?.year ?: year
                this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
                this.tags = tags.distinct()
                this.synonyms = meta?.synonyms
                this.score = meta?.score?.let { Score.from(it, 0) } ?: Score.from10(rating)
                this.duration = meta?.duration ?: duration
                this.recommendations = recommendations
                this.showStatus = when {
                    meta?.showStatus == ShowStatus.Completed -> ShowStatus.Completed
                    meta?.totalEpisodes != null && meta.totalEpisodes == episodes.size ->
                        ShowStatus.Completed
                    meta?.showStatus != null -> meta.showStatus
                    isCompleted -> ShowStatus.Completed
                    else -> ShowStatus.Ongoing
                }
                addActors(actors.map { Actor(it, null) })
                val finalTrailer = meta?.trailerUrl ?: trailer
                if (finalTrailer != null) addTrailer(finalTrailer)
                // v42: `syncData` KALDIRILDI — CloudStream'e SONRADAN eklenen alan;
                // Kitsugi'nin gömülü `library-android:1.0.2` sürümünde yoksa
                // `NoSuchMethodError: LoadResponse.getSyncData` → KTS-CSP-9110.
                // Kitsu kimliği artık yalnızca log'a yazılıyor.
                meta?.kitsuId?.let { Log.d(LOG_TAG, "kitsuId=$it ($url)") }
                meta?.banner?.ifBlank { null }?.let { this.backgroundPosterUrl = it }
            }
        }
    }

    // ------------------------------------------------------------------
    // Kitsu zenginleştirme
    // ------------------------------------------------------------------

    data class KitsuEpisode(
        val title: String,
        val thumb: String,
        val description: String
    )

    data class KitsuMetadata(
        val banner: String,
        val cover: String,
        val description: String,
        val duration: Int?,
        val episodes: Map<Int, KitsuEpisode>,
        val kitsuId: String,
        val score: Double?,
        val showStatus: ShowStatus?,
        val synonyms: List<String>,
        val title: String,
        val totalEpisodes: Int?,
        val trailerUrl: String?,
        val year: Int?
    )

    private suspend fun fetchKitsuDataByTitle(rawTitle: String?): KitsuMetadata? {
        if (rawTitle.isNullOrBlank()) return null
        val (cleanTitle, _) = parseTitleAndSeason(rawTitle)
        val encoded = cleanTitle.replace(" ", "%20")
        return try {
            val response = app.get(
                "https://kitsu.io/api/edge/anime?filter[text]=$encoded",
                timeout = 10
            )
            val responseText = response.text ?: return null
            val json = JSONObject(responseText)
            val data = json.getJSONArray("data")
            if (data.length() == 0) return null
            val first = data.getJSONObject(0)
            val kitsuId = first.getString("id")
            val attrs = first.getJSONObject("attributes")

            val title = attrs.optString("canonicalTitle", "")
            val description = attrs.optString("synopsis", "")
            val banner = attrs.optJSONObject("posterImage")?.optString("original", "")
            val cover = attrs.optJSONObject("coverImage")?.optString("original", "")
            val statusRaw = attrs.optString("status", "")
            val showStatus = when (statusRaw) {
                "finished" -> ShowStatus.Completed
                "current" -> ShowStatus.Ongoing
                else -> null
            }
            val totalEpisodes = attrs.optInt("episodeCount", 0).takeIf { it != 0 }
            val duration = attrs.optInt("episodeLength", 0).takeIf { it != 0 }
            val score = attrs.optString("averageRating", "").toDoubleOrNull()
            val year = attrs.optString("startDate", "")
                .split("-").firstOrNull()?.toIntOrNull()
            val youtubeId = attrs.optString("youtubeVideoId", "")
            val trailerUrl = if (youtubeId.isNotBlank())
                "https://www.youtube.com/watch?v=$youtubeId" else null

            // Synonymler: titles objesi (değerleri) + abbreviatedTitles dizisi.
            val synonyms = mutableListOf<String>()
            attrs.optJSONObject("titles")?.let { titlesObj ->
                for (key in titlesObj.keys()) {
                    titlesObj.optString(key, "").takeIf { it.isNotBlank() }?.let(synonyms::add)
                }
            }
            attrs.optJSONArray("abbreviatedTitles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i, "").takeIf { it.isNotBlank() }?.let(synonyms::add)
                }
            }

            // Bölümler (en fazla 100) — sayısal harita (DEX'teki davranış).
            // NOT: DEX burada "canonicalTitle"/"synopsis" anahtarlarını okuyordu;
            // Kitsu episode API'sinde bu anahtarlar olmadığı için hep boş çıkıyordu.
            // Burası doğru API anahtarları ("title"/"description") ile düzeltildi.
            val epMap = mutableMapOf<Int, KitsuEpisode>()
            val epResponse = app.get(
                "https://kitsu.io/api/edge/anime/$kitsuId/episodes?page[limit]=100",
                timeout = 10
            )
            val epText = epResponse.text ?: return null
            val epJson = JSONObject(epText)
            val epData = epJson.getJSONArray("data")
            for (i in 0 until epData.length()) {
                val epObj = epData.getJSONObject(i)
                val epAttrs = epObj.getJSONObject("attributes")
                val number = epAttrs.optInt("number", -1)
                if (number > 0) {
                    val epTitle = epAttrs.optString("title", "")
                    val epThumb = try {
                        epAttrs.optJSONObject("thumbnail")?.optString("original", "") ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                    val epDesc = epAttrs.optString("description", "")
                    epMap[number] = KitsuEpisode(
                        title = if (epTitle.isBlank()) "" else epTitle,
                        thumb = if (epThumb.isBlank()) "" else epThumb,
                        description = if (epDesc.isBlank()) "" else epDesc
                    )
                }
            }

            KitsuMetadata(
                banner = banner ?: "",
                cover = cover ?: "",
                description = description,
                duration = duration,
                episodes = epMap,
                kitsuId = kitsuId,
                score = score,
                showStatus = showStatus,
                synonyms = synonyms.distinct(),
                title = title,
                totalEpisodes = totalEpisodes,
                trailerUrl = trailerUrl,
                year = year
            )
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Kitsu verisi alınamadı: ${e.message}")
            null
        }
    }

    private fun parseTitleAndSeason(title: String): Pair<String, Int> {
        val seasonRegex = Regex(
            "(?i)\\b(?:season|sezon)\\s*(\\d+)|(\\d+)[.\\s]*sezon|(\\d+)(?:st|nd|rd|th)?\\s+season"
        )
        val match = seasonRegex.find(title)
        val season = match?.let { m ->
            m.groupValues[1].toIntOrNull()
                ?: m.groupValues[2].toIntOrNull()
                ?: m.groupValues[3].toIntOrNull()
        } ?: 1
        val cleanTitle = if (match != null) title.replace(seasonRegex, "") else title
        return cleanTitle.trim() to season
    }

    // ------------------------------------------------------------------
    // Kaynak yükleme (loadLinks)
    // ------------------------------------------------------------------

    // v42: `VideoApi` (Jackson `@JsonProperty` taşıyan veri sınıfı) KALDIRILDI.
    // Kullanılmıyordu ve `com.fasterxml.jackson.annotation.JsonProperty`
    // referansı eklentiyi Jackson sürümüne bağlıyordu (uygulama 2.18.3 gömüyor,
    // depo 2.13/2.16 ile derliyor). JSON alanları artık `org.json` ile okunuyor.

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // v26: canlı domain garantisi; bölüm URL'i ölü domaini taşıyorsa
        // mevcut domain'e haritalanır.
        ensureDomainAlive()
        val dataUrl = remapDomain(data)
        Log.d(LOG_TAG, "loadLinks: $dataUrl")

        val count = try {
            TrAnimeIzleStreams(name, { mainUrl }, this)
                .load(dataUrl, subtitleCallback, callback)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "loadLinks hatası: ${e.javaClass.simpleName}: ${e.message}")
            0
        }

        if (count > 0) {
            Log.d(LOG_TAG, "loadLinks: $count akış yayınlandı")
            return true
        }
        Log.w(LOG_TAG, "loadLinks: kaynak bulunamadı ($dataUrl)")
        throw ErrorLoadingException(
            "Kaynak bulunamadı: site bu bölüm için oynatıcı listesi döndürmedi. " +
                "Başka bir bölüm/fansub deneyin veya eklentiyi güncelleyin."
        )
    }

    companion object {
        private const val LOG_TAG = "kraptor_TrAnime"
        private const val LUFFY_PREFIX = "luffytra2.top/embed2/?id="

        // v32: ana domain **www.tranimeizle.live**.
        // KANIT (uygulamanın kendi teşhis raporu, `plugin_diagnostic_report.md`,
        // 25.10.2026): `https://www.tranimeizle.io` → **301 → https://www.tranimeizle.live/**.
        // Yani .io artık yalnızca yönlendirme kabuğu; oturum/çerez ve API
        // uçları (.live) üzerinde kuruluyor. Eski varsayılan .io olduğu için
        // arama boş dönüyor, kaynak listeleri 0 geliyordu.
        // Tek domain'e kilitlenme yok: DOMAIN_CANDIDATES sırayla denenir,
        // .io/.top yedek olarak korunur (site domain'i yılda birkaç kez taşıyor).
        private const val DEFAULT_DOMAIN = "https://www.tranimeizle.live"

        /** v32: otomatik rotasyon aday domainleri (sıra = öncelik). */
        private val DOMAIN_CANDIDATES = listOf(
            "https://www.tranimeizle.live",
            "https://tranimeizle.live",
            "https://www.tranimeizle.io",
            "https://tranimeizle.io",
            "https://www.tranimeizle.net",
            "https://tranimeizle.net",
            "https://www.tranimeizle.co",
            "https://tranimeizle.co",
            "https://www.tranimeizle.top",
            "https://tranimeizle.top"
        )

        private const val DOMAIN_CHECK_TTL_MS = 10 * 60 * 1000L

        /**
         * Domain çözümleme.
         *
         * v41: **SharedPreferences okuması kaldırıldı**. Çalışan eklentilerin
         * hiçbiri `android.content.SharedPreferences` kullanmıyor; bu bağımlılık
         * eklenti sınıf yükleyicisinde "çalışma zamanı uyumsuzluğu" riski
         * taşıyordu. Domain artık:
         *
         *  1. [DEFAULT_DOMAIN] ile başlar,
         *  2. `ensureDomainAlive()` canlı doğrulama yapar (ana sayfa 200 + içerik),
         *  3. ölü domainde [DOMAIN_CANDIDATES] sırayla denenir.
         */
        fun getDomain(): String {
            Log.d(LOG_TAG, "domain = $DEFAULT_DOMAIN")
            return DEFAULT_DOMAIN
        }
    }
}
