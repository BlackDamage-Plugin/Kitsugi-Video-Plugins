# Anizm — canlı ölçüm notları (02.11.2026)

Bu klasör, `Anizm` eklentisinin **gerçek site sözleşmesinin** ölçüldüğü kayıtları tutar.
Büyük HTML/JS dökümleri depoyu şişirmemek için silindi; aynı ölçümler
`tools/anizm_gate_probe.py`, `tools/anizm_resolve_probe.py`,
`tools/anizm_sistenn_probe.py` ile yeniden üretilebilir. Ayrıştırıcı doğrulaması
için gereken gerçek sayfalar `Anizm/src/test/resources/fixtures/` altındadır.

## Alan adları (GitHub runner, aynı ağ sınıfı)

| adres | durum |
|---|---|
| `https://anizm.com.tr/` | **200**, 147 KB, `v3-series-card` ×39 |
| `https://anizm.net/` | 403 Cloudflare "Just a moment…" (veri merkezi IP) |
| `https://www.puffytr.com/` | 403 Cloudflare |
| `https://anizm.org/` | 200 ama 1.9 KB park sayfası |

Cihaz (operatör/residans IP) `anizm.net`'e girebildiği için eklenti önce
`anizm.com.tr` ile başlar, sayfa alınamazsa adayları tek tek dener
(`Anizm.ensureBase`) ve **başarısız bölüm isteğinden sonra** bir kez daha dener.

## Bölüm → akış zinciri (canlı, gerçek eklenti kodu)

```
/one-piece                                     → parseDetail + 1170 bölüm (1…1180)
/one-piece-1-bolum-izle                        → 3 çevirmen, CSRF var, +18 kapısı YOK
/bolum/18853/cevirmen/18853                    → {"success":true,"videos":[12 kayıt]}
/bolum/18853/video/1437961                     → {"success":true,"url":"…/player/1437961"}
/player/1437961                                → 302 → sağlayıcı
```

`data-sources` ucu **düz GET ile de** JSON döner (CSRF zorunlu değil); eklenti
XHR başlıklarıyla gider, 419 alırsa token'ı tazeler.

## Sağlayıcı matrisi

| kaynak | uç | sonuç (02.11.2026) |
|---|---|---|
| Aincrad | `anizmplayer.com` `POST /player/index.php?data=<hash>&do=getVideo` | **`securedLink`** = imzalı `master.m3u8`; `videoSource` = `master.txt` (oynatılmaz). Veri merkezi IP'de 403 Cloudflare, cihazda çalışır. |
| Beta Player | `pl.puffytr.tr/watch/<slug>` | `/stream/<slug>/native.m3u8` → `#EXTM3U` ✓ |
| FireStream klonu | `bysesayeveum.com` / `flyf.lat` `POST /api/<r>/resolve {blob}` | 200 `signedVideoUrl` (`…/video.m3u8?md5=…`) ✓ |
| GDrive | `drive.google.com/file/d/…/preview` | 401/429 — kota/oturum; cihazda oturum varsa oynar |
| Sistenn ×3 | `sistenn.uns.bio`, `anizm.rpmvid.com`, `anizm.strp2p.com` | `/api/v1/info` 200 (AES hex), `/api/v1/video` **400 "player is not recognized"** / 500 — sunucu tarafı sorun |
| Vidmoly / Voe / UQload / HDVid / Dood / Sibnet / Mail.ru | — | extractor katmanına devredilir; datacenter IP'de 403/404/522 (cihazda değişir) |

Tam kaynak denetimi (94 kaynak): çözücü kapsamı 81, ölçülen oynatılabilir 9 —
kalanı üçüncü taraf host'ların kendi kesintileri ya da veri merkezi IP engeli.

## +18 / +16 yaş kapısı

`v3-content-warning.js` v1.0.5 → `sessionStorage['anizm.v3.content-warning.<animeId>']`,
`localStorage['anizm.v3.content-warning.remembered-rating']`. Yani kapı **tamamen
istemci tarafında**; sunucu onay beklemiyor (bölüm sayfası ve `cevirmen` ucu onaysız
da 200). Eklenti WebView kullanmadığı için uyarı hiç görünmez; ayrıştırmada
`<dialog data-v3-content-warning>` düğümü atlanır (bağlantıları bölüm/kaynak
sanılmasın diye).
