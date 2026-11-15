# FilmMakinesi v83 — Close/Rapid istek ve bölüm eşlemesi

Cihaz TR’de; 451 bu koşunun sorunu değil. Kullanıcının S01E01 HTML’inde Close ve Rapid adresleri var. Kırılma eklenti tarafında:

1. Close masaüstü UA ile 404 (Anthology kısa `Mozilla/5.0`). Rapid için masaüstü UA. Önce her kaynak iki UA ile gidiyordu (4 istek); uygulama 15 sn sınırında bitmiyordu.
2. Bölüm numarası `a.item-ep` içindeki `/sezon-N/bolum-M/` adresinden okunmalı. Metin `03 Ekim 2012 / 43 dk / 8. Sezon / 1. Bölüm` biçiminde; yanlış varsayılan S01E01 TMDB eşlemesini kaçırır.
3. Bölüm sayfası 20 sn bekliyordu; uygulama 15 sn’de kesiyordu.

v83: Close tek kısa UA, Rapid tek masaüstü UA; bölüm path; sayfa 8 sn / oyuncu 10 sn. Canlı oynatma bu koşuda doğrulanmadı.
