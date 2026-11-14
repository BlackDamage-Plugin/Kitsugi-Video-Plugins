# Geçici oynatıcı paketi geri dönüşü

FilmMakinesi 72 ve HDFilmCehennemi 63 yayına alındıktan sonra video kaynağı
bulunamadığı bildirildi. Sorunun nedeni ve kullanıcının o anda kurulu eklenti
sürümleri doğrulanmamıştır; alan adının aynı olması da oynatıcının değişmediği
anlamına gelmez. Canlı oynatma testi yapılmamıştır.

Bu geçici seçenek, Drive `Kitsugi-Plugins-Video-CleanSource.zip` arşivindeki
FilmMakinesi 71 ve HDFilmCehennemi 62 CS3 paketlerinin çalıştırılabilir
`classes.dex` içeriğini birebir korur. Yalnızca iç manifestin sürüm numarası
sırasıyla 73 ve 64 yapıldı; böylece uygulama güncelleme olarak görebilir.
Paketler `prebuilt/` altında saklanır; yayın akışı daha yüksek sürümlü hazır
paketi seçecektir. `tests/test_drive_fallback_packages.py` DEX hash'lerini ve
sürüm/evreleme davranışını kontrol eder. Bu test canlı çalışmayı kanıtlamaz.

**Önemli:** Modüllerdeki Kotlin kaynakları bu hazır paketlerin kaynağı değildir;
72/63 sürümünün sonraki oyuncu uygulamasını korur. Bu nedenle kaynakları
tekrar derleyip 73/64 numarasını vermeyin: aynı sürümde farklı kod üretilmiş
olur. Gelecekteki bir kaynak düzeltmesi, cihazda test edildikten sonra daha
büyük bir sürüm numarasıyla yayımlanmalıdır. Geri dönüş, kullanıcı doğrulaması
sonrasında dahi sitelerin güncel davranışı nedeniyle çalışmayabilir.
