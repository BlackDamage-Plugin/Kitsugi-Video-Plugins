#!/usr/bin/env python3
"""Opt-in test of publicly listed TR HTTP proxies; no credentials or HTML logged."""
import concurrent.futures
import ipaddress
import json
import os
import urllib.error
import urllib.request

from probe_player_access import PAGES

PROXY_LIST = "https://api.github.com/repos/proxifly/free-proxy-list/contents/proxies/countries/TR/data.json"


def candidates():
    import base64
    headers = {"User-Agent": "Kitsugi-player-diagnostic"}
    token = os.environ.get("GH_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(PROXY_LIST, headers=headers)
    with urllib.request.urlopen(request, timeout=15) as response:
        data = json.loads(base64.b64decode(json.load(response)["content"]))
    out = []
    for item in data:
        try:
            if item["protocol"] != "http" or not ipaddress.ip_address(item["ip"]).is_global:
                continue
            url = f'http://{item["ip"]}:{int(item["port"])}'
            if url not in out:
                out.append(url)
        except (ValueError, KeyError):
            continue
    return out[:35]


def attempt(proxy, page):
    label, url, referer = page
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36", "Referer": referer, "Accept": "text/html,application/xhtml+xml,*/*"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({"https": proxy}))
    try:
        # Default TLS verification is deliberately kept on. No cookies/auth sent.
        with opener.open(request, timeout=6) as response:
            text = response.read(1_000_000).decode("utf-8", "replace")
            status = response.status
    except urllib.error.HTTPError as e:
        status = e.code
        text = e.read(100_000).decode("utf-8", "replace")
    except Exception:
        return label, "network"
    lower = text.lower()
    if status == 200 and "jwplayer" in lower and "sources" in lower and "content unavailable in your region" not in lower:
        # Kısa ömürlü teşhis artifact'ı; başlık, cookie ve proxy adresi kaydedilmez.
        os.makedirs("site-dump", exist_ok=True)
        path = "site-dump/" + label.replace(" ", "-") + ".html"
        try:
            with open(path, "x", encoding="utf-8") as output:
                output.write(text)
        except FileExistsError:
            pass
        return label, "player_html"
    return label, f"HTTP {status}"  # no untrusted body or proxy endpoint in report


def main():
    print("## Türkiye konumlu genel proxy denemesi\n")
    try:
        proxies = candidates()
    except Exception as e:
        print(f"Proxy listesi alınamadı ({type(e).__name__}).")
        return
    print(f"{len(proxies)} HTTP proxy adayı; TLS doğrulaması açık, çerez veya kimlik bilgisi kullanılmadı. Konum ve proxy güvenilirliği garanti edilmez.\n")
    counts = {p[0]: {} for p in PAGES}
    with concurrent.futures.ThreadPoolExecutor(max_workers=24) as pool:
        for label, result in pool.map(lambda pair: attempt(*pair), [(proxy, page) for proxy in proxies for page in PAGES]):
            counts[label][result] = counts[label].get(result, 0) + 1
    for label, results in counts.items():
        print(f"- {label}: " + (", ".join(f"{key}: {value}" for key, value in sorted(results.items())) or "proxy adayı yok"))


if __name__ == "__main__":
    main()
