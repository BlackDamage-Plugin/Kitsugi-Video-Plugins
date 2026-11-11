import json, urllib.request

with open('prebuilt_plugins.json', encoding='utf-8') as f:
    existing = json.load(f)

# Kraptor reposundan tum eklentileri cek
url = 'https://raw.githubusercontent.com/Kraptor123/cs-kraptor/builds/plugins.json'
with urllib.request.urlopen(url, timeout=15) as r:
    kraptor = json.loads(r.read().decode('utf-8'))

# Bizde olan eklentilerin listesi (internalName bazli)
our_names_lower = {}
for p in existing:
    n = (p.get('internalName') or p.get('name') or '').lower().strip()
    if n:
        our_names_lower[n] = p.get('url', '')

# Kraptor'daki her eklentiyi kontrol et
print('=== KRAPTOR EKLENTILERININ DURUMU ===')
actually_missing = []
for p in kraptor:
    kname = (p.get('internalName') or p.get('name') or '').strip()
    klower = kname.lower()
    kurl = p.get('url', '')
    kver = p.get('version', 0)
    kstatus = p.get('status', 1)

    if klower not in our_names_lower:
        actually_missing.append(p)
        print(f'EKSIK: {kname} v{kver} status={kstatus}')
    else:
        our_url = our_names_lower[klower]
        if 'kraptor123' in our_url.lower():
            print(f'OK (Kraptor URL): {kname}')
        else:
            print(f'FARKLI URL: {kname} | bizde={our_url[:70]}')

print(f'\nGercekten eksik olan: {len(actually_missing)}')
for p in actually_missing:
    print(f'  + {p.get("internalName", p.get("name", "?"))} | status={p.get("status",1)} | lang={p.get("language","?")}')

# Eksikleri ekle
if actually_missing:
    for p in actually_missing:
        existing.append(p)
    with open('prebuilt_plugins.json', 'w', encoding='utf-8') as f:
        json.dump(existing, f, indent=4, ensure_ascii=False)
    print(f'\n{len(actually_missing)} eklenti prebuilt_plugins.json\'a eklendi!')
else:
    print('\nEklenecek bir sey yok.')
