import zipfile
import os

cs3_path = r"c:\Kitsugi-Beta\Kitsugi-Plugins\prebuilt\Anizium.cs3"
if os.path.exists(cs3_path):
    print(f"Inspecting {cs3_path}:")
    with zipfile.ZipFile(cs3_path, 'r') as z:
        for name in z.namelist():
            print(f"  {name}")
else:
    print(f"File not found: {cs3_path}")
