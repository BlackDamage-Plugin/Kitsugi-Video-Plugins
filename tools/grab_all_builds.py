"""
Desteklenen tüm harici repolardan derlenmiş .cs3 dosyalarını indirir,
URL'leri kendi repomuzla değiştirir ve builds branch'ine hazırlar.
"""
import json
import os
import urllib.request
import urllib.parse
import hashlib
import ssl
import zipfile
import tempfile
import shutil

ssl_ctx = ssl._create_unverified_context()

MY_REPO = "KitsugiBeta-dev/Kitsugi-Plugins"
BUILDS_DIR = r"..\builds-branch"

# Her repo: (plugins.json URL, builds ZIP URL, repo adı)
REPOS = [
    {
        "name": "Kraptor123/cs-kraptor",
        "plugins_json": "https://raw.githubusercontent.com/Kraptor123/cs-kraptor/builds/plugins.json",
        "builds_zip": "https://github.com/Kraptor123/cs-kraptor/archive/refs/heads/builds.zip",
        "zip_prefix": "cs-kraptor-builds/",
    },
    {
        "name": "feroxx/Kekik-cloudstream",
        "plugins_json": "https://raw.githubusercontent.com/feroxx/Kekik-cloudstream/builds/plugins.json",
        "builds_zip": "https://github.com/feroxx/Kekik-cloudstream/archive/refs/heads/builds.zip",
        "zip_prefix": "Kekik-cloudstream-builds/",
    },
    {
        "name": "ByAyzen/AyzenCS3",
        "plugins_json": "https://raw.githubusercontent.com/ByAyzen/AyzenCS3/refs/heads/builds/plugins.json",
        "builds_zip": "https://github.com/ByAyzen/AyzenCS3/archive/refs/heads/builds.zip",
        "zip_prefix": "AyzenCS3-builds/",
    },
    {
        "name": "Sertel392/Makotogecici",
        "plugins_json": "https://raw.githubusercontent.com/Sertel392/Makotogecici/refs/heads/main/plugins.json",
        "builds_zip": "https://github.com/Sertel392/Makotogecici/archive/refs/heads/main.zip",
        "zip_prefix": "Makotogecici-main/",
    },
    {
        "name": "caca1403/cloudstream-cagi-eklenti",
        "plugins_json": "https://raw.githubusercontent.com/caca1403/cloudstream-cagi-eklenti/main/plugins.json?v=1",
        "builds_zip": "https://github.com/caca1403/cloudstream-cagi-eklenti/archive/refs/heads/main.zip",
        "zip_prefix": "cloudstream-cagi-eklenti-main/",
    },
    {
        "name": "sarapcanagii/Pitipitii",
        "plugins_json": "https://raw.githubusercontent.com/sarapcanagii/Pitipitii/builds/plugins.json",
        "builds_zip": "https://github.com/sarapcanagii/Pitipitii/archive/refs/heads/builds.zip",
        "zip_prefix": "Pitipitii-builds/",
    },
]

def fetch_bytes(url):
    url = url.replace(" ", "%20")
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=30, context=ssl_ctx) as r:
            return r.read()
    except Exception as e:
        print(f"  ERROR fetching {url}: {e}")
        return None

def fetch_json(url):
    data = fetch_bytes(url)
    if data:
        try:
            return json.loads(data.decode("utf-8"))
        except:
            pass
    return None

def main():
    os.makedirs(BUILDS_DIR, exist_ok=True)

    # Mevcut plugins.json yükle
    plugins_json_path = os.path.join(BUILDS_DIR, "plugins.json")
    existing_plugins = {}
    if os.path.exists(plugins_json_path):
        try:
            with open(plugins_json_path, "r", encoding="utf-8") as f:
                for p in json.load(f):
                    key = (p.get("internalName") or p.get("name", "")).strip()
                    if key:
                        existing_plugins[key] = p
            print(f"Mevcut plugins.json: {len(existing_plugins)} eklenti")
        except Exception as e:
            print(f"plugins.json okuma hatasi: {e}")

    total_downloaded = 0

    for repo in REPOS:
        print(f"\n{'='*60}")
        print(f"Repo: {repo['name']}")
        print(f"{'='*60}")

        # plugins.json indir
        meta_list = fetch_json(repo["plugins_json"])
        if not meta_list or not isinstance(meta_list, list):
            print(f"  plugins.json alinamadi, atlaniyor.")
            continue

        # builds ZIP indir
        print(f"  ZIP indiriliyor: {repo['builds_zip']}")
        zip_data = fetch_bytes(repo["builds_zip"])
        if not zip_data:
            print(f"  ZIP indirilemedi, atlaniyor.")
            continue

        # ZIP'i aç, .cs3 dosyalarını çıkar
        tmpdir = tempfile.mkdtemp()
        zip_path = os.path.join(tmpdir, "builds.zip")
        with open(zip_path, "wb") as f:
            f.write(zip_data)

        cs3_files = {}  # filename -> bytes
        try:
            with zipfile.ZipFile(zip_path, "r") as zf:
                for name in zf.namelist():
                    if name.endswith(".cs3"):
                        basename = os.path.basename(name)
                        cs3_files[basename] = zf.read(name)
        except Exception as e:
            print(f"  ZIP acma hatasi: {e}")
            shutil.rmtree(tmpdir, ignore_errors=True)
            continue

        print(f"  ZIP'te {len(cs3_files)} adet .cs3 bulundu")

        # Her plugin meta verisini işle
        for p in meta_list:
            plugin_name = (p.get("internalName") or p.get("name", "")).strip()
            if not plugin_name:
                continue

            cs3_filename = f"{plugin_name}.cs3"
            if cs3_filename not in cs3_files:
                # Farklı isimle dene
                found = None
                for fn in cs3_files:
                    if fn.lower() == cs3_filename.lower():
                        found = fn
                        break
                if not found:
                    print(f"  UYARI: {cs3_filename} ZIP'te bulunamadi")
                    continue
                cs3_filename = found

            file_data = cs3_files[cs3_filename]
            dest_path = os.path.join(BUILDS_DIR, cs3_filename)

            # Dosyayı kopyala
            with open(dest_path, "wb") as f:
                f.write(file_data)

            # Meta veriyi güncelle - URL'yi kendi repomuzla değiştir
            file_size = len(file_data)
            sha256_hash = hashlib.sha256(file_data).hexdigest()

            updated = p.copy()
            updated["url"] = f"https://raw.githubusercontent.com/{MY_REPO}/builds/{cs3_filename}"
            updated["fileSize"] = file_size
            updated["fileHash"] = f"sha256-{sha256_hash}"
            updated["repositoryUrl"] = f"https://github.com/{repo['name']}"

            existing_plugins[plugin_name] = updated
            total_downloaded += 1
            print(f"  OK {plugin_name} ({file_size:,} bytes)")

        shutil.rmtree(tmpdir, ignore_errors=True)

    # plugins.json'u yaz
    final_list = list(existing_plugins.values())
    with open(plugins_json_path, "w", encoding="utf-8") as f:
        json.dump(final_list, f, indent=4, ensure_ascii=False)

    print(f"\n{'='*60}")
    print(f"TAMAMLANDI!")
    print(f"  Indirilen/Guncellenen .cs3: {total_downloaded}")
    print(f"  plugins.json toplam: {len(final_list)} eklenti")
    print(f"{'='*60}")

if __name__ == "__main__":
    main()
