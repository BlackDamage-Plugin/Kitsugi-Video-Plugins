import zipfile
import json
import os

cs3_path = r"c:\Kitsugi-Beta\Kitsugi-Plugins\prebuilt\Anizium.cs3"
if os.path.exists(cs3_path):
    with zipfile.ZipFile(cs3_path, 'r') as z:
        manifest_data = z.read("manifest.json").decode("utf-8")
        print(manifest_data)
else:
    print(f"File not found: {cs3_path}")
