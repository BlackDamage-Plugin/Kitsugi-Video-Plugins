# FilmMakinesi Close/Rapid — açık kaynak eklentiler ne yapıyor

HTTP 451 bu ortamda ve GitHub runner’da var. Soru: başkaları bunu nasıl geçiyor?

Kaynak kodu okunan depolar (kopyalanmadı):

| Depo | Close/Rapid yaklaşımı | 451 / vekil / VPN |
| --- | --- | --- |
| [JustSadY/Cloudstream-Lykia](https://github.com/JustSadY/Cloudstream-Lykia) | `app.get(embed, referer)`; Rhino ile `sources:[{file:VAR}]` | yok |
| [beyazsapka/cs-beyaz](https://github.com/beyazsapka/cs-beyaz) | aynı GET; `RapidRame.decrypt` (XOR/permütasyon); CF için `CloudflareKiller` | yok |
| [Emre-Kahveci/CloudStreamHub](https://github.com/Emre-Kahveci/CloudStreamHub) | sabit şablonlu CloseLoad decode | yok |
| [patr0nq/Kekik-cloudstream](https://github.com/patr0nq/Kekik-cloudstream) | GET + `dc_*` / packer; JSON-LD `contentUrl` yedek (tuzak) | yok |
| [falsisdev/anthology](https://github.com/falsisdev/anthology) | Close embed için kısa `Mozilla/5.0` UA | yok |

Hiçbiri coğrafi 451 için vekil, VPN, `CF-IPCountry` veya sahte çıkış kullanmıyor. Hepsi isteği **cihazın kendi IP’sinden** atıyor. Eklenti telefonda TR hattındaysa 451 beklenen durum değil.

Bu repodaki MiniJs zaten Lykia Rhino şiminden fazla DOM (`querySelector` / `data-v`) kapsıyor. Beyaz’ın `RapidRame.decrypt` aynı JS ailesinin Kotlin çevirisi; canlı gövde olmadan kopyalanmadı.

GitHub Actions 43726097619 gerçek Close/Rapid adreslerinde hâlâ 0 oyuncu HTML. Ücretsiz TR vekil listesi ev hattı değil.

Sonuç: 451’i “internet talanı” ile bu koşuda aşan kamu yöntemi yok. Cihazda kırılma varsa neden 451 değil, oynatıcı JS / zaman aşımı. Yeni sürüm yok.
