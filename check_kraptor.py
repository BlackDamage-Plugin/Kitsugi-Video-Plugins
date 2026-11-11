import json

with open('prebuilt_plugins.json', encoding='utf-8') as f:
    d = json.load(f)

# Kraptor'dan gelenleri bul
kraptor_in_us = [p for p in d if 'kraptor123' in p.get('url','').lower() or 'kraptor123' in p.get('repositoryUrl','').lower()]
print(f'Kraptor kaynaklı eklenti sayisi: {len(kraptor_in_us)}')

# Anizium var mi kontrol et
anizium = [p for p in d if 'anizium' in (p.get('internalName','') + p.get('name','')).lower()]
print(f'Anizium mevcut mu: {len(anizium) > 0}')
for a in anizium:
    name = a.get('name', '?')
    url = a.get('url', '?')
    print(f'  name={name}  url={url}')

# Kraptor reposundan gelen ama Kraptor URL'si olmayan var mi?
import urllib.request
kraptor_url = 'https://raw.githubusercontent.com/Kraptor123/cs-kraptor/builds/plugins.json'
with urllib.request.urlopen(kraptor_url, timeout=15) as r:
    kraptor = json.loads(r.read().decode('utf-8'))

our_names = set()
for p in d:
    n = (p.get('internalName') or p.get('name') or '').lower().strip()
    if n:
        our_names.add(n)

print('\nKraptor eklentileri bizde olup olmadigi:')
missing_from_us = []
for p in kraptor:
    n = (p.get('internalName') or p.get('name') or '').lower().strip()
    in_us = n in our_names
    kraptor_name = p.get('internalName', p.get('name', '?'))
    # URL'leri kontrol et - bizde farkli URL ile mi var?
    our_versions = [x for x in d if (x.get('internalName') or x.get('name','')) == kraptor_name]
    our_url = our_versions[0].get('url','yok') if our_versions else 'YOK'
    kraptor_entry_url = p.get('url','?')
    if not in_us:
        missing_from_us.append(kraptor_name)
        print(f'  EKSIK: {kraptor_name}')
    elif our_url != kraptor_entry_url:
        print(f'  FARKLI URL: {kraptor_name}')
        print(f'    Bizde: {our_url}')
        print(f'    Kraptor: {kraptor_entry_url}')

print(f'\nToplam eksik: {len(missing_from_us)}')
