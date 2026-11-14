#!/usr/bin/env python3
"""Opt-in read-only probe of user-provided player pages; never prints tokens or HTML."""
import re
import urllib.error
import urllib.parse
import urllib.request

PAGES = [
    ("FilmMakinesi Close", "https://closeload.filmmakinesi.to/video/embed/RFeVmBTmaCG/?imdb_id=tt0460681", "https://filmmakinesi.to/"),
    ("FilmMakinesi Rapid", "https://rapid.filmmakinesi.to/embed-6d4mkrakdi1a/", "https://filmmakinesi.to/"),
    ("HDFilmCehennemi Close", "https://hdfilmcehennemi.mobi/video/embed/RFeVmBTmaCG/?rapidrame_id=6d4mkrakdi1a", "https://www.hdfilmcehennemi.nl/"),
    ("HDFilmCehennemi Rapid", "https://www.hdfilmcehennemi.nl/rplayer/6d4mkrakdi1a/", "https://www.hdfilmcehennemi.nl/"),
]


def probe(label, url, referer):
    headers = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36", "Referer": referer, "Accept": "text/html,application/xhtml+xml,*/*"}
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=25) as response:
            status = response.status
            host = urllib.parse.urlsplit(response.url).hostname
            body = response.read(2_000_000).decode("utf-8", errors="replace")
    except urllib.error.HTTPError as exc:
        status = exc.code
        host = urllib.parse.urlsplit(exc.url).hostname
        body = exc.read(200_000).decode("utf-8", errors="replace")
    except Exception as exc:
        return f"- {label}: bağlantı hatası ({type(exc).__name__})"
    indicators = [x for x in ("jwplayer", "sources", "eval(function", "Just a moment", "Content unavailable in your region") if x.lower() in body.lower()]
    scripts = len(re.findall(r"<script\b", body, re.I))
    return f"- {label}: HTTP {status}; son host `{host}`; {len(body)} karakter; {scripts} script; işaretçiler: {', '.join(indicators) or 'yok'}"


if __name__ == "__main__":
    print("## Oynatıcı erişim kontrolü\n")
    print("Kullanıcının paylaştığı Supernatural 1. sezon 1. bölüm oyuncu adresleri; yalnızca HTTP durumu ve yapı işaretçileri, HTML veya token yok.\n")
    for page in PAGES:
        print(probe(*page))
