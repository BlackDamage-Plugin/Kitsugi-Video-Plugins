#!/usr/bin/env python3
"""Temporary read-only player diagnostics: no cookies, token logging or media downloads."""
import base64
import concurrent.futures
import ipaddress
import json
import os
import subprocess
import urllib.request
from collections import Counter

PAGES = {
    "FilmMakinesi-Close": ("https://closeload.filmmakinesi.to/video/embed/RFeVmBTmaCG/?imdb_id=tt0460681", "https://filmmakinesi.to/dizi/supernatural-2005-fm8t9q/sezon-1/bolum-1/"),
    "FilmMakinesi-Rapid": ("https://rapid.filmmakinesi.to/embed-6d4mkrakdi1a/", "https://filmmakinesi.to/dizi/supernatural-2005-fm8t9q/sezon-1/bolum-1/"),
    "HDFilmCehennemi-Close": ("https://hdfilmcehennemi.mobi/video/embed/RFeVmBTmaCG/?rapidrame_id=6d4mkrakdi1a", "https://www.hdfilmcehennemi.nl/dizi/supernatural/sezon-1/bolum-1-hd11/"),
}


def proxies():
    # Independent TR lists, without transmitting cookies or authorization to them.
    urls = [
        "https://api.github.com/repos/stormsia/proxy-list/contents/web/public/proxies.json",
        "https://api.github.com/repos/ProxyScrape/free-proxy-list/contents/proxies/countries/tr/data.json",
        "https://api.github.com/repos/litportnet/free-proxy-list/contents/proxies/countries/tr/all.json",
        "https://api.github.com/repos/proxifly/free-proxy-list/contents/proxies/countries/TR/data.json",
    ]
    out = []
    for url in urls:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Kitsugi-diagnostic", "Authorization": "Bearer " + os.environ["GH_TOKEN"]})
            with urllib.request.urlopen(req, timeout=20) as response:
                listing = json.loads(base64.b64decode(json.load(response)["content"]))
            for item in listing:
                try:
                    scheme = item["protocol"]
                    if "geolocation" in item and ((item.get("geolocation") or {}).get("country") or {}).get("iso_code") != "TR":
                        continue
                    if item.get("username") or item.get("password"):
                        continue
                    if "https" in item and item["https"] is False:
                        continue
                    address = ipaddress.ip_address(item.get("ip") or item["host"])
                    port = int(item["port"])
                    if scheme not in ("http", "socks4", "socks5") or not address.is_global or not 1 <= port <= 65535:
                        continue
                    proxy = f"{scheme}://{address}:{port}"
                    if proxy not in out:
                        out.append(proxy)
                except (KeyError, ValueError, TypeError, AttributeError):
                    continue
        except Exception as exc:
            print(f"Liste alınamadı: {type(exc).__name__}")
    return out[:180]


def fetch(pair):
    proxy, (name, (url, referer)) = pair
    args = ["curl", "-sS", "--location", "--max-redirs", "3", "--proto-redir", "=https", "--max-time", "9", "--connect-timeout", "5", "--max-filesize", "2000000", "--proxy", proxy, "-A", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36", "-e", referer, "-H", "Accept: text/html,application/xhtml+xml,*/*", "-w", "\\nKITSUGI_STATUS:%{http_code}", url]
    try:
        result = subprocess.run(args, capture_output=True, timeout=12, check=False)
        body, _, status = result.stdout.rpartition(b"\nKITSUGI_STATUS:")
        status = status.decode("ascii", errors="replace")
        text = body.decode("utf-8", errors="replace")
        low = text.lower()
        valid = status == "200" and "jwplayer" in low and "sources" in low and not any(s in low for s in ("content unavailable in your region", "just a moment", "cf-error-details"))
        if valid:
            os.makedirs("site-dump", exist_ok=True)
            try:
                with open(f"site-dump/{name}.html", "x", encoding="utf-8") as output:
                    output.write(text)
            except FileExistsError:
                pass
            return name, "player_html"
        return name, "HTTP " + status if status and status != "000" else "network"
    except Exception:
        return name, "network"


def main():
    try:
        choices = proxies()
    except Exception as exc:
        print(f"Proxy listesi alınamadı: {type(exc).__name__}")
        return
    print(f"{len(choices)} Türkiye olarak listelenen HTTP/SOCKS adayı; TLS doğrulaması açık, oturum verisi kullanılmadı.")
    counts = {name: Counter() for name in PAGES}
    with concurrent.futures.ThreadPoolExecutor(max_workers=24) as pool:
        for name, result in pool.map(fetch, ((proxy, page) for proxy in choices for page in PAGES.items())):
            counts[name][result] += 1
    for name, results in counts.items():
        print(f"- {name}: " + (", ".join(f"{k}: {v}" for k, v in sorted(results.items())) or "aday yok"))


if __name__ == "__main__":
    main()
