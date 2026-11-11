"""
Desteklenen harici repolardan açık kaynak plugin klasörlerini indirir,
yerel main branch'te OLMAYANLARI kopyalar ve push eder.
"""
import os
import sys
import json
import shutil
import zipfile
import tempfile
import urllib.request
import ssl

ssl_ctx = ssl._create_unverified_context()

ROOT = r"C:\Kitsugi-Beta\Kitsugi-Plugins"

EXCLUDE = {
    "gradle", "build", ".git", ".github", ".gradle", ".kotlin",
    ".idea", ".vscode", "prebuilt", "__pycache__", "__Temel", "__New"
}

SOURCE_REPOS = [
    ("https://github.com/feroxx/Kekik-cloudstream/archive/refs/heads/master.zip", "Kekik-cloudstream-master"),
    ("https://github.com/ByAyzen/AyzenCS3/archive/refs/heads/master.zip", "AyzenCS3-master"),
    ("https://github.com/nikyokki/nik-cloudstream/archive/refs/heads/master.zip", "nik-cloudstream-master"),
    ("https://github.com/Sertel392/Makotogecici/archive/refs/heads/main.zip", "Makotogecici-main"),
    ("https://github.com/caca1403/cloudstream-cagi-eklenti/archive/refs/heads/main.zip", "cloudstream-cagi-eklenti-main"),
    ("https://github.com/sarapcanagii/Pitipitii/archive/refs/heads/master.zip", "Pitipitii-master"),
    ("https://github.com/Kraptor123/Cs-Karma/archive/refs/heads/master.zip", "Cs-Karma-master"),
    ("https://github.com/Kraptor123/cs-kekikanime/archive/refs/heads/master.zip", "cs-kekikanime-master"),
    ("https://github.com/Kraptor123/Cs-GizliKeyif/archive/refs/heads/master.zip", "Cs-GizliKeyif-master"),
]

def fetch_zip(url):
    print(f"  Indiriliyor: {url}")
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=30, context=ssl_ctx) as r:
            return r.read()
    except Exception as e:
        print(f"  HATA: {e}")
        return None

def get_local_plugins():
    """Main branch'teki mevcut plugin klasörlerini döndür (küçük harf)"""
    plugins = set()
    for name in os.listdir(ROOT):
        path = os.path.join(ROOT, name)
        if not os.path.isdir(path):
            continue
        if name.startswith(".") or name in EXCLUDE:
            continue
        if (os.path.exists(os.path.join(path, "build.gradle.kts")) or
                os.path.exists(os.path.join(path, "build.gradle"))):
            plugins.add(name.lower())
    return plugins

def main():
    local_plugins = get_local_plugins()
    print(f"Mevcut local plugin klasoru: {len(local_plugins)}\n")

    added = []
    tmpdir = tempfile.mkdtemp()

    for zip_url, prefix in SOURCE_REPOS:
        print(f"\n{'='*60}")
        print(f"Repo: {prefix}")
        zip_data = fetch_zip(zip_url)
        if not zip_data:
            continue

        zip_path = os.path.join(tmpdir, "repo.zip")
        with open(zip_path, "wb") as f:
            f.write(zip_data)

        extract_dir = os.path.join(tmpdir, prefix)
        os.makedirs(extract_dir, exist_ok=True)

        try:
            with zipfile.ZipFile(zip_path, "r") as zf:
                zf.extractall(extract_dir)
        except Exception as e:
            print(f"  ZIP acilamadi: {e}")
            continue

        # ZIP'in icindeki tek kok klasoru bul
        contents = os.listdir(extract_dir)
        if len(contents) == 1:
            repo_root = os.path.join(extract_dir, contents[0])
        else:
            repo_root = extract_dir

        # Plugin klasorlerini tara
        for name in os.listdir(repo_root):
            plugin_path = os.path.join(repo_root, name)
            if not os.path.isdir(plugin_path):
                continue
            if name.startswith(".") or name in EXCLUDE:
                continue
            has_gradle = (
                os.path.exists(os.path.join(plugin_path, "build.gradle.kts")) or
                os.path.exists(os.path.join(plugin_path, "build.gradle"))
            )
            if not has_gradle:
                continue

            if name.lower() in local_plugins:
                continue  # Zaten var

            # Yeni! Kopyala
            dest = os.path.join(ROOT, name)
            if os.path.exists(dest):
                continue
            try:
                shutil.copytree(plugin_path, dest)
                local_plugins.add(name.lower())
                added.append(f"{prefix}/{name}")
                print(f"  + EKLENDI: {name}")
            except Exception as e:
                print(f"  KOPYA HATASI {name}: {e}")

    shutil.rmtree(tmpdir, ignore_errors=True)

    print(f"\n{'='*60}")
    print(f"TOPLAM {len(added)} yeni plugin klasoru eklendi:")
    for a in added:
        print(f"  {a}")
    print(f"{'='*60}")

    return added

if __name__ == "__main__":
    added = main()
    if not added:
        print("Eklenecek yeni plugin bulunamadi.")
        sys.exit(0)
    print("\nGit commit/push icin devam ediliyor...")
