import json, urllib.request

# Mevcut prebuilt_plugins.json'u yukle
with open('prebuilt_plugins.json', encoding='utf-8') as f:
    existing = json.load(f)

existing_map = {}
for p in existing:
    n = (p.get('internalName') or p.get('name') or '').lower().strip()
    if n:
        existing_map[n] = p

# Kraptor reposundan tum eklentileri cek
print('Kraptor plugins.json cekiliyor...')
url = 'https://raw.githubusercontent.com/Kraptor123/cs-kraptor/builds/plugins.json'
with urllib.request.urlopen(url, timeout=15) as r:
    kraptor = json.loads(r.read().decode('utf-8'))

print(f'Kraptor toplam: {len(kraptor)} eklenti')

added = []
updated = []

for p in kraptor:
    n = (p.get('internalName') or p.get('name') or '').lower().strip()
    if not n:
        continue

    if n not in existing_map:
        # Hic yok, ekle
        existing.append(p)
        existing_map[n] = p
        added.append(p.get('internalName', p.get('name', '?')))
    else:
        # Var ama URL farkliysa guncelle (Kraptor daha yeni versiyona sahipse)
        our = existing_map[n]
        kraptor_url = p.get('url', '')
        our_url = our.get('url', '')
        our_ver = our.get('version', 0)
        kraptor_ver = p.get('version', 0)

        if kraptor_url and kraptor_url != our_url and kraptor_ver >= our_ver:
            # Kraptor URL'sine guncelle
            idx = existing.index(our)
            existing[idx] = p
            existing_map[n] = p
            updated.append(f"{p.get('internalName', '?')} v{our_ver}->v{kraptor_ver}")

# Kaydet
with open('prebuilt_plugins.json', 'w', encoding='utf-8') as f:
    json.dump(existing, f, indent=4, ensure_ascii=False)

print(f'\nEklenen ({len(added)}):')
for a in added:
    print(f'  + {a}')

print(f'\nGuncellenen URL ({len(updated)}):')
for u in updated:
    print(f'  ~ {u}')

print(f'\nToplam prebuilt_plugins.json: {len(existing)} eklenti')
