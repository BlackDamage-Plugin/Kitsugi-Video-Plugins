import os
import sys
import shutil
import urllib.request
import zipfile
import tempfile

REPOS = [
    ("Kekik", "feroxx", "Kekik-cloudstream"),
    ("Kraptor", "Kraptor123", "cs-kraptor"),
    ("Cs-Karma", "Kraptor123", "Cs-Karma"),
    ("nik-cloudstream", "nikyokki", "nik-cloudstream"),
    ("AyzenCS3", "ByAyzen", "AyzenCS3"),
    ("cs-kekikanime", "Kraptor123", "cs-kekikanime"),
    ("Pitipitii", "sarapcanagii", "Pitipitii"),
    ("Cs-GizliKeyif", "Kraptor123", "Cs-GizliKeyif"),
    ("Makotogecici", "Sertel392", "Makotogecici"),
    ("cloudstream-cagi-eklenti", "caca1403", "cloudstream-cagi-eklenti"),
]

# Standard directories/files to ignore in the root of the repositories
EXCLUDE_DIRS = {
    "gradle", "build", ".git", ".github", ".gradle", ".kotlin", ".idea", ".vscode", "prebuilt", "__Temel", "CanliTV", "OxAx", "SineWix", "YouTube", "NetflixMirror", "HQPorner"
}

def get_existing_plugins():
    existing = set()
    for name in os.listdir("."):
        if os.path.isdir(name) and not name.startswith(".") and name not in EXCLUDE_DIRS:
            if os.path.exists(os.path.join(name, "build.gradle.kts")) or os.path.exists(os.path.join(name, "build.gradle")):
                existing.add(name.lower().strip())
    return existing

def download_zip(owner, repo):
    branches = ["master", "main"]
    for branch in branches:
        url = f"https://github.com/{owner}/{repo}/archive/refs/heads/{branch}.zip"
        print(f"Downloading {url} ...")
        try:
            req = urllib.request.Request(
                url, 
                headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
            )
            with urllib.request.urlopen(req, timeout=30) as response:
                return response.read(), branch
        except urllib.error.HTTPError as e:
            if e.code == 404:
                print(f"Branch '{branch}' not found for {owner}/{repo}. Trying next...")
            else:
                print(f"HTTP Error {e.code} downloading {url}")
        except Exception as e:
            print(f"Error downloading {url}: {e}")
    return None, None

def sync():
    existing_plugins = get_existing_plugins()
    print(f"Existing local plugins count: {len(existing_plugins)}")
    
    temp_dir = tempfile.mkdtemp()
    print(f"Temporary extraction directory: {temp_dir}")
    
    copied_count = 0
    copied_plugins = []
    
    try:
        for name, owner, repo in REPOS:
            print(f"\n--- Processing {owner}/{repo} ({name}) ---")
            zip_data, branch = download_zip(owner, repo)
            if not zip_data:
                print(f"Skipping {owner}/{repo} (could not download ZIP)")
                continue
                
            zip_path = os.path.join(temp_dir, f"{repo}.zip")
            with open(zip_path, "wb") as f:
                f.write(zip_data)
                
            extract_path = os.path.join(temp_dir, f"{repo}_extracted")
            with zipfile.ZipFile(zip_path, 'r') as zip_ref:
                zip_ref.extractall(extract_path)
                
            # Find the root folder inside the extracted folder (usually repo-branch or repo-master)
            root_extracted_dirs = os.listdir(extract_path)
            if not root_extracted_dirs:
                print(f"Extraction failed/empty for {repo}")
                continue
                
            repo_root = os.path.join(extract_path, root_extracted_dirs[0])
            
            for item in os.listdir(repo_root):
                item_path = os.path.join(repo_root, item)
                if os.path.isdir(item_path) and item not in EXCLUDE_DIRS and not item.startswith("."):
                    # Check if it has build.gradle or build.gradle.kts
                    if os.path.exists(os.path.join(item_path, "build.gradle.kts")) or os.path.exists(os.path.join(item_path, "build.gradle")):
                        key = item.lower().strip()
                        if key not in existing_plugins:
                            dest_path = os.path.join(".", item)
                            print(f"  [FOUND MISSING] Copying '{item}' from {owner}/{repo} to {dest_path}")
                            shutil.copytree(item_path, dest_path, dirs_exist_ok=True)
                            existing_plugins.add(key)
                            copied_count += 1
                            copied_plugins.append(f"{item} ({owner}/{repo})")
                        else:
                            print(f"  [EXISTS] '{item}' is already in local plugins.")
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)
        
    print("\n==========================================")
    print(f"Sync complete. Copied {copied_count} new plugin source directories.")
    if copied_plugins:
        print("Copied plugins:")
        for p in copied_plugins:
            print(f"  + {p}")
    print("==========================================")

if __name__ == "__main__":
    sync()
