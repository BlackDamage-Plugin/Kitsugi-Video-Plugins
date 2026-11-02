#!/usr/bin/env python3
"""Anizm **+18 kapısı + kaynak zinciri** probu (salt-okunur, canlı site).

Neden var: anizm.net, +18 işaretli yapıların bölüm sayfasında video kaynaklarını
*çerez/onay olmadan* sunmuyor ("Video kaynaklarını yüklemek için +18 uyarısını
kabul edin."). Eklentinin bu kapıyı doğru aşabilmesi için kapının **gerçek**
mekanizması (hangi çerez / hangi uç / hangi HTML işareti) ölçülmeli.

Bu betik sırayla:

  1. alan adlarını dener (anizm.net / anizm.com.tr / puffytr …) ve Cloudflare
     duvarını raporlar,
  2. bölüm sayfasını **çerez kavanozu** ile çeker; `Set-Cookie` başlıklarını ve
     kavanozun son hâlini yazar,
  3. +18 kapısının HTML'ini (etrafındaki pencere), formları/girdileri ve
     `data-*` işaretlerini döker,
  4. sayfadaki TÜM `<script src>` dosyalarını indirir ve içlerinde yaş kapısı /
     çerez / çevirmen / video uçlarını arar,
  5. kapıyı **kabul etme** adaylarını (JS'ten ve formlardan çıkan uçlar) sırayla
     dener, her denemeden sonra bölüm sayfasını tekrar çeker ve kaynak listesinin
     açılıp açılmadığını karşılaştırır,
  6. açıldıysa çevirmen → video → `/player/<id>` zincirini yürür.

Çıktı yalnızca günlüktür (PR/CI günlüğünde okunur). Kullanım:

    python3 tools/anizm_gate_probe.py
    python3 tools/anizm_gate_probe.py --url https://anizm.net/one-piece-1-bolum-izle
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
BASE_HEADERS = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8",
    "Accept-Encoding": "gzip",
    "Upgrade-Insecure-Requests": "1",
}
DOMAINS = ["https://anizm.net", "https://anizm.com.tr", "https://www.puffytr.com"]
DEFAULT_EPISODE = (
    "/shingeki-no-kyojin-movie-kanketsu-hen-the-last-attack-"
    "shingeki-no-kyojin-movie-kanketsu-hen---the-last-attack-bolum-izle"
)
MAX_BYTES = 12 * 1024 * 1024
JAR = http.cookiejar.CookieJar()

LOG_LINES: list[str] = []
GATE_HINTS = (
    "+18", "18+", "yaş sınırı", "yas siniri", "yaş uyarısı", "yas uyarisi",
    "age-gate", "agegate", "age_gate", "age-verified", "age_verified", "ageverified",
    "yetiskin", "yetişkin", "18_yas", "onay", "consent", "nsfw", "r18",
)


def log(line: str = "") -> None:
    print(line, flush=True)
    LOG_LINES.append(line)


def _decode(raw: bytes, encoding: str) -> str:
    if "gzip" in (encoding or "").lower():
        try:
            raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
        except Exception:  # noqa: BLE001
            pass
    return raw.decode("utf-8", "replace")


class Res:
    def __init__(self, url: str, status: int, text: str, headers: dict[str, str], final: str, via: str):
        self.url, self.status, self.text = url, status, text
        self.headers, self.final, self.via = headers, final, via

    @property
    def ok(self) -> bool:
        return 200 <= self.status < 300

    def header(self, name: str) -> str:
        for k, v in (self.headers or {}).items():
            if k.lower() == name.lower():
                return v
        return ""

    def set_cookies(self) -> list[str]:
        out = []
        for k, v in (self.headers or {}).items():
            if k.lower() == "set-cookie":
                out.append(v)
        return out


def _opener(follow: bool):
    handlers: list = [urllib.request.HTTPCookieProcessor(JAR)]
    if not follow:
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a, **kw):  # noqa: D401
                return None

        handlers.append(NoRedirect)
    return urllib.request.build_opener(*handlers)


def req(
    url: str,
    referer: str | None = None,
    data: str | bytes | None = None,
    xhr: bool = False,
    json_body: bool = False,
    extra: dict[str, str] | None = None,
    follow: bool = True,
    timeout: int = 30,
) -> Res:
    headers = dict(BASE_HEADERS)
    if referer:
        headers["Referer"] = referer
    if data is not None:
        headers["Content-Type"] = (
            "application/json; charset=UTF-8" if json_body
            else "application/x-www-form-urlencoded; charset=UTF-8"
        )
        headers["Accept"] = "application/json, text/plain, */*"
    if xhr:
        headers["X-Requested-With"] = "XMLHttpRequest"
        headers["Sec-Fetch-Mode"] = "cors"
        headers["Sec-Fetch-Dest"] = "empty"
        headers["Sec-Fetch-Site"] = "same-origin"
    if extra:
        headers.update(extra)

    body = data.encode() if isinstance(data, str) else data
    r = urllib.request.Request(url, data=body, headers=headers, method="POST" if body is not None else "GET")
    try:
        with _opener(follow).open(r, timeout=timeout) as resp:
            return Res(url, resp.status, _decode(resp.read(MAX_BYTES), resp.headers.get("Content-Encoding", "")),
                       dict(resp.headers), resp.geturl(), "urllib")
    except urllib.error.HTTPError as exc:
        hdrs = dict(exc.headers or {})
        return Res(url, exc.code, _decode(exc.read(MAX_BYTES), hdrs.get("Content-Encoding", "")),
                   hdrs, exc.geturl(), "urllib")
    except Exception as exc:  # noqa: BLE001
        return _curl_req(url, referer, body, headers, follow, exc)


def _curl_req(url, referer, body, headers, follow, exc) -> Res:
    """urllib TLS'te düşerse curl'e (varsa) düşer; çerezler curl'e taşınmaz."""
    cmd = ["curl", "-sS", "--compressed", "--max-time", "40", "-A", UA, "-D", "-"]
    if follow:
        cmd.append("-L")
    if referer:
        cmd += ["-e", referer]
    for k, v in headers.items():
        if k.lower() in ("user-agent", "accept-encoding"):
            continue
        cmd += ["-H", f"{k}: {v}"]
    jars = "; ".join(f"{c.name}={c.value}" for c in JAR)
    if jars:
        cmd += ["-H", f"Cookie: {jars}"]
    if body is not None:
        cmd += ["--data-binary", body.decode("utf-8", "replace") if isinstance(body, bytes) else body]
    cmd.append(url)
    try:
        out = subprocess.run(cmd, capture_output=True, timeout=90).stdout
    except Exception:  # noqa: BLE001
        return Res(url, 0, "", {}, url, f"curl-yok:{exc}")
    raw = out.decode("utf-8", "replace")
    head, _, text = raw.partition("\r\n\r\n")
    status = 0
    m = re.search(r"^HTTP/[\d.]+ (\d{3})", head, re.M)
    if m:
        status = int(m.group(1))
    hdrs = {}
    for line in head.splitlines():
        if ":" in line and not line.startswith("HTTP/"):
            k, v = line.split(":", 1)
            hdrs.setdefault(k.strip(), v.strip())
    return Res(url, status, text, hdrs, url, "curl")



OUT_DIR = "research/anizm-probe"


def save(name: str, text: str) -> None:
    import os
    os.makedirs(OUT_DIR, exist_ok=True)
    try:
        with open(os.path.join(OUT_DIR, name), "w", encoding="utf-8") as fh:
            fh.write(text or "")
        log(f"    (kaydedildi: {OUT_DIR}/{name} {len(text or '')}B)")
    except Exception as exc:  # noqa: BLE001
        log(f"    ! kaydedilemedi {name}: {exc}")


def jar_state() -> str:
    items = [f"{c.domain}|{c.name}={c.value[:40]} (exp={c.expires})" for c in JAR]
    return ", ".join(items) if items else "(boş)"


def looks_blocked(text: str) -> bool:
    low = (text or "").lower()
    return ("just a moment" in low and "challenge-platform" in low) or "cf-browser-verification" in low


def title_of(html: str) -> str:
    m = re.search(r"<title[^>]*>(.*?)</title>", html or "", re.S | re.I)
    return html_mod.unescape(m.group(1)).strip()[:90] if m else "-"


def window(text: str, needle: str, before: int = 400, after: int = 2600) -> str:
    i = text.find(needle)
    if i < 0:
        return ""
    return text[max(0, i - before): i + after]


def scripts_of(html: str, base: str) -> list[str]:
    out, seen = [], set()
    for u in re.findall(r"""<script[^>]+src=["']([^"']+)["']""", html or "", re.I):
        full = urllib.parse.urljoin(base, html_mod.unescape(u))
        if full not in seen:
            seen.add(full)
            out.append(full)
    return out


def count(html: str, needle: str) -> int:
    return (html or "").count(needle)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default=DEFAULT_EPISODE, help="bölüm yolu (tam adres ya da /yol)")
    ap.add_argument("--query", default="one piece", help="arama sorgusu (yedek bölüm bulmak için)")
    args = ap.parse_args()

    log("# Anizm +18 kapısı + kaynak zinciri probu")
    log(f"- UA: {UA}")

    # ── 1) alan adı ────────────────────────────────────────────────────────
    log("\n## 1) Alan adı erişimi (çerez kavanozu ile)")
    base = ""
    for dom in DOMAINS:
        r = req(dom + "/", referer=None)
        log(f"- {dom}/ → {r.status} {len(r.text)}B başlık={title_of(r.text)!r} cf={looks_blocked(r.text)} via={r.via}")
        for c in r.set_cookies():
            log(f"    set-cookie: {c[:160]}")
        if r.ok and len(r.text) > 20000 and not looks_blocked(r.text) and not base:
            base = dom
    log(f"- seçilen taban: {base or 'YOK'}")
    log(f"- kavanoz: {jar_state()}")
    if not base:
        log("!! hiçbir alan adı açılmadı — prob durdu")
        return 1

    ep_url = urllib.parse.urljoin(base + "/", args.url)
    log(f"- bölüm adresi: {ep_url}")

    # ── 2) bölüm sayfası (kapı ile) ────────────────────────────────────────
    log("\n## 2) Bölüm sayfası (onay öncesi)")
    ep = req(ep_url, referer=base + "/")
    page = ep.text
    save("episode.html", page)
    log(f"- {ep.status} {len(page)}B başlık={title_of(page)!r} final={ep.final}")
    for c in ep.set_cookies():
        log(f"    set-cookie: {c[:160]}")
    log(f"- kavanoz: {jar_state()}")

    markers = [
        "data-translatorclick", "data-playerclick", "episodePlayerContent", "episodePlayers",
        "translatorCompactBox", "data-sources", "data-v3-translator", "csrf-token",
        "Video kaynaklarını yüklemek", "+18", "Yaş sınırı uyarısı", "yasKontrol", "age-gate",
        "agegate", "onay", "cerez", "cookie", "localStorage", "section.v3-hero", "v3-",
    ]
    log("- HTML işaretleri:")
    for m in markers:
        log(f"    {m!r}: {count(page, m)}")

    forms = re.findall(r"<form\b[^>]*>[\s\S]{0,1200}?</form>", page, re.I)
    log(f"- form sayısı: {len(forms)}")
    for i, f in enumerate(forms[:6]):
        action = re.search(r"""action=["']([^"']*)["']""", f, re.I)
        method = re.search(r"""method=["']([^"']*)["']""", f, re.I)
        inputs = re.findall(r"""<input\b[^>]*>""", f, re.I)
        log(f"    form[{i}] method={method.group(1) if method else '?'} action={(action.group(1) if action else '?')[:120]}")
        for inp in inputs[:8]:
            log(f"        {inp[:200]}")

    log("- kapı penceresi (HTML):")
    for needle in ("Yaş sınırı uyarısı", "Video kaynaklarını yüklemek", "+18"):
        chunk = window(page, needle, 600, 3000)
        if chunk:
            log(f"--- {needle} ---\n{chunk}\n--- /{needle} ---")
            break

    log("- çevirmen düğmeleri (ham):")
    for tag in re.findall(r"<[a-z]+[^>]*data-translatorclick[^>]*>", page, re.I)[:8]:
        log(f"    {tag[:400]}")
    for tag in re.findall(r"<[a-z]+[^>]*(?:data-sources|data-v3-translator)[^>]*>", page, re.I)[:6]:
        log(f"    {tag[:400]}")

    # ── 2.5) arama / detay HTML'i (çözümleyici doğrulaması için) ───────────
    log("\n## 2.5) Arama ve detay HTML'i")
    search = req(f"{base}/arama?q=one+piece", referer=base + "/")
    save("search.html", search.text)
    log(f"- /arama?q=one+piece → {search.status} {len(search.text)}B")
    for sel in ("v3-catalog-card", "v3-series-card", "v3-episode-card", "poster", "<article"):
        log(f"    {sel!r}: {count(search.text, sel)}")
    api = req(f"{base}/searchAnime?query=one%20piece&page=1&type=detailed&limit=10", referer=base + "/",
              xhr=True, extra={"Accept": "application/json, text/plain, */*"})
    save("searchAnime.json", api.text)
    log(f"- /searchAnime → {api.status} {len(api.text)}B ilk={(api.text or '')[:160]!r}")

    detail = req(f"{base}/shingeki-no-kyojin-movie-kanketsu-hen-the-last-attack", referer=base + "/")
    save("detail.html", detail.text)
    log(f"- detay → {detail.status} {len(detail.text)}B")
    for sel in ("v3-anime", "v3-episode", "data-episode", "bolum-izle", "episodeLine",
                "v3-detail", "v3-hero", "data-anime-id"):
        log(f"    {sel!r}: {count(detail.text, sel)}")
    eps = re.findall(r"""href=["']([^"']*-bolum[^"']*)["']""", detail.text, re.I)
    log(f"    bölüm bağlantısı: {len(eps)} → {list(dict.fromkeys(eps))[:5]}")

    # ── 3) script dosyaları ve yaş kapısı mantığı ──────────────────────────
    log("\n## 3) Script dosyaları")
    scripts = scripts_of(page, base)
    log(f"- {len(scripts)} script: " + ", ".join(s.rsplit('/', 1)[-1] for s in scripts))

    kw = re.compile(r"\b18\b|ya[şs]|age[-_]?gate|age_?verif|consent|onay|nsfw|r18|cookie|localStorage|sessionStorage",
                    re.I)
    endpoints: set[str] = set()
    for s in scripts[:24]:
        r = req(s, referer=ep_url)
        body = r.text or ""
        if re.search(r"content-warning|v3-episode", s, re.I):
            save("js__" + re.sub(r"[^A-Za-z0-9.]+", "_", s.rsplit("/", 1)[-1])[:60], body)
        log(f"\n- {s.rsplit('/', 1)[-1]} → {r.status} {len(body)}B")
        if re.search(r"content-warning", s, re.I):
            log(f"    --- TAM DOSYA ({len(body)}B) ---")
            for chunk in body.splitlines():
                log(f"      » {chunk[:400]}")
            log("    --- /TAM DOSYA ---")
        hits = [ln.strip() for ln in body.splitlines() if kw.search(ln)]
        if hits:
            log(f"    yaş/çerez satırı: {len(hits)}")
            for ln in hits[:24]:
                log(f"      | {ln[:300]}")
        for u in re.findall(r"""["'](/(?:[a-z0-9_\-]+/?){1,4})["']""", body, re.I):
            if re.search(r"yas|age|onay|consent|18|cevirmen|bolum|video|player|cookie", u, re.I):
                endpoints.add(u)
        for u in re.findall(r"""(?:url|href|action)\s*[:=]\s*["']([^"']{3,120})["']""", body, re.I):
            if re.search(r"yas|age|onay|consent|18|cookie", u, re.I):
                endpoints.add(u)

    log("\n- JS'ten çıkan aday uçlar:")
    for u in sorted(endpoints)[:60]:
        log(f"    {u}")

    csrf_m = re.search(r"""<meta[^>]+name=["']csrf-token["'][^>]+content=["']([^"']+)""", page, re.I)
    csrf = csrf_m.group(1) if csrf_m else ""
    log(f"- csrf-token: {'VAR (' + csrf[:12] + '…)' if csrf else 'YOK'}")

    def gate_open(html: str) -> tuple[int, int]:
        return (count(html, "data-translatorclick") + count(html, "data-sources"),
                count(html, "Yaş sınırı uyarısı") + count(html, "Video kaynaklarını yüklemek"))

    before = gate_open(page)
    log(f"- kapı öncesi (kaynak işareti, kapı işareti): {before}")

    # ── 4) kapıyı aşma denemeleri ──────────────────────────────────────────
    # 2026-11-02 ölçümû: kapı TAMAMEN istemci tarafında (sessionStorage/localStorage,
    # v3-content-warning.js). Sunucuda çerez yok; bu yüzden HTTP ile "kabul" yok.
    # Aşağıdaki denemeler yalnızca sunucu tarafı bir kapı varsa yakalar.
    log("\n## 4) Kapıyı aşma denemeleri (sunucu tarafı kapı var mı?)")
    for path in ("/18-yas-onay", "/yas-onay", "/18"):
        r = req(base + path, referer=ep_url, follow=False)
        log(f"- GET {path} → {r.status} loc={r.header('Location')[:80]!r}")

    # ── 5) kaynak zinciri (ölçülen sözleşme) ──────────────────────────────
    # data-sources  → {success, videos:[{id,name,watchTogether}]}
    # .../video/<id> → {success, url:"/player/<id>", source, playerOrigin, watchTogether}
    # /player/<id>   → 302 → sağlayıcı sayfası
    log("\n## 5) Kaynak zinciri")
    translators = []
    for pat in (r"""data-sources=["']([^"']+)["']""",
                r"""data-translatorclick=["']([^"']+)["']""",
                r"""translator=["']([^"']+)["']"""):
        for m in re.finditer(pat, page, re.I):
            translators.append(html_mod.unescape(m.group(1)))
    translators = list(dict.fromkeys(translators))
    log(f"- çevirmen yolu: {len(translators)} → {translators[:5]}")

    xhr_headers = {
        "Accept": "application/json",
        "X-Requested-With": "XMLHttpRequest",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Dest": "empty",
        "Sec-Fetch-Site": "same-origin",
    }
    if csrf:
        xhr_headers["X-CSRF-TOKEN"] = csrf

    videos: list[tuple[str, str, str]] = []   # (fansub, host, sourcesUrl+id)
    for i, t in enumerate(translators[:4]):
        turl = urllib.parse.urljoin(base + "/", html_mod.unescape(t))
        r = req(turl, referer=ep_url, extra=xhr_headers)
        log(f"  - çevirmen[{i}] {turl} → {r.status} {len(r.text)}B")
        try:
            data = json.loads(r.text or "{}")
        except Exception:  # noqa: BLE001
            data = {}
        for v in (data.get("videos") or []):
            vid = str(v.get("id"))
            videos.append((turl.rsplit("/", 2)[-2] if "/cevirmen/" in turl else "?", str(v.get("name")), vid))
            log(f"      · video id={vid} ad={v.get('name')} watchTogether={v.get('watchTogether')}")
        log(f"      · toplam {len(data.get('videos') or [])} video")

    final_targets: list[tuple[str, str]] = []
    for fansub, host, vid in videos[:20]:
        surl = next((t for t in translators if f"/cevirmen/{fansub}" in t), translators[0] if translators else "")
        if not surl:
            continue
        vurl = re.sub(r"/cevirmen/\d+$", "/video/" + vid, urllib.parse.urljoin(base + "/", surl))
        r = req(vurl, referer=ep_url, extra=xhr_headers)
        body = (r.text or "").strip()
        log(f"  - video {vid} ({host}) → {r.status} {len(body)}B")
        for line in body.splitlines()[:6]:
            log(f"      » {line[:300]}")
        try:
            j = json.loads(body)
        except Exception:  # noqa: BLE001
            j = {}
        if isinstance(j, dict) and j.get("url"):
            player_url = urllib.parse.urljoin(base + "/", j["url"])
            log(f"      player={player_url} source={j.get('source')} origin={j.get('playerOrigin')}")
            pr = req(player_url, referer=ep_url, follow=False, extra={
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Sec-Fetch-Mode": "navigate", "Sec-Fetch-Dest": "iframe", "Sec-Fetch-Site": "same-origin",
            })
            loc = pr.header("Location")
            log(f"      /player → {pr.status} location={loc[:220]!r} bytes={len(pr.text)}")
            final_targets.append((host, loc or player_url))

    # ── 6) sağlayıcı sayfaları ─────────────────────────────────────────────
    log("\n## 6) Sağlayıcı sayfaları (son hedefler)")
    for host, target in final_targets[:14]:
        if not target.startswith("http"):
            continue
        r = req(target, referer=ep_url, follow=True)
        body = r.text or ""
        log(f"- [{host}] {target[:120]} → {r.status} {len(body)}B başlık={title_of(body)!r} final={r.final[:120]}")
        media = sorted(set(re.findall(r"""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""", body)))[:6]
        frames = re.findall(r"""<iframe[^>]+src=["']([^"']+)["']""", body, re.I)[:4]
        scripts = re.findall(r"""<script[^>]+src=["']([^"']+)["']""", body, re.I)[:6]
        for m in media:
            log(f"    medya: {m[:200]}")
        for f in frames:
            log(f"    iframe: {f[:200]}")
        for sc in scripts:
            log(f"    script: {sc[:160]}")
        for key in ("masterUrl", "master.txt", "native.m3u8", "securedLink", "videoSource",
                    "file:", "source:", "sources:", ".m3u8", "/api/v1/video", "videoId"):
            idx = body.find(key)
            if idx >= 0:
                log(f"    ipucu {key!r}: …{body[max(0, idx - 120):idx + 220].replace(chr(10), ' ')[:340]}")

    log("\n## 7) kavanozun son hâli")
    log(f"- {jar_state()}")
    log(f"- toplam günlük satırı: {len(LOG_LINES)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
