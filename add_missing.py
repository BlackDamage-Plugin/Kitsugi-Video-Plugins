import json, os, urllib.request

# Mevcut prebuilt_plugins.json
with open('prebuilt_plugins.json', encoding='utf-8') as f:
    existing = json.load(f)

existing_map = {}
for p in existing:
    n = p.get('internalName') or p.get('name')
    if n: existing_map[n.lower().strip()] = p

# Hexated'dan FilmPalast
hexated_url = 'https://raw.githubusercontent.com/hexated/cloudstream-extensions-hexated/builds/plugins.json'
with urllib.request.urlopen(hexated_url, timeout=15) as r:
    hexated_plugins = json.loads(r.read().decode('utf-8'))

# Makoto'dan Kanal 7
makoto_repo_url = 'https://raw.githubusercontent.com/Sertel392/Makotogecici/main/repo.json'
with urllib.request.urlopen(makoto_repo_url, timeout=15) as r:
    makoto_repo = json.loads(r.read().decode('utf-8'))
makoto_plugins = []
for pl_url in makoto_repo.get('pluginLists', []):
    try:
        with urllib.request.urlopen(pl_url, timeout=15) as r:
            sub = json.loads(r.read().decode('utf-8'))
            if isinstance(sub, list):
                makoto_plugins.extend(sub)
    except Exception as e:
        print(f'[HATA] {e}')

added = []

# FilmPalast ekle (hexated)
for p in hexated_plugins:
    n = p.get('internalName') or p.get('name')
    if n and n == 'FilmPalast':
        key = n.lower().strip()
        if key not in existing_map:
            existing_map[key] = p
            added.append(f"FilmPalast v{p.get('version','?')} (hexated)")

# Kanal 7 ekle (Makoto) - internalName farklı olabilir
for p in makoto_plugins:
    n = p.get('internalName') or p.get('name')
    if n and 'kanal' in n.lower() and '7' in n:
        key = n.lower().strip()
        if key not in existing_map:
            existing_map[key] = p
            added.append(f"{n} v{p.get('version','?')} (Makoto)")

merged = list(existing_map.values())
with open('prebuilt_plugins.json', 'w', encoding='utf-8') as f:
    json.dump(merged, f, indent=4, ensure_ascii=False)

if added:
    print(f'Eklendi:')
    for a in added: print(f'  + {a}')
else:
    print('Eklenecek bir sey bulunamadi.')
