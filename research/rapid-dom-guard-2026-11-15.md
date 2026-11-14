# Rapid oynatıcı data-v denetimi — 15 Kasım 2026

HDFilmCehennemi Supernatural 1. sezon 1. bölüm Rapid iframe yanıtı, Türkiye
konumlu bir bağlantıyla alındı ve kullanıcının HDFilmCehennemi Drive klasörüne
kaydedildi. Yanıtta `sources` ifadesi, `document.querySelectorAll('[data-v]')`
ile sayfadaki gizli elemanın `data-v` değerini denetliyor; bulunmazsa boş dize
veriyor. Önceki `MiniJs` belgesi bu DOM sorgusunu gerçek sayfaya göre
uygulamıyordu. Bu, kaynak üretilememesinin somut bir hata yoludur; diğer hata
nedenlerini veya tüm Close/Rapid varyantlarını tek başına açıklamaz.

Her iki modül, gerçek HTML'deki `data-v` değerlerini Jsoup ile okuyup MiniJs'e
aktarıyor. Yorumlayıcı yalnızca `[data-v]` seçicisini ve `getAttribute('data-v')`
desteğini sağlar; olmayan değerler üretilmez. Eşleşen ve eşleşmeyen örnekler
iki modülde de test edilir. HDFilmCehennemi'nin opsiyonel gerçek yanıt testi,
`LIVE_RAPID_CAPTURE` ortam değişkeniyle geçici HTML dosyasına işaret edebilir.
GitHub Actions run 43653715248 bu gerçek yanıtla Kotlin testlerini geçti;
43653715227 derleme ve birim testlerini geçti. Bu, kullanıcı cihazında video
oynatımını ve FilmMakinesi/Rapid ya da iki Close uç noktasını doğrulamaz.

Sürümler önceki geçici hazır paketleri geçmek için FilmMakinesi 74 ve
HDFilmCehennemi 65 olarak artırılmıştır. Geçerli eski prebuilt 73/64 dosyaları
kaynakla aynı değildir; yayın, daha yeni derlemeleri seçmelidir.
