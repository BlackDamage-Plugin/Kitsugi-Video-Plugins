import urllib.request
import zipfile
import tempfile
import os

url = "https://github.com/Kraptor123/cs-kraptor/archive/refs/heads/builds.zip"
print(f"Downloading {url} ...")
req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
try:
    with urllib.request.urlopen(req) as response:
        zip_data = response.read()
except Exception as e:
    print(f"Failed builds branch: {e}")
    sys.exit(1)

temp_dir = tempfile.mkdtemp()
zip_path = os.path.join(temp_dir, "repo.zip")
with open(zip_path, "wb") as f:
    f.write(zip_data)

with zipfile.ZipFile(zip_path, 'r') as zip_ref:
    for name in zip_ref.namelist():
        print(name)
