import json
import os
import urllib.request
import urllib.error
import hashlib
import ssl

# Disable SSL verification for convenience
ssl_context = ssl._create_unverified_context()

URLS = [
    # Kraptor
    'https://raw.githubusercontent.com/Kraptor123/cs-kraptor/builds/plugins.json',
    # Kekik
    'https://raw.githubusercontent.com/feroxx/Kekik-cloudstream/builds/plugins.json',
    # ByAyzen
    'https://raw.githubusercontent.com/ByAyzen/AyzenCS3/master/repo.json',
    # Cağatay Repo
    'https://raw.githubusercontent.com/caca1403/cloudstream-cagi-eklenti/main/repo.json',
    # Makoto
    'https://raw.githubusercontent.com/Sertel392/Makotogecici/main/repo.json',
    # Pitipitii
    'https://raw.githubusercontent.com/sarapcanagii/Pitipitii/master/repo.json',
]

EXCLUDE_DIRS = {
    "gradle", "build", ".git", ".github", ".gradle", ".kotlin", ".idea", ".vscode", "prebuilt", "__Temel"
}

def get_source_plugins():
    source_plugins = set()
    for name in os.listdir("."):
        if os.path.isdir(name) and not name.startswith(".") and name not in EXCLUDE_DIRS:
            if os.path.exists(os.path.join(name, "build.gradle.kts")) or os.path.exists(os.path.join(name, "build.gradle")):
                source_plugins.add(name.lower().strip())
    return source_plugins

def fetch_json(url):
    print(f"Fetching: {url}")
    try:
        req = urllib.request.Request(
            url, 
            headers={'User-Agent': 'Mozilla/5.0'}
        )
        with urllib.request.urlopen(req, timeout=15, context=ssl_context) as r:
            return json.loads(r.read().decode('utf-8'))
    except Exception as e:
        print(f"Error fetching JSON from {url}: {e}")
        return None

def main():
    source_plugins = get_source_plugins()
    print(f"Local source-code plugins (to exclude from prebuilt): {len(source_plugins)}")
    
    # Load existing prebuilt_plugins.json
    prebuilt_file = 'prebuilt_plugins.json'
    existing_prebuilt = []
    if os.path.exists(prebuilt_file):
        try:
            with open(prebuilt_file, 'r', encoding='utf-8') as f:
                existing_prebuilt = json.load(f)
        except Exception as e:
            print(f"Error reading {prebuilt_file}: {e}")
            existing_prebuilt = []

    # Map by internalName
    prebuilt_map = {}
    for p in existing_prebuilt:
        name = (p.get('internalName') or p.get('name') or '').lower().strip()
        if name:
            prebuilt_map[name] = p

    all_fetched_plugins = []
    
    for url in URLS:
        data = fetch_json(url)
        if not data:
            continue
            
        if isinstance(data, list):
            all_fetched_plugins.extend(data)
        elif isinstance(data, dict):
            # Check if it has pluginLists
            if 'pluginLists' in data:
                for sub_url in data.get('pluginLists', []):
                    sub_data = fetch_json(sub_url)
                    if sub_data and isinstance(sub_data, list):
                        all_fetched_plugins.extend(sub_data)
            else:
                # Might be single plugin dict or other format
                pass

    # Ensure prebuilt directory exists
    os.makedirs('prebuilt', exist_ok=True)

    downloaded_count = 0
    updated_meta_count = 0

    for p in all_fetched_plugins:
        name = p.get('internalName', p.get('name', ''))
        key = name.lower().strip()
        
        if not key:
            continue
            
        # Skip if we already build this plugin from source
        if key in source_plugins:
            continue
            
        # Version and details check
        ver = p.get('version', 0)
        p_url = p.get('url')
        if p_url:
            p_url = p_url.replace(" ", "%20")
        if not p_url:
            continue
            
        existing = prebuilt_map.get(key)
        existing_ver = existing.get('version', -1) if existing else -1
        
        # Download if new or version is higher
        # Or if the local file in 'prebuilt/' doesn't exist
        local_filename = f"prebuilt/{name}.cs3"
        file_missing = not os.path.exists(local_filename)
        
        if file_missing or ver > existing_ver:
            print(f"Syncing: {name} (ver: {existing_ver} -> {ver})")
            
            # Download the .cs3 file
            try:
                req = urllib.request.Request(p_url, headers={'User-Agent': 'Mozilla/5.0'})
                with urllib.request.urlopen(req, timeout=30, context=ssl_context) as response:
                    file_data = response.read()
                    
                # Save to prebuilt/
                with open(local_filename, 'wb') as f:
                    f.write(file_data)
                
                # Calculate hash and size
                file_size = len(file_data)
                sha256_hash = hashlib.sha256(file_data).hexdigest()
                file_hash = f"sha256-{sha256_hash}"
                
                # Create/Update entry
                updated_entry = p.copy()
                updated_entry['url'] = f"https://raw.githubusercontent.com/KitsugiBeta-dev/Kitsugi-Plugins/builds/{name}.cs3"
                updated_entry['fileSize'] = file_size
                updated_entry['fileHash'] = file_hash
                
                prebuilt_map[key] = updated_entry
                downloaded_count += 1
                print(f"  Successfully downloaded and mapped {name} ({file_size} bytes)")
                
            except Exception as e:
                print(f"  Error downloading {name} from {p_url}: {e}")
        else:
            # Just keep existing
            pass

    # Save to prebuilt_plugins.json
    final_prebuilt = list(prebuilt_map.values())
    with open(prebuilt_file, 'w', encoding='utf-8') as f:
        json.dump(final_prebuilt, f, indent=4, ensure_ascii=False)

    print(f"\nCompleted: Downloaded/updated {downloaded_count} plugins in 'prebuilt/' and updated '{prebuilt_file}'.")

if __name__ == '__main__':
    main()
