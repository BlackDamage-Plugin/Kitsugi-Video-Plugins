import urllib.request
import json

url = "https://api.github.com/repos/Kraptor123/cs-kraptor/branches"
req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
try:
    with urllib.request.urlopen(req) as response:
        branches = json.loads(response.read().decode('utf-8'))
        for b in branches:
            print(b['name'])
except Exception as e:
    print(f"Error fetching branches: {e}")
