import json, urllib.request

# Mevcut prebuilt_plugins.json'u yukle
with open('prebuilt_plugins.json', encoding='utf-8') as f:
    existing = json.load(f)

existing_names = set()
for p in existing:
    n = (p.get('internalName') or p.get('name') or '').lower().strip()
    if n:
        existing_names.add(n)

# Kraptor reposundan tum eklentileri cek
url = 'https://raw.githubusercontent.com/Kraptor123/cs-kraptor/builds/plugins.json'
with urllib.request.urlopen(url, timeout=15) as r:
    kraptor = json.loads(r.read().decode('utf-8'))

missing = []
for p in kraptor:
    n = (p.get('internalName') or p.get('name') or '').lower().strip()
    if n and n not in existing_names:
        missing.append(p)

print(f'Toplam Kraptor: {len(kraptor)}')
print(f'Bizde mevcut: {len(existing_names)}')
print(f'Eksik: {len(missing)}')
print()
for p in missing:
    name = p.get('internalName', p.get('name', '?'))
    lang = p.get('language', '?')
    status = p.get('status', '?')
    types = ','.join(p.get('tvTypes', []))
    print(f'  - {name} | lang={lang} | status={status} | types={types}')

# Eksikleri prebuilt_plugins.json'a ekle
added = 0
for p in missing:
    existing.append(p)
    added += 1

with open('prebuilt_plugins.json', 'w', encoding='utf-8') as f:
    json.dump(existing, f, indent=4, ensure_ascii=False)

print(f'\n{added} eklenti prebuilt_plugins.json\'a eklendi.')
