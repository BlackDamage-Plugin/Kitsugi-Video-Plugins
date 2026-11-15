#!/usr/bin/env python3
"""HDFilmCehennemi Close (CloseLoad) zinciri için salt-okunur canlı prob.

Amaç: "Close kaynağı bir süre sonra gelmiyor" şikâyetini tahminle değil ölçümle
ayırmak. Aşağıdakileri sırayla raporlar:

  1. Site kökü / bilinen film-bölüm sayfası hangi HTTP kodunu dönüyor?
  2. Sayfadaki dil sekmeleri, `[data-video]` düğmeleri ve varsayılan iframe.
  3. `/video/{id}/` yanıtındaki oynatıcı adresi ve **host'u** (kaynak rotasyonu).
  4. Oynatıcı sayfası üç farklı User-Agent ile: HTTP kodu, boyut, Cache-Control
     ve çözücü ipuçları (`sources`, `jwplayer`, `data-v`, `hostname`, CDN adı).
  5. Çözülen akış adresinin CDN'den Referer ile/Referer'sız yanıtı.

Çıktı sorgu dizgelerini (imza/anahtar) ve ham HTML'i yazmaz; yalnızca sınıflandırma
ve sayaç verir. Ağ erişimi olan bir ortamda çalıştırılmalıdır (CI adımı).

Kullanım:
    python3 tools/hdfc_close_probe.py            # varsayılan adresler
    python3 tools/hdfc_close_probe.py <sayfa-url>
"""
from __future__ import annotations

import json
import re
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

DESKTOP_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)
SHORT_UA = "Mozilla/5.0"
MOBILE_UA = (
    "Mozilla/5.0 (Linux; Android 13; SM-A536B) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
)
UA_VARIANTS = [("desktop", DESKTOP_UA), ("short", SHORT_UA), ("mobile", MOBILE_UA)]

ROOTS = [
    "https://www.hdfilmcehennemi.nl",
    "https://hdfilmcehennemi.nl",
    "https://www.hdfilmcehennemi.la",
]
DEFAULT_PAGES = [
    "/insidious-out-of-the-further-26/",
]
MAX_BYTES = 3 * 1024 * 1024
SSL_CTX = ssl.create_default_context()

LOG: list[str] = []


def log(line: str = "") -> None:
    print(line, flush=True)
    LOG.append(line)


def redact(url: str) -> str:
    """Sorgu dizgesini (imza/token) gizle, yolun sonunu kısalt."""
    try:
        parts = urllib.parse.urlsplit(url)
    except ValueError:
        return url
    path = parts.path if len(parts.path) <= 90 else parts.path[:87] + "..."
    q = "?<gizli>" if parts.query else ""
    return f"{parts.scheme}://{parts.netloc}{path}{q}"


def fetch(url, ua=DESKTOP_UA, referer="", xhr=False, follow=True, timeout=30):
    headers = {
        "User-Agent": ua,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8",
    }
    if referer:
        headers["Referer"] = referer
    if xhr:
        headers["X-Requested-With"] = "fetch"
        headers["Accept"] = "*/*"
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, headers=headers)
    opener = urllib.request.build_opener(
        urllib.request.HTTPSHandler(context=SSL_CTX), urllib.request.HTTPCookieProcessor()
    )
    if not follow:
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a, **k):
                return None
        opener = urllib.request.build_opener(
            urllib.request.HTTPSHandler(context=SSL_CTX),
            urllib.request.HTTPCookieProcessor(),
            NoRedirect(),
        )
    try:
        with opener.open(req, timeout=timeout) as res:
            raw = res.read(MAX_BYTES)
            return {
                "status": res.status,
                "url": res.geturl(),
                "body": raw.decode("utf-8", "replace"),
                "headers": {k.lower(): v for k, v in res.headers.items()},
                "error": "",
            }
    except urllib.error.HTTPError as exc:
        raw = b""
        try:
            raw = exc.read(MAX_BYTES)
        except Exception:  # noqa: BLE001
            pass
        return {
            "status": exc.code,
            "url": exc.geturl() or url,
            "body": raw.decode("utf-8", "replace"),
            "headers": {k.lower(): v for k, v in (exc.headers or {}).items()},
            "error": "",
        }
    except Exception as exc:  # noqa: BLE001
        return {"status": 0, "url": url, "body": "", "headers": {}, "error": str(exc)}


def markers(html: str) -> str:
    low = html.lower()
    keys = []
    for name, needle in (
        ("jwplayer", "jwplayer"),
        ("sources", "sources"),
        ("atob", "atob"),
        ("packer", "eval(function(p,a,c,k,e,d)"),
        ("data-v", "data-v"),
        ("hostname", "hostname"),
        ("querySelector", "queryselector"),
        ("getElementById", "getelementbyid"),
        ("cdnimages", "cdnimages"),
        ("closeload", "closeload"),
        ("rapidrame", "rapidrame"),
        ("m3u8", "m3u8"),
        ("ld+json", "ld+json"),
        ("cloudflare", "cloudflare"),
        ("challenge", "just a moment"),
    ):
        if name == "packer":
            if "eval(function(p,a,c,k,e,d)" in low:
                keys.append(name)
            continue
        if needle in low:
            keys.append(name)
    return ",".join(keys) if keys else "-"


HOST_RE = re.compile(r"https?://([a-z0-9.\-]+)", re.I)


def hosts_in(text: str) -> str:
    found = sorted({m.group(1).lower() for m in HOST_RE.finditer(text)})
    interesting = [h for h in found if any(k in h for k in ("cdn", "close", "rapid", "hdfilm", "srv"))]
    return ",".join(interesting[:8]) if interesting else "-"


def iframes(html: str) -> list[str]:
    out = []
    for m in re.finditer(r"<iframe\b[^>]*>", html, re.I):
        tag = m.group(0)
        src = re.search(r'(?:data-src|src)\s*=\s*["\']([^"\']+)["\']', tag, re.I)
        if src:
            out.append(src.group(1).replace("\\/", "/").replace("&amp;", "&"))
    return out


def buttons(html: str) -> list[dict]:
    out = []
    for m in re.finditer(r"<button\b[^>]*data-video\s*=\s*[\"'](\d+)[\"'][^>]*>(.*?)</button>", html, re.I | re.S):
        text = re.sub(r"<.[^>]*>", " ", m.group(2))
        out.append({"video_id": m.group(1), "label": " ".join(text.split())[:40]})
    return out


def api_embed(api_body: str) -> str | None:
    m = re.search(r'"html"\s*:\s*"', api_body)
    text = api_body
    if m:
        text = api_body[m.end():]
        text = text.replace('\\"', '"').replace("\\/", "/")
    found = iframes(text)
    return found[0] if found else None


def main() -> int:
    pages = [p for p in sys.argv[1:] if p.startswith("http")]
    log("# HDFilmCehennemi Close (CloseLoad) canlı prob")
    log()

    page_url = ""
    page = None
    for root in ROOTS:
        probe = fetch(root + "/")
        log(f"- kök {root}: HTTP {probe['status'] or probe['error'][:60]} "
            f"({len(probe['body'])} bayt, son={redact(probe['url'])})")
        if probe["status"] == 200 and len(probe["body"]) > 500:
            if not pages:
                pages = [root + p for p in DEFAULT_PAGES]
            break
    log()

    for candidate in pages:
        page = fetch(candidate)
        log(f"## Sayfa {redact(candidate)} → HTTP {page['status'] or page['error'][:60]} "
            f"({len(page['body'])} bayt)")
        page_url = page["url"]
        if page["status"] == 200 and len(page["body"]) > 500:
            break
    if not page or page["status"] != 200:
        log("Sayfa alınamadı; prob burada bitiyor.")
        return 1

    html = page["body"]
    log(f"- sayfadaki oynatıcı işaretleri: {markers(html)}")
    log(f"- sayfadaki ilgili host'lar: {hosts_in(html)}")
    found_iframes = iframes(html)
    log(f"- iframe sayısı: {len(found_iframes)}")
    for f in found_iframes[:4]:
        log(f"    · {redact(f)}")
    page_buttons = buttons(html)
    log(f"- data-video düğmesi: {len(page_buttons)}")
    for b in page_buttons[:8]:
        log(f"    · {b['label']} = {b['video_id']}")

    root = "{0.scheme}://{0.netloc}".format(urllib.parse.urlsplit(page_url))
    embeds: list[str] = [f for f in found_iframes if any(k in f.lower() for k in ("/video/embed/", "/rplayer/", "closeload", "rapid"))]
    for b in page_buttons[:8]:
        api = fetch(f"{root}/video/{b['video_id']}/", referer=page_url, xhr=True, timeout=20)
        embed = api_embed(api["body"]) if api["status"] == 200 else None
        log(f"- API {b['video_id']} ({b['label']}) → HTTP {api['status'] or api['error'][:50]}"
            f"{'' if api['status'] != 200 else ', oynatıcı=' + (redact(embed) if embed else 'YOK')}")
        if embed and embed not in embeds:
            embeds.append(embed)

    log()
    log("## Oynatıcı sayfaları (UA varyantları)")
    for embed in embeds[:4]:
        log(f"### {redact(embed)}")
        for name, ua in UA_VARIANTS:
            res = fetch(embed, ua=ua, referer=page_url, timeout=25)
            cache = res["headers"].get("cache-control", "-")
            log(f"- [{name}] HTTP {res['status'] or res['error'][:60]} · {len(res['body'])} bayt · "
                f"cache-control={cache} · işaretler={markers(res['body'])}")
            if res["status"] == 200 and res["body"]:
                log(f"    host'lar: {hosts_in(res['body'])}")
                stream = re.search(r"https?://[^\s\"'<>\\]+\.(?:m3u8|txt)[^\s\"'<>\\]*", res["body"])
                if stream:
                    log(f"    akış adayı: {redact(stream.group(0))}")
    log()
    log("## Özet")
    log(json.dumps({"page_status": page["status"], "iframes": len(found_iframes),
                    "buttons": len(page_buttons), "embeds": len(embeds)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
