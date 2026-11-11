import json, os, urllib.request

with open('prebuilt_plugins.json', encoding='utf-8') as f:
    our_plugins = json.load(f)
our_names = set()
for p in our_plugins:
    n = p.get('internalName') or p.get('name')
    if n: our_names.add(n.lower().strip())

if os.path.exists('build/plugins.json'):
    with open('build/plugins.json', encoding='utf-8') as f:
        compiled = json.load(f)
    for p in compiled:
        n = p.get('internalName') or p.get('name')
        if n: our_names.add(n.lower().strip())

print(f'Bizde toplam: {len(our_names)} eklenti\n')

# Hexated ve diger bilinmeyen repolar - doğrudan plugins.json URL'leri
direct_plugin_lists = [
    ('hexated', 'https://raw.githubusercontent.com/hexated/cloudstream-extensions-hexated/builds/plugins.json'),
    ('recloudstream', 'https://raw.githubusercontent.com/recloudstream/csstuff/master/plugins.json'),
    ('caci1403', 'https://raw.githubusercontent.com/caca1403/cloudstream-cagi-eklenti/main/repo.json'),
    ('Sertel392/Makoto', 'https://raw.githubusercontent.com/Sertel392/Makotogecici/main/repo.json'),
    ('sarapcanagii/Pitipitii', 'https://raw.githubusercontent.com/sarapcanagii/Pitipitii/master/repo.json'),
    ('ByAyzen/AyzenCS3', 'https://raw.githubusercontent.com/ByAyzen/AyzenCS3/master/repo.json'),
]

all_missing = {}

for repo_name, url in direct_plugin_lists:
    try:
        with urllib.request.urlopen(url, timeout=15) as r:
            data = json.loads(r.read().decode('utf-8'))
        
        # repo.json formatı mı yoksa direkt dizi mi?
        if isinstance(data, list):
            plugins = data
        elif isinstance(data, dict) and 'pluginLists' in data:
            plugins = []
            for pl_url in data.get('pluginLists', []):
                try:
                    with urllib.request.urlopen(pl_url, timeout=15) as r2:
                        sub = json.loads(r2.read().decode('utf-8'))
                        if isinstance(sub, list):
                            plugins.extend(sub)
                except Exception as e2:
                    print(f'  [HATA sub] {e2}')
        else:
            print(f'[BILINMEYEN FORMAT] {repo_name}')
            continue

        missing = []
        for p in plugins:
            n = p.get('internalName') or p.get('name')
            if n and n.lower().strip() not in our_names:
                lang = p.get('language', '?')
                types = ','.join(p.get('tvTypes', []))
                missing.append((n, p.get('version', '?'), lang, types))
        
        if missing:
            tr_missing = [m for m in missing if m[2] in ('tr', '?', 'az')]
            other_missing = [m for m in missing if m[2] not in ('tr', '?', 'az')]
            print(f'=== {repo_name} - TR EKSİK: {len(tr_missing)} | Diger: {len(other_missing)} ===')
            for m in sorted(tr_missing):
                print(f'  TR - {m[0]} v{m[1]} | {m[3]}')
            all_missing[repo_name] = missing
        else:
            print(f'=== {repo_name} - Tamamen mevcut ===')
    except Exception as e:
        print(f'[HATA] {repo_name}: {e}')

print(f'\nToplam TR eksik yakl.: {sum(len([m for m in v if m[2] in ("tr","?","az")]) for v in all_missing.values())}')
