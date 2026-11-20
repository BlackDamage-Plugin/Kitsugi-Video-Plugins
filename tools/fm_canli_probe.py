#!/usr/bin/env python3
"""FilmMakinesi canlı sayfa HTML'lerini toplar (salt-okunur, medya indirmez).

Arama, ana sayfa, film/dizi sayfası ve oynatıcı embed sayfalarının ham
HTML'lerini diske yazar; böylece ayrıştırıcı seçiciler gerçek yapı üzerinden
denenebilir. Çıktı FM_PROBE_OUT (varsayılan /tmp/fm-probe).

Kullanım:
    pip install cloudscraper
    python3 tools/fm_canli_probe.py
"""
from __future__ import annotations

import json
import os
import sys

from cloudscraper import CloudScraper

UA_CHROME = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)
UA_SHORT = "Mozilla/5.0"

BASE = "https://filmmakinesi.to"

# Arama sayfası (eklentinin birincil arama yolu) ve otomatik tamamlama yolu.
PAGES = {
    "home": BASE + "/",
    "arama-supernatural": BASE + "/arama/?s=supernatural",
    "autocomplete-supernatural": BASE + "/ajax/autocomplete/?s=supernatural",
    "film": BASE + "/film/orumcek-adam-yepyeni-bir-gun-2026-fm3/",
    "dizi-bolum": BASE + "/dizi/supernatural-2005-fm8t9q/sezon-1/bolum-1/",
    "close-embed": "https://closeload.filmmakinesi.to/video/embed/RFeVmBTmaCG/?imdb_id=tt0460681",
    "rapid-embed": "https://rapid.filmmakinesi.to/embed-r6b2y7qlpt3r/",
}

# Oynatıcı sayfaları kısa UA ister; site bu aileye masaüstü UA ile 404 döner.
UA_BY_KEY = {
    "close-embed": UA_SHORT,
    "rapid-embed": UA_CHROME,
}


def main() -> int:
    out = os.environ.get("FM_PROBE_OUT", "/tmp/fm-probe")
    os.makedirs(out, exist_ok=True)
    scraper = CloudScraper()
    scraper.headers.update({"Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8"})

    report: dict[str, dict] = {}
    for key, url in PAGES.items():
        ua = UA_BY_KEY.get(key, UA_CHROME)
        try:
            response = scraper.get(
                url,
                headers={"User-Agent": ua, "Referer": BASE + "/"},
                timeout=25,
            )
            body = response.text
            with open(os.path.join(out, f"{key}.html"), "w", encoding="utf-8") as handle:
                handle.write(body)
            report[key] = {
                "status": response.status_code,
                "bytes": len(body),
                "final_url": response.url,
                "title": _title(body),
            }
        except Exception as exc:  # noqa: BLE001 - probe raporu
            report[key] = {"error": f"{type(exc).__name__}: {exc}"}

    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0 if report else 1


def _title(html: str) -> str:
    import re

    match = re.search(r"<title[^>]*>(.*?)</title\s*>", html, re.I | re.S)
    return " ".join(match.group(1).split()) if match else ""


if __name__ == "__main__":
    sys.exit(main())
