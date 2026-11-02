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

        # bulunan sözleşmeyle dene: birkaç parametre varyantı
        variants = [
            f"{origin}/api/v1/video?id={code}&w=1920&h=1080&r={urllib.parse.urlparse('https://anizm.com.tr').netloc}",
            f"{origin}/api/v1/video?id={code}&w=1920&h=1080",
            f"{origin}/api/v1/video?id={code}",
            f"{origin}/api/v1/video/{code}",
        ]
        for api in variants:
            st3, out, _, _ = get(api, {
                **HDR,
                "Accept": "*/*",
                "Origin": origin,
                "Referer": origin + "/",
                "Sec-Fetch-Site": "same-origin",
                "Sec-Fetch-Mode": "cors",
                "Sec-Fetch-Dest": "empty",
            })
            log(f"- api {api.replace(origin, '')} → {st3} {len(out)}B {out[:120]!r}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
