#!/usr/bin/env python3
"""Anizm ana sayfa / kategori sayfası ölçüm probu.

Neden: uygulamada her kategori satırı (Aksiyon, Macera, Dram, …) AYNI alfabetik
listeyi gösteriyordu. Bu prob, `/kategoriler/N` ve `/temalar/N` sayfalarının
gerçekte ne döndürdüğünü ve eklentinin kart çözümleyicisinin ne topladığını
ölçer.

Çıktı: research/anizm-probe/kategori.log (+ fixtures)
"""

from __future__ import annotations

import gzip
import io
import re
import sys
import urllib.parse
import urllib.request

BASE = "https://anizm.com.tr"
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
)
OUT = []


def log(line: str = "") -> None:
    OUT.append(line)
    print(line, flush=True)


def fetch(path: str) -> tuple[int, str, str]:
    url = path if path.startswith("http") else BASE + path
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": UA,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
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
            return r.status, raw.decode("utf-8", "replace"), r.geturl()
    except Exception as exc:  # noqa: BLE001
        body = ""
        try:
            body = exc.read().decode("utf-8", "replace")  # type: ignore[attr-defined]
        except Exception:  # noqa: BLE001
            pass
        return getattr(exc, "code", 0), body, url


def title_of(html: str) -> str:
    m = re.search(r"<title[^>]*>(.*?)</title>", html, re.S | re.I)
    return re.sub(r"\s+", " ", m.group(1)).strip() if m else "-"


def count(html: str, needle: str) -> int:
    return html.count(needle)


def card_titles(html: str, limit: int = 10) -> list[str]:
    """Eklentinin gördüğü sırayla kart başlıkları (v3 kart kapsayıcıları)."""
    titles = []
    for m in re.finditer(
        r"<article[^>]*class=\"[^\"]*v3-(?:catalog|series|episode)-card[^\"]*\"(.*?)</article>",
        html,
        re.S | re.I,
    ):
        block = m.group(1)
        t = re.search(r"<h[23][^>]*>\s*(?:<a[^>]*>)?(.*?)(?:</a>)?\s*</h[23]>", block, re.S | re.I)
        if t:
            titles.append(re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", t.group(1))).strip())
        else:
            alt = re.search(r"<img[^>]*alt=\"([^\"]*)\"", block, re.I)
            titles.append(alt.group(1) if alt else "?")
        if len(titles) >= limit:
            break
    return titles


def anchors(html: str, pattern: str, limit: int = 8) -> list[str]:
    out = []
    for m in re.finditer(r"<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", html, re.S | re.I):
        href = m.group(1)
        if not re.search(pattern, href, re.I):
            continue
        text = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", m.group(2))).strip()
        out.append(f"{text or '?'} → {href}")
        if len(out) >= limit:
            break
    return out


def report(path: str) -> None:
    status, html, final = fetch(path)
    log(f"\n### {path}  → {status} ({len(html)}B) final={final}")
    if status != 200 or not html:
        log("  !! içerik yok")
        return
    log(f"  title      : {title_of(html)}")
    for sel, needle in (
        ("article.v3-catalog-card", "v3-catalog-card"),
        ("article.v3-series-card", "v3-series-card"),
        ("article.v3-episode-card", "v3-episode-card"),
        ("a.v3-catalog-poster", "v3-catalog-poster"),
        ("v3-category-card", "v3-category-card"),
        ("v3-poster-rail", "v3-poster-rail"),
        ("v3-alphabet", "v3-alphabet"),
        ("data-v3-deferred-src", "data-v3-deferred-src"),
        ("bolum-izle", "-bolum-izle"),
        ("pagination", "v3-pagination"),
    ):
        log(f"  {sel:<24}: {count(html, needle)}")

    kicker = re.search(
        r"<span class=\"v3-section-kicker\">(.*?)</span>", html, re.S | re.I
    )
    h1 = re.search(r"<h1[^>]*>(.*?)</h1>", html, re.S | re.I)
    def flat(raw: str) -> str:
        return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", raw)).strip()

    if h1:
        log("  h1         : " + flat(h1.group(1))[:90])
    if kicker:
        log("  kicker     : " + flat(kicker.group(1))[:90])

    log("  ilk kartlar:")
    for t in card_titles(html):
        log(f"    • {t[:80]}")
    log("  aktif kategori bağlantısı (aria-current/active):")
    for a in anchors(html, r"kategoriler/\d+|temalar/\d+", 6):
        if "active" in a or "current" in a:
            log(f"    * {a}")
    cur = re.findall(r"<a[^>]*(?:aria-current=\"page\"|class=\"[^\"]*(?:active|is-active)[^\"]*\")[^>]*href=\"([^\"]+)\"", html, re.I)
    log(f"    aria-current/active href: {cur[:6]}")


def main() -> int:
    log("# Anizm kategori / ana sayfa probu")
    log("Amaç: her kategori satırı AYNI listeyi gösteriyordu — hangi URL ne dönüyor?")

    paths = [
        "/",
        "/yeniler",
        "/yeniler?sayfa=1",
        "/yeniler?sayfa=2",
        "/kategoriler",
        "/kategoriler/2",
        "/kategoriler/2?sayfa=1",
        "/kategoriler/2?sayfa=2",
        "/kategoriler/1?sayfa=1",
        "/kategoriler/4?sayfa=1",
        "/kategoriler/3?sayfa=1",
        "/kategoriler/2?page=1",
        "/temalar/3?sayfa=1",
        "/kategoriler/2?sort=populer",
    ]
    for path in paths:
        try:
            report(path)
        except Exception as exc:  # noqa: BLE001
            log(f"\n### {path} → PROB HATASI: {exc}")

    log("\n## Özet")
    log("Her satırın aynı çıkması, kategori URL'sinin filtrelenmediğini gösterir.")

    import os

    out_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "research", "anizm-probe")
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "kategori.log"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(OUT) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
