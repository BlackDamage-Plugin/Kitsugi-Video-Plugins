#!/usr/bin/env python3
"""Anizm ana sayfa bölümleri + kategori kartı ölçümü (v2).

Sorular:
 1. Ana sayfadaki `#populer`, `#son-bolumler`, `#yeni-seriler` bölümlerinde kaç
    kart var, başlıkları ne?
 2. Kategori sayfası kartlarında **puan** (`v3-catalog-score`) var mı? (Varsa
    satırları alfabetik yerine puana göre dizebiliriz.)
 3. Bölüm kartları (`#son-bolumler`) seri adresine mi, bölüme mi gidiyor?
"""

from __future__ import annotations

import gzip
import io
import re
import sys
import urllib.request

BASE = "https://anizm.com.tr"
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
)
OUT: list[str] = []


def log(line: str = "") -> None:
    OUT.append(line)
    print(line, flush=True)


def fetch(path: str) -> tuple[int, str]:
    url = path if path.startswith("http") else BASE + path
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Accept": "text/html,*/*;q=0.8",
            "Accept-Language": "tr-TR,tr;q=0.9",
            "Referer": BASE + "/",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read(8 * 1024 * 1024)
            if "gzip" in (r.headers.get("Content-Encoding") or ""):
                try:
                    raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
                except Exception:  # noqa: BLE001
                    pass
            return r.status, raw.decode("utf-8", "replace")
    except Exception as exc:  # noqa: BLE001
        return getattr(exc, "code", 0), ""


def articles(html: str) -> list[str]:
    return re.findall(r"<article[^>]*class=\"[^\"]*v3-[a-z-]*card[^\"]*\"(.*?)</article>", html, re.S | re.I)


def title_of(block: str) -> str:
    t = re.search(r"<h[23][^>]*>\s*(?:<a[^>]*>)?(.*?)(?:</a>)?\s*</h[23]>", block, re.S | re.I)
    if t:
        return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", t.group(1))).strip()
    alt = re.search(r"<img[^>]*alt=\"([^\"]*)\"", block, re.I)
    return alt.group(1) if alt else "?"


def href_of(block: str) -> str:
    m = re.search(r"href=\"([^\"]+)\"", block, re.I)
    return m.group(1) if m else "?"


def section(html: str, section_id: str) -> str:
    m = re.search(
        r"<section[^>]*id=\"" + section_id + r"\"(.*?)</section>", html, re.S | re.I
    )
    return m.group(1) if m else ""


def main() -> int:
    log("# Anizm ana sayfa / kategori ölçümü (v2)")

    status, home = fetch("/")
    log(f"\n## / → {status} ({len(home)}B)")
    for sid in ("populer", "son-bolumler", "yeni-seriler", "turler"):
        body = section(home, sid)
        blocks = articles(body)
        log(f"\n### section#{sid}: {len(blocks)} kart (ham {len(body)}B)")
        log(f"  'v3-catalog-score' sayısı: {body.count('v3-catalog-score')}")
        for b in blocks[:8]:
            score = re.search(r"v3-catalog-score[^>]*>.*?([\d.]+)", b, re.S)
            log(
                "    • "
                + title_of(b)[:60]
                + " | puan="
                + (score.group(1) if score else "-")
                + " | "
                + href_of(b)[:80]
            )
        if sid == "son-bolumler" and blocks:
            log("  ilk kart HTML (kırpılmış):")
            log("    " + re.sub(r"\s+", " ", blocks[0])[:900])

    status, kat = fetch("/kategoriler/2?sayfa=1")
    blocks = articles(kat)
    log(f"\n## /kategoriler/2?sayfa=1 → {status} ({len(kat)}B), {len(blocks)} kart")
    log(f"  'v3-catalog-score': {kat.count('v3-catalog-score')}, 'data-score': {kat.count('data-score')}")
    for b in blocks[:6]:
        score = re.search(r"v3-catalog-score[^>]*>.*?([\d.]+)", b, re.S)
        log(f"    • {title_of(b)[:60]} | puan={score.group(1) if score else '-'} | {href_of(b)[:70]}")
    if blocks:
        log("  kart HTML örneği:")
        log("    " + re.sub(r"\s+", " ", blocks[0])[:900])

    import os

    out_dir = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "research",
        "anizm-probe",
    )
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "anasayfa.log"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(OUT) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
