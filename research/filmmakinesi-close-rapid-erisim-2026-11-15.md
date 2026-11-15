# FilmMakinesi Close / Rapid — gerçek adresli erişim

Kullanıcının Drive’daki Supernatural S01E01 bölüm HTML’inde iki oynatıcı var:

- Close: `closeload.filmmakinesi.to/video/embed/<id>/`
- Rapid: `rapid.filmmakinesi.to/embed-<id>/`

Önceki Actions denemeleri HD kimliklerini bu hostlara yazıyordu. GitHub Actions 43726097619 bu kez bölüm sayfasındaki gerçek adresleri denedi.

## Sonuç

Oyuncu HTML’i alınamadı. Yeni çözücü veya sürüm yok.

| Yol | Close | Rapid | Bölüm |
| --- | --- | --- | --- |
| GitHub runner doğrudan | HTTP 451 geo | HTTP 451 geo | HTTP 451 geo |
| jina | 200, geo gövdesi | 200, geo gövdesi | 200, geo gövdesi |
| allorigins / corsproxy / codetabs | 522 veya 403 | 522 veya 403 | 522 veya 403 |
| Wayback CDX | kayıt yok | hata | hata |
| 83 TR listeli HTTP/SOCKS | 4×403, 1×451, 78 ağ | 5×403 + 1×403, kalan ağ | 1×403, kalan ağ |

`player_html` sayısı her hedefte 0. Jina 200 dönmesi coğrafi sayfanın okunabildiği anlamına gelir; JWPlayer gövdesi değildir.

Bu, kullanıcının cihazında aynı kodun geleceğini kanıtlamaz. Bölüm sayfası Drive yakalamasında açık; kırılma oynatıcı belgesindedir ve bu koşuda o belge yok.
