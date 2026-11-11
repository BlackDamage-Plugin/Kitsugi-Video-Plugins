import json
import os

def main():
    compiled_json_path = "build/plugins.json"
    prebuilt_json_path = "prebuilt_plugins.json"
    
    # Path to existing plugins catalogue in builds branch checkout
    builds_workspace = os.environ.get("GITHUB_WORKSPACE", ".")
    builds_json_path = os.path.join(builds_workspace, "builds", "plugins.json")
    if not os.path.exists(builds_json_path):
        # Fallback to local builds directory if running locally
        builds_json_path = os.path.join(".", "builds", "plugins.json")

    existing_builds_plugins = []
    if os.path.exists(builds_json_path):
        try:
            with open(builds_json_path, "r", encoding="utf-8") as f:
                existing_builds_plugins = json.load(f)
                print(f"Loaded {len(existing_builds_plugins)} existing plugins from builds catalogue.")
        except Exception as e:
            print(f"Error loading existing builds plugins.json: {e}")

    prebuilt_plugins = []
    if os.path.exists(prebuilt_json_path):
        try:
            with open(prebuilt_json_path, "r", encoding="utf-8") as f:
                prebuilt_plugins = json.load(f)
                print(f"Loaded {len(prebuilt_plugins)} prebuilt plugins.")
        except Exception as e:
            print(f"Error loading prebuilt_plugins.json: {e}")

    compiled_plugins = []
    if os.path.exists(compiled_json_path):
        try:
            with open(compiled_json_path, "r", encoding="utf-8") as f:
                compiled_plugins = json.load(f)
                print(f"Loaded {len(compiled_plugins)} newly compiled plugins.")
        except Exception as e:
            print(f"Error loading compiled plugins.json: {e}")

    # Merge hierarchy: existing builds -> prebuilt -> newly compiled overrides
    combined = {}
    for p in existing_builds_plugins:
        name = p.get("internalName") or p.get("name")
        if name:
            combined[name] = p

    for p in prebuilt_plugins:
        name = p.get("internalName") or p.get("name")
        if name:
            combined[name] = p

    for p in compiled_plugins:
        name = p.get("internalName") or p.get("name")
        if name:
            combined[name] = p

    try:
        os.makedirs(os.path.dirname(compiled_json_path), exist_ok=True)
        with open(compiled_json_path, "w", encoding="utf-8") as f:
            json.dump(list(combined.values()), f, indent=4, ensure_ascii=False)
        print(f"Successfully generated merged plugins.json with {len(combined)} total plugins.")
    except Exception as e:
        print(f"Error writing merged plugins.json: {e}")

if __name__ == '__main__':
    main()

