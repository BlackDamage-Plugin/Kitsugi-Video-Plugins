#!/usr/bin/env python3
"""FilmMakinesi Close and Rapid fetch diagnostics for GitHub Actions.

Uses the same access layers already in this repo (direct, public HTML
proxies, Wayback) plus the Turkey-listed proxy file from GitHub. Does not
download media, store cookies, or print stream URLs.
"""
from __future__ import annotations

import base64
import concurrent.futures
import ipaddress
import json
import os
import re
import subprocess
import threading
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from html.parser import HTMLParser

UA_CHROME = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)
UA_SHORT = "Mozilla/5.0"
REFERER = "https://www.hdfilmcehennemi.nl/dizi/supernatural/sezon-9/bolum-15-hd1/"

# FilmMakinesi Close (aynı CloseLoad ailesi) + HD Close doğrulama.
PAGES = {
    "fm-close": "https://closeload.filmmakinesi.to/video/embed/RFeVmBTmaCG/?imdb_id=tt0460681",
    "fm-episode": "https://filmmakinesi.to/dizi/supernatural-2005-fm8t9q/sezon-1/bolum-1/",
    "hd-close": "https://hdfilmcehennemi.mobi/video/embed/RFeVmBTmaCG/?rapidrame_id=6d4mkrakdi1a",
}
REFERERS = {
    "fm-close": "https://filmmakinesi.to/dizi/supernatural-2005-fm8t9q/sezon-1/bolum-1/",
    "fm-episode": "https://filmmakinesi.to/",
    "hd-close": "https://www.hdfilmcehennemi.nl/dizi/supernatural/sezon-1/bolum-1-hd11/",
}

GEO_MARKERS = (
    "content unavailable in your region",
    "unavailable for legal reasons",
    "bu içerik bölgenizde kullanılamıyor",
)
BLOCK_MARKERS = (
    "just a moment",
    "cf-browser-verification",
    "cf-error-details",
    "attention required",
    "access denied",
)


class _Markup(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.data_v: list[str] = []
        self.ids: list[str] = []
        self.video_urls: list[str] = []
        self.iframes: list[str] = []
        self.title_bits: list[str] = []
        self._title = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = {k.lower(): (v or "") for k, v in attrs}
        if "data-v" in values:
            self.data_v.append(values["data-v"])
        if "id" in values:
            self.ids.append(values["id"])
        if "data-video_url" in values:
            self.video_urls.append(values["data-video_url"])
        if tag == "iframe":
            self.iframes.append(values.get("data-src") or values.get("src") or "")
        if tag == "title":
            self._title = True

    def handle_data(self, data: str) -> None:
        if self._title:
            self.title_bits.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag == "title":
            self._title = False


def host_of(url: str) -> str:
    try:
        return (urllib.parse.urlsplit(url).hostname or "").lower()
    except Exception:
        return ""


def shape_url(url: str) -> str:
    try:
        parsed = urllib.parse.urlsplit(url)
        host = (parsed.hostname or "unknown").lower()
        path = parsed.path or "/"
        path = re.sub(r"/embed-[A-Za-z0-9]+/?", "/embed-<id>/", path)
        path = re.sub(r"/video/embed/[A-Za-z0-9]+", "/video/embed/<id>", path)
        path = re.sub(r"/rplayer/[A-Za-z0-9]+", "/rplayer/<id>", path)
        return host + path
    except Exception:
        return "<invalid>"


def analyze(html: str, name: str) -> dict:
    low = html.lower()
    parser = _Markup()
    try:
        parser.feed(html)
    except Exception:
        pass
    title = "".join(parser.title_bits).strip()[:120]
    kind = "html"
    if any(marker in low for marker in GEO_MARKERS):
        kind = "geo"
    elif any(marker in low for marker in BLOCK_MARKERS):
        kind = "challenge"
    elif name.startswith("hd-s") and ("data-video" in low or "alternative-link" in low):
        kind = "episode"
    elif name.startswith("fm-episode") and "data-video_url" in low:
        kind = "episode"
    elif "jwplayer" in low and "sources" in low:
        kind = "player"
    hosts = sorted(
        {
            host_of(url)
            for url in parser.video_urls + parser.iframes
            if host_of(url)
        }
    )
    return {
        "kind": kind,
        "bytes": len(html),
        "title": title,
        "jwplayer": low.count("jwplayer"),
        "sources": low.count("sources"),
        "eval_packer": html.count("eval(function"),
        "querySelector_data_v": "[data-v]" in html,
        "getElementById": "getElementById" in html,
        "location_hostname": "location.hostname" in html,
        "data_v_count": len(parser.data_v),
        "data_v_values": parser.data_v[:8],
        "ids": parser.ids[:12],
        "embed_hosts": hosts,
        "trap_contentUrl": "filmakinesimp4" in low or "playmix.uno" in low,
        "commented_file_link": "atob(file_link)" in low,
    }


def valid_body(name: str, status: str, text: str) -> bool:
    if status != "200" or len(text) < 400:
        return False
    low = text.lower()
    if any(marker in low for marker in GEO_MARKERS + BLOCK_MARKERS):
        return False
    if name.startswith("hd-s"):
        return ("data-video" in low or "alternative-link" in low) and "hdfilmcehennemi" in low
    if name.startswith("fm-episode"):
        return "data-video_url" in low and "filmmakinesi" in low
    return "jwplayer" in low and "sources" in low


def raw_get(url: str, timeout: int = 20, headers: dict | None = None) -> tuple[int, str]:
    request_headers = {
        "User-Agent": UA_CHROME,
        "Accept": "text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8",
    }
    request_headers.update(headers or {})
    request = urllib.request.Request(url, headers=request_headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read(2_000_000).decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        body = error.read(2_000_000).decode("utf-8", "replace")
        return error.code, body
    except Exception as exc:
        return 0, type(exc).__name__


def layer_fetch(url: str) -> list[tuple[str, int, str]]:
    encoded = urllib.parse.quote(url, safe="")
    attempts = [
        ("direct", url, None),
        ("jina", "https://r.jina.ai/" + url, {"x-respond-with": "html"}),
        ("allorigins", "https://api.allorigins.win/raw?url=" + encoded, None),
        ("corsproxy", "https://corsproxy.io/?" + encoded, None),
        ("codetabs", "https://api.codetabs.com/v1/proxy?quest=" + encoded, None),
    ]
    out: list[tuple[str, int, str]] = []
    for via, target, extra in attempts:
        status, body = raw_get(target, headers=extra)
        out.append((via, status, body))
    try:
        cdx = (
            "https://web.archive.org/cdx/search/cdx?url="
            + encoded
            + "&output=json&fl=timestamp,statuscode,original&limit=3&filter=statuscode:200"
        )
        status, body = raw_get(cdx, timeout=15)
        note = body[:300].replace("\n", " ") if status == 200 else body[:120]
        out.append(("wayback-cdx", status, note))
    except Exception as exc:
        out.append(("wayback-cdx", 0, type(exc).__name__))
    return out


def proxies() -> list[str]:
    url = "https://api.github.com/repos/proxifly/free-proxy-list/contents/proxies/countries/TR/data.json"
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "Kitsugi-diagnostic",
            "Authorization": "Bearer " + os.environ["GH_TOKEN"],
            "Accept": "application/vnd.github+json",
        },
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        listing = json.loads(base64.b64decode(json.load(response)["content"]))
    out: list[str] = []
    for item in listing:
        try:
            scheme = item["protocol"]
            address = ipaddress.ip_address(item["ip"])
            port = int(item["port"])
            if scheme not in ("http", "socks4", "socks5") or not address.is_global or not 1 <= port <= 65535:
                continue
            proxy = f"{scheme}://{address}:{port}"
            if proxy not in out:
                out.append(proxy)
        except (KeyError, ValueError, TypeError):
            continue
    return out[:160]


def curl_fetch(proxy: str, name: str, url: str, ua: str) -> tuple[str, str, str]:
    label = f"{name}-{ua}"
    agent = UA_SHORT if ua == "short" else UA_CHROME
    args = [
        "curl", "-sS", "--location", "--max-redirs", "3", "--proto-redir", "=https",
        "--max-time", "9", "--connect-timeout", "5", "--max-filesize", "2000000",
        "--proxy", proxy, "-A", agent, "-e", REFERERS.get(name, REFERER),
        "-H", "Accept: text/html,application/xhtml+xml,*/*",
        "-w", "\nKITSUGI_STATUS:%{http_code}", url,
    ]
    try:
        result = subprocess.run(args, capture_output=True, timeout=12, check=False)
        body, _, status = result.stdout.rpartition(b"\nKITSUGI_STATUS:")
        status_text = status.decode("ascii", errors="replace")
        text = body.decode("utf-8", errors="replace")
        if valid_body(name, status_text, text):
            return label, "player_html", text
        if status_text and status_text != "000":
            low = text.lower()
            if any(marker in low for marker in GEO_MARKERS):
                return label, "HTTP " + status_text + " geo", text[:800]
            return label, "HTTP " + status_text, ""
        return label, "network", ""
    except Exception:
        return label, "network", ""


def print_analysis(name: str, html: str) -> None:
    info = analyze(html, name)
    print(f"### {name}")
    for key, value in info.items():
        print(f"- {key}: {value}")
    ids = re.findall(r'getElementById\(\s*[\'"]([^\'"]+)[\'"]', html)
    if ids:
        print("- getElementById_calls:", sorted(set(ids))[:12])
    attrs = re.findall(r'\.([a-zA-Z_][\w]*)', " ".join(re.findall(r'getElementById\([^)]+\)\.[a-zA-Z_][\w]*', html)))
    if attrs:
        print("- getElementById_props:", sorted(set(attrs))[:12])
    for eid in ("hm00v", "player", "videoplayer"):
        m = re.search(r"<[^>]+\bid=[\"']" + re.escape(eid) + r"[\"'][^>]*>.{0,200}", html, re.I | re.S)
        if m:
            print("- tag_" + eid + ":", re.sub(r"\s+", " ", m.group(0))[:240])
    src = re.search(r"sources\s*:\s*\[.{0,400}", html, re.S)
    if src:
        print("- sources_snip:", re.sub(r"\s+", " ", src.group(0))[:400])
    print()


def main() -> None:
    captured: dict[str, str] = {}
    print("Hedefler (HDFilmCehennemi S09E15 sayfa + S01E01 Close/Rapid):")
    for name, url in PAGES.items():
        print(f"- {name}: {shape_url(url)}")
    print()
    print("## Katmanlar (GitHub runner, vekilsiz)")
    for name, url in PAGES.items():
        print(f"### {name}")
        for via, status, body in layer_fetch(url):
            text = body if isinstance(body, str) else ""
            if valid_body(name, str(status), text):
                captured.setdefault(name, text)
                info = analyze(text, name)
                print(f"- {via}: HTTP {status}, {info['kind']}, {info['bytes']} bayt")
            elif via == "wayback-cdx":
                print(f"- {via}: HTTP {status}, {text[:180]}")
            else:
                low = text.lower()
                tag = "geo" if any(m in low for m in GEO_MARKERS) else analyze(text, name)["kind"] if len(text) > 80 else "empty"
                print(f"- {via}: HTTP {status}, {len(text)} bayt, {tag}")
        print()

    print("## Türkiye vekil listesi")
    try:
        choices = proxies()
    except Exception as exc:
        print(f"Proxy listesi alınamadı: {type(exc).__name__}: {exc}")
        choices = []
    print(f"{len(choices)} Türkiye olarak listelenen HTTP/SOCKS adayı; TLS doğrulaması açık.")
    remaining = {f"{name}-{ua}" for name in PAGES for ua in ("short", "chrome")}
    counts = {label: Counter() for label in remaining}
    lock = threading.Lock()
    stop = threading.Event()

    def work(proxy: str, name: str, url: str, ua: str) -> None:
        label = f"{name}-{ua}"
        if stop.is_set() or label not in remaining:
            return
        result_name, result, text = curl_fetch(proxy, name, url, ua)
        with lock:
            key = result.split(" geo")[0] if result.startswith("HTTP") else result
            counts[result_name][key] += 1
            if result == "player_html" and name not in captured:
                captured[name] = text
                remaining.difference_update({f"{name}-short", f"{name}-chrome"})
            if set(PAGES) <= set(captured):
                stop.set()

    if choices:
        with concurrent.futures.ThreadPoolExecutor(max_workers=24) as pool:
            futures = [
                pool.submit(work, proxy, name, url, ua)
                for proxy in choices
                for name, url in PAGES.items()
                for ua in ("short", "chrome")
            ]
            concurrent.futures.wait(futures, timeout=90)
    for label, results in sorted(counts.items()):
        print("- " + label + ": " + (", ".join(f"{k}: {v}" for k, v in sorted(results.items())) or "aday yok"))
    print()
    print("## Yakalanan gövde tanısı")
    out_dir = os.environ.get("HDFC_PROBE_OUT", "")
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
        for name, html in captured.items():
            path = os.path.join(out_dir, name + ".html")
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(html)
            print(f"- yazıldı {path} ({len(html)} bayt)")
    if not captured:
        print("Oyuncu veya bölüm HTML'i alınamadı.")
        return
    for name, html in captured.items():
        print_analysis(name, html)
        if name.startswith("hd-s"):
            buttons = re.findall(
                r'data-video\s*=\s*["\'](\d+)["\'][^>]*>(.*?)</button>',
                html,
                re.I | re.S,
            )
            print("- buttons:")
            for vid, label in buttons[:8]:
                print(f"    · {vid} { ' '.join(label.split())[:40]}")
            frames = re.findall(r'(?:data-src|src)\s*=\s*["\']([^"\']+)["\']', html, re.I)
            for src in frames[:6]:
                if any(k in src.lower() for k in ("embed", "rplayer", "close", "rapid")):
                    print(f"- iframe: {shape_url(src)}")
            print()


if __name__ == "__main__":
    main()
