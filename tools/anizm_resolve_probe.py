#!/usr/bin/env python3
"""Anizm **referans akış çözücüsü** — eklentinin uygulayacağı zincirin Python aynası.

Amaç: Kotlin'e yazılacak mantığı önce burada kanıtlamak. Her sağlayıcı için
gerçek istek atar, gerçek yanıtı yazar ve **oynatılabilir** bir adres
(m3u8/mp4/`#EXTM3U`) elde edilip edilmediğini söyler.

Zincir (2026-11-02 canlı ölçümü):

    bölüm sayfası            → button[data-v3-translator][data-sources]
    GET  <data-sources>      → {"success":true,"videos":[{id,name}]}
    GET  /bolum/<ep>/video/<videoId>  (XHR + CSRF) → {"url":"/player/<id>","source":"…"}
    GET  /player/<id>        → 302 Location → sağlayıcı
    sağlayıcıya özel çözüm   → m3u8 / mp4

Kullanım:
    python3 tools/anizm_resolve_probe.py
    python3 tools/anizm_resolve_probe.py --url /one-piece-1-bolum-izle --limit 6
"""

from __future__ import annotations

import argparse
import gzip
import html as html_mod
import http.cookiejar
import io
import json
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
)
HEADERS = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8",
    "Accept-Encoding": "gzip",
    "Upgrade-Insecure-Requests": "1",
}
XHR = {
    "Accept": "application/json, text/plain, */*",
    "X-Requested-With": "XMLHttpRequest",
    "Sec-Fetch-Mode": "cors",
    "Sec-Fetch-Dest": "empty",
    "Sec-Fetch-Site": "same-origin",
}
DOMAINS = ["https://anizm.net", "https://anizm.com.tr"]
DEFAULT_EPISODE = (
    "/shingeki-no-kyojin-movie-kanketsu-hen-the-last-attack-"
    "shingeki-no-kyojin-movie-kanketsu-hen---the-last-attack-bolum-izle"
)
JAR = http.cookiejar.CookieJar()
MAX = 12 * 1024 * 1024
OUT = "research/anizm-probe"


def log(line: str = "") -> None:
    print(line, flush=True)


def save(name: str, text: str) -> None:
    import os
    os.makedirs(OUT, exist_ok=True)
    try:
        with open(os.path.join(OUT, name), "w", encoding="utf-8") as fh:
            fh.write(text or "")
    except Exception:  # noqa: BLE001
        pass


def _decode(raw: bytes, enc: str) -> str:
    if "gzip" in (enc or "").lower():
        try:
            raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
        except Exception:  # noqa: BLE001
            pass
    return raw.decode("utf-8", "replace")


class R:
    def __init__(self, url, status, text, headers, final, err=""):
        self.url, self.status, self.text = url, status, text
        self.headers, self.final, self.err = headers or {}, final, err

    @property
    def ok(self):
        return 200 <= self.status < 300 and not self.err

    def h(self, name):
        for k, v in self.headers.items():
            if k.lower() == name.lower():
                return v
        return ""

    def cookies(self):
        return [v for k, v in self.headers.items() if k.lower() == "set-cookie"]


def req(url, referer=None, data=None, xhr=False, extra=None, follow=True, timeout=30) -> R:
    headers = dict(HEADERS)
    if referer:
        headers["Referer"] = referer
    if data is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
        headers["Accept"] = "application/json, text/plain, */*"
    if xhr:
        headers.update(XHR)
    if extra:
        headers.update(extra)
    body = data.encode() if isinstance(data, str) else data

    handlers = [urllib.request.HTTPCookieProcessor(JAR)]
    if not follow:
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a, **kw):  # noqa: D401
                return None

        handlers.append(NoRedirect)

    request = urllib.request.Request(url, data=body, headers=headers,
                                     method="POST" if body is not None else "GET")
    try:
        with urllib.request.build_opener(*handlers).open(request, timeout=timeout) as resp:
            return R(url, resp.status, _decode(resp.read(MAX), resp.headers.get("Content-Encoding", "")),
                     dict(resp.headers), resp.geturl())
    except urllib.error.HTTPError as exc:
        hdrs = dict(exc.headers or {})
        return R(url, exc.code, _decode(exc.read(MAX), hdrs.get("Content-Encoding", "")),
                 hdrs, exc.geturl())
    except Exception as exc:  # noqa: BLE001
        return _curl(url, headers, body, follow, exc)


def _curl(url, headers, body, follow, exc) -> R:
    cmd = ["curl", "-sS", "--compressed", "--max-time", "40", "-D", "-"]
    if follow:
        cmd.append("-L")
    for k, v in headers.items():
        if k.lower() in ("user-agent", "accept-encoding"):
            continue
        cmd += ["-H", f"{k}: {v}"]
    cmd += ["-A", UA]
    jars = "; ".join(f"{c.name}={c.value}" for c in JAR)
    if jars:
        cmd += ["-H", f"Cookie: {jars}"]
    if body:
        cmd += ["--data-binary", body.decode("utf-8", "replace") if isinstance(body, bytes) else body]
    cmd.append(url)
    try:
        raw = subprocess.run(cmd, capture_output=True, timeout=90).stdout.decode("utf-8", "replace")
    except Exception:  # noqa: BLE001
        return R(url, 0, "", {}, url, str(exc))
    head, _, text = raw.partition("\r\n\r\n")
    m = re.search(r"^HTTP/[\d.]+ (\d{3})", head, re.M)
    return R(url, int(m.group(1)) if m else 0, text, {}, url)


def media_in(text: str) -> list[str]:
    """Gövdedeki oynatılabilir adresler (m3u8/mp4), öncelik sırasıyla."""
    found: list[str] = []
    for pat in (
        r"""["'](https?://[^"'\\\s]+?\.m3u8[^"'\\\s]*)["']""",
        r"""file\s*:\s*["'](https?://[^"'\\\s]+)["']""",
        r"""["'](https?://[^"'\\\s]+?\.mp4[^"'\\\s]*)["']""",
        r"""(https?://[^"'\\\s]+?\.m3u8[^"'\\\s]*)""",
    ):
        for m in re.findall(pat, text or ""):
            if m not in found:
                found.append(m)
    return found


def playlist_ok(url: str, referer: str) -> tuple[bool, str]:
    r = req(url, referer=referer, extra={"Accept": "*/*", "Sec-Fetch-Dest": "empty"})
    head = (r.text or "")[:80].replace("\n", " ")
    return ((r.text or "").lstrip().startswith("#EXTM3U"), f"{r.status} {len(r.text or '')}B {head!r}")


# ── sağlayıcı çözücüleri ────────────────────────────────────────────────────

def resolve_anizmplayer(url: str, referer: str) -> tuple[str | None, str]:
    m = re.search(r"anizmplayer\.com/(?:video|player|embed)/([A-Za-z0-9_-]{8,64})", url)
    if not m:
        return None, "hash yok"
    h = m.group(1)
    page = req(url, referer=referer)
    log(f"      page {page.status} {len(page.text)}B cookie={[c[:60] for c in page.cookies()][:2]}")
    variants = [
        ("player+origin", "https://anizmplayer.com/player/index.php?data=%s&do=getVideo" % h,
         urllib.parse.urlencode({"hash": h, "r": referer}),
         {"Origin": "https://anizmplayer.com", "Referer": url}),
        ("player+noref", "https://anizmplayer.com/player/index.php?data=%s&do=getVideo" % h,
         urllib.parse.urlencode({"hash": h, "r": "https://anizm.com.tr/"}),
         {"Origin": "https://anizmplayer.com", "Referer": url}),
    ]
    for tag, endpoint, form, extra in variants:
        r = req(endpoint, referer=url, data=form, xhr=True, extra=extra)
        body = (r.text or "").strip()
        log(f"      getVideo[{tag}] → {r.status} {len(body)}B {body[:200]!r}")
        save(f"anizmplayer_getVideo_{tag}.json", body)
        if not body.startswith("{"):
            continue
        try:
            j = json.loads(body)
        except Exception:  # noqa: BLE001
            continue
        for key in ("securedLink", "videoSource", "hls", "source", "file", "url"):
            val = j.get(key)
            if isinstance(val, str) and val.startswith("http"):
                return val, f"getVideo:{key}"
    return None, "getVideo başarısız"


def resolve_sistenn(url: str, referer: str) -> tuple[str | None, str]:
    """Sistenn ailesi: https://<host>/#<kod> + AES-128-CBC /api/v1/video."""
    from base64 import b64decode
    from binascii import unhexlify
    try:
        from Crypto.Cipher import AES  # type: ignore
    except Exception:  # noqa: BLE001
        return None, "pycryptodome yok"
    u = urllib.parse.urlparse(url)
    code = (u.fragment or "").strip()
    if not code:
        return None, "kod yok"
    origin = f"{u.scheme}://{u.netloc}"
    query = urllib.parse.urlencode({"id": code, "w": 1920, "h": 1080, "r": "anizm.com.tr"})
    api = f"{origin}/api/v1/video?{query}"
    r = req(api, referer=f"{origin}/", xhr=True, extra={
        "Origin": origin, "Accept": "*/*", "Sec-Fetch-Site": "same-origin",
        "Sec-Fetch-Mode": "cors", "Sec-Fetch-Dest": "empty",
    })
    body = (r.text or "").strip()
    log(f"      api {r.status} {len(body)}B {body[:160]!r}")
    save(f"sistenn_api_{u.netloc}.txt", body)
    if not body or body.startswith("<"):
        return None, f"api {r.status}"
    try:
        raw = unhexlify(body)
        key = b"kiemtienmua911ca"
        iv = b"1234567890oiuytr"
        cipher = AES.new(key, AES.MODE_CBC, iv)
        plain = cipher.decrypt(raw)
        plain = plain[: -plain[-1]] if plain and 0 < plain[-1] <= 16 else plain
        data = json.loads(plain.decode("utf-8", "replace"))
    except Exception as exc:  # noqa: BLE001
        return None, f"çözülemedi: {exc}"
    save(f"sistenn_json_{u.netloc}.json", json.dumps(data, indent=1, ensure_ascii=False))
    for key in ("cfNative", "cf", "source", "hlsVideoTiktok", "hlsVideoGoogle"):
        val = data.get(key)
        if isinstance(val, str) and val.startswith("http"):
            ok, info = playlist_ok(val, f"{origin}/")
            log(f"      {key} → {info}")
            if ok:
                return val, f"api:{key}"
    return None, "akış anahtarı yok"


def resolve_gdrive(url: str, referer: str) -> tuple[str | None, str]:
    m = re.search(r"/file/d/([A-Za-z0-9_-]{10,})", url) or re.search(r"[?&]id=([A-Za-z0-9_-]{10,})", url)
    if not m:
        return None, "fileId yok"
    fid = m.group(1)
    page = req(f"https://drive.google.com/file/d/{fid}/preview", referer=referer)
    log(f"      preview {page.status} {len(page.text)}B")
    api_key = re.search(r"AIza[0-9A-Za-z_-]{35}", page.text or "")
    if not api_key:
        return None, "AIza anahtarı yok"
    endpoint = (
        f"https://content-workspacevideo-pa.googleapis.com/v1/drive/media/{fid}/playback"
        f"?key={api_key.group(0)}&auditContext=forDisplay"
    )
    r = req(endpoint, referer="https://drive.google.com/", extra={"Accept": "*/*"})
    log(f"      playback {r.status} {len(r.text or '')}B")
    save(f"gdrive_{fid}.json", r.text or "")
    try:
        data = json.loads(r.text or "{}")
    except Exception:  # noqa: BLE001
        return None, "playback JSON değil"
    urls = []

    def walk(node):
        if isinstance(node, dict):
            for k, v in node.items():
                if k == "url" and isinstance(v, str) and v.startswith("http"):
                    urls.append((node.get("height") or node.get("transcodeMetadata", {}).get("height"), v))
                else:
                    walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)

    walk(data)
    if not urls:
        return None, "akış adresi yok"
    urls.sort(key=lambda x: (x[0] or 0), reverse=True)
    return urls[0][1], f"playback:{urls[0][0]}p"


def resolve_generic(url: str, referer: str) -> tuple[str | None, str]:
    """Vidmoly / Voe / Abyss / UQload / HDVid … sayfa gövdesinden akış."""
    r = req(url, referer=referer)
    log(f"      sayfa {r.status} {len(r.text or '')}B final={r.final[:90]}")
    save("generic_" + re.sub(r"[^A-Za-z0-9]+", "_", urllib.parse.urlparse(url).netloc)[:40] + ".html", r.text or "")
    medias = media_in(r.text or "")
    for m in medias[:4]:
        log(f"      aday: {m[:140]}")
    for m in medias:
        if ".m3u8" in m:
            ok, info = playlist_ok(m, url)
            log(f"      m3u8 {info}")
            if ok:
                return m, "html:m3u8"
    if medias:
        return medias[0], "html:mp4"
    # Voe: /e/<id> → JSON içinde source
    if "voe.sx" in url:
        r2 = req(r.final, referer=url)
        for m in media_in(r2.text or ""):
            ok, info = playlist_ok(m, url)
            log(f"      voe2 {info}")
            if ok:
                return m, "voe"
    return None, "akış yok"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default=DEFAULT_EPISODE)
    ap.add_argument("--limit", type=int, default=20)
    args = ap.parse_args()

    base = ""
    for dom in DOMAINS:
        r = req(dom + "/")
        log(f"- {dom}/ → {r.status} {len(r.text)}B")
        if r.ok and len(r.text) > 20000 and "just a moment" not in (r.text or "").lower():
            base = dom
            break
    if not base:
        log("!! taban yok")
        return 1
    log(f"- taban: {base}")

    ep_url = urllib.parse.urljoin(base + "/", args.url)
    ep = req(ep_url, referer=base + "/")
    page = ep.text or ""
    csrf = re.search(r"""<meta[^>]+name=["']csrf-token["'][^>]+content=["']([^"']+)""", page, re.I)
    csrf = csrf.group(1) if csrf else ""
    log(f"- bölüm {ep.status} {len(page)}B csrf={'VAR' if csrf else 'YOK'}")

    sources_urls = list(dict.fromkeys(
        html_mod.unescape(m) for m in re.findall(r"""data-sources=["']([^"']+)["']""", page, re.I)
    ))
    log(f"- çevirmen: {len(sources_urls)}")
    headers_extra = dict(XHR)
    if csrf:
        headers_extra["X-CSRF-TOKEN"] = csrf

    results: list[tuple[str, str, str, str]] = []
    for surl in sources_urls[:3]:
        surl = urllib.parse.urljoin(base + "/", surl)
        r = req(surl, referer=ep_url, extra=headers_extra)
        log(f"\n## çevirmen {surl} → {r.status} {len(r.text or '')}B")
        try:
            data = json.loads(r.text or "{}")
        except Exception:  # noqa: BLE001
            data = {}
        videos = data.get("videos") or []
        log(f"   {len(videos)} video")
        for v in videos[: args.limit]:
            vid, name = str(v.get("id")), str(v.get("name"))
            vurl = re.sub(r"/cevirmen/\d+$", "/video/" + vid, surl)
            vr = req(vurl, referer=ep_url, extra=headers_extra)
            try:
                meta = json.loads(vr.text or "{}")
            except Exception:  # noqa: BLE001
                meta = {}
            player = meta.get("url") or ""
            log(f"\n- [{name}] video={vid} → {vr.status} player={player}")
            if not player:
                log(f"   ! oynatıcı adresi yok: {(vr.text or '')[:160]!r}")
                results.append((name, vid, "", "video JSON yok"))
                continue
            player_url = urllib.parse.urljoin(base + "/", player)
            pr = req(player_url, referer=ep_url, follow=False, extra={
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Sec-Fetch-Mode": "navigate", "Sec-Fetch-Dest": "iframe", "Sec-Fetch-Site": "same-origin",
            })
            target = pr.h("Location") or player_url
            log(f"   /player → {pr.status} → {target}")

            low = target.lower()
            if "anizmplayer.com" in low:
                stream, how = resolve_anizmplayer(target, ep_url)
            elif re.search(r"sistenn|rpmvid|strp2p|uns\.bio", low):
                stream, how = resolve_sistenn(target, ep_url)
            elif "drive.google" in low:
                stream, how = resolve_gdrive(target, ep_url)
            else:
                stream, how = resolve_generic(target, ep_url)

            log(f"   => {how} :: {stream}")
            results.append((name, vid, stream or "", how))

    log("\n## ÖZET")
    ok = sum(1 for r in results if r[2])
    for name, vid, stream, how in results:
        log(f"- {'✓' if stream else '✗'} {name:<22} {how:<24} {stream[:90]}")
    log(f"- çözülen: {ok}/{len(results)}")
    save("resolve-summary.txt", "\n".join(f"{n}\t{v}\t{h}\t{s}" for n, v, s, h in results))
    return 0


if __name__ == "__main__":
    sys.exit(main())
