#!/usr/bin/env python3
"""Sistenn ailesi oynatıcı sözleşmesini oynatıcının KENDİ JS paketinden okur.

`/player/<id>` → 302 → `https://<host>/#<kod>` sayfası yalnızca bir kabuk; akış
`/api/v1/video?...` ucundan AES-128-CBC ile şifreli geliyor. Parametreler ve
başlıklar sürümden sürüme değiştiği için bu betik **paketin içinden** doğrular:
script dosyalarını indirir, `/api/v1/video` geçen yeri ve istek başlıklarını
yazdırır, ardından bulunan sözleşmeyle API'yi dener.
"""

from __future__ import annotations

import gzip
import io
import json
import re
import sys
import http.cookiejar
import urllib.error
import urllib.parse
import urllib.request

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36")
HDR = {"User-Agent": UA, "Accept": "*/*", "Accept-Language": "tr-TR,tr;q=0.9"}
HTML_HDR = {"User-Agent": UA, "Accept": "text/html,application/xhtml+xml,*/*;q=0.8",
            "Accept-Language": "tr-TR,tr;q=0.9", "Accept-Encoding": "gzip"}
OUT = "research/anizm-probe"


def log(x=""):
    print(x, flush=True)


def get(url, headers=None, timeout=30):
    req = urllib.request.Request(url, headers=headers or HDR)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read(12 * 1024 * 1024)
            if "gzip" in (r.headers.get("Content-Encoding") or ""):
                try:
                    raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
                except Exception:  # noqa: BLE001
                    pass
            return r.status, raw.decode("utf-8", "replace"), dict(r.headers), r.geturl()
    except Exception as exc:  # noqa: BLE001
        code = getattr(exc, "code", 0)
        body = ""
        try:
            body = exc.read().decode("utf-8", "replace")  # type: ignore[attr-defined]
        except Exception:  # noqa: BLE001
            pass
        return code, body, {}, url



_JAR = http.cookiejar.CookieJar()


def _open(url, headers, timeout=30):
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(_JAR))
    request = urllib.request.Request(url, headers=headers)
    try:
        with opener.open(request, timeout=timeout) as r:
            raw = r.read(12 * 1024 * 1024)
            if "gzip" in (r.headers.get("Content-Encoding") or ""):
                try:
                    raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
                except Exception:  # noqa: BLE001
                    pass
            return r.status, raw.decode("utf-8", "replace"), dict(r.headers), r.geturl()
    except Exception as exc:  # noqa: BLE001
        body = ""
        try:
            body = exc.read().decode("utf-8", "replace")  # type: ignore[attr-defined]
        except Exception:  # noqa: BLE001
            pass
        return getattr(exc, "code", 0), body, {}, url


def get_origin_page(origin, headers):
    return _open(origin + "/", headers)


def get_with_jar(url, headers):
    return _open(url, headers)


def save(name, text):
    import os
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as fh:
        fh.write(text or "")


def main() -> int:
    targets = sys.argv[1:] or [
        "https://sistenn.uns.bio/#epccep",
        "https://anizm.rpmvid.com/#8e1lx",
        "https://anizm.strp2p.com/#nkwqg",
    ]
    for target in targets:
        u = urllib.parse.urlparse(target)
        origin = f"{u.scheme}://{u.netloc}"
        code = u.fragment
        log(f"\n## {origin} kod={code!r}")
        st, body, hdrs, final = get(origin + "/", HTML_HDR)
        log(f"- sayfa {st} {len(body)}B final={final}")
        save(f"sistenn_page_{u.netloc}.html", body)
        scripts = re.findall(r"""<script[^>]+src=["']([^"']+)["']""", body, re.I)
        log(f"- script: {scripts}")

        for src in scripts:
            full = urllib.parse.urljoin(origin + "/", src)
            st2, js, _, _ = get(full)
            log(f"- {full} → {st2} {len(js)}B")
            if not js:
                continue
            save("sistenn_js_" + re.sub(r"[^A-Za-z0-9]+", "_", full.rsplit("/", 1)[-1])[:50] + ".js", js)
            for kw in ("/api/v1/video", "api/v1", "playerId", "player is not recognized",
                       "cfNative", "hlsmod", "x-api", "X-Player", "w=", "&h=", "capacityToken",
                       "sec-fetch", "credentials"):
                for m in list(re.finditer(re.escape(kw), js))[:3]:
                    snippet = js[max(0, m.start() - 320): m.start() + 380].replace("\n", " ")
                    log(f"    [{kw}] …{snippet}…")

        # ── sözleşme denemeleri (başlık/çerez matrisi) ─────────────────
        ep_comtr = ("https://anizm.com.tr/shingeki-no-kyojin-movie-kanketsu-hen-the-last-attack-"
                    "shingeki-no-kyojin-movie-kanketsu-hen---the-last-attack-bolum-izle")
        ep_net = ep_comtr.replace("anizm.com.tr", "anizm.net")
        jar_headers = {"User-Agent": UA, "Accept": "*/*", "Accept-Language": "tr-TR,tr;q=0.9"}

        page_headers = {**HTML_HDR, "Referer": ep_comtr}
        st_p, page_body, ph, _ = get_origin_page(origin, page_headers)
        log(f"- sayfa (jar) {st_p} set-cookie={ph.get('Set-Cookie', '')[:120]!r}")

        variants = [
            ("A referer=ep arsiv", ep_comtr, {"Accept": "*/*", "Accept-Language": "tr-TR,tr;q=0.9"}),
            ("B referer=anizm.net ep", ep_net, {"Accept": "*/*", "Accept-Language": "tr-TR,tr;q=0.9"}),
            ("C origin=anizm.net site", ep_net, {"Accept": "*/*", "Origin": "https://anizm.net"}),
            ("D xhr+json", ep_comtr, {"Accept": "application/json", "X-Requested-With": "XMLHttpRequest"}),
            ("E referer=sistenn page", origin + "/", {"Accept": "*/*", "Sec-Fetch-Site": "same-origin"}),
        ]
        api_params = [
            ("plain", ""),
            ("subsapi", "&subs=0&api=1"),
            ("player", "&player=" + code),
        ]
        for tag, ref, extra in variants:
            for ptag, extra_params in api_params:
                api = f"{origin}/api/v1/video?id={code}&w=1920&h=1080&r={urllib.parse.quote(ref, safe='')}{extra_params}"
                st3, out, hdrs3, _ = get_with_jar(api, {**jar_headers, "Referer": ref, **extra})
                log(f"- [{tag}/{ptag}] → {st3} {len(out)}B {out[:120]!r}")
                if st3 == 200 and out and not out.startswith("<"):
                    save(f"sistenn_ok_{u.netloc}.txt", out)
                    break
    return 0


if __name__ == "__main__":
    sys.exit(main())
