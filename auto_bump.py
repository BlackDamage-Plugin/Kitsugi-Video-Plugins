import subprocess
import os
import re

def get_modified_plugins():
    try:
        output = subprocess.check_output(["git", "status", "--porcelain"], text=True, encoding="utf-8", errors="replace")
    except Exception as e:
        print(f"[auto_bump] Git status failed: {e}")
        return set()

    changed_plugins = set()
    for line in output.splitlines():
        if len(line) < 4:
            continue
        filepath = line[3:].strip().strip('"')
        parts = filepath.replace("\\", "/").split("/")
        plugin_dir = parts[0]
        skip_prefixes = (".", "_", "build", "gradle", "prebuilt")
        skip_files = {"KONTROL.py", "merge_plugins.py", "sync_prebuilt.py",
                      "sync_all_sources.py", "auto_bump.py", "github_push.bat",
                      "add_missing.py", "README.md", "LICENSE", "MiBox.md",
                      "settings.gradle.kts", "build.gradle.kts", "gradle.properties",
                      "local.properties", "gradlew", "gradlew.bat", "_config.yml"}
        if not any(plugin_dir.startswith(p) for p in skip_prefixes) and plugin_dir not in skip_files:
            changed_plugins.add(plugin_dir)

    return changed_plugins


def bump_plugin_version(plugin_dir):
    gradle_path = os.path.join(plugin_dir, "build.gradle.kts")
    if not os.path.isfile(gradle_path):
        return False

    with open(gradle_path, "r", encoding="utf-8") as f:
        content = f.read()

    pattern = re.compile(r'(^\s*version\s*=\s*)(\d+)', re.MULTILINE)
    match = pattern.search(content)
    if not match:
        return False

    old_version = int(match.group(2))
    new_version = old_version + 1
    new_content = pattern.sub(lambda m: m.group(1) + str(new_version), content, count=1)

    with open(gradle_path, "w", encoding="utf-8") as f:
        f.write(new_content)

    print(f"  [BUMPED] {plugin_dir}: version {old_version} -> {new_version}")
    return True


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    os.chdir(script_dir)

    print("[auto_bump] Detecting changed plugins...")
    changed = get_modified_plugins()

    if not changed:
        print("[auto_bump] No plugin changes detected.")
        return

    print(f"[auto_bump] {len(changed)} plugin(s) changed: {', '.join(sorted(changed))}")
    bumped = 0
    for plugin in sorted(changed):
        if bump_plugin_version(plugin):
            bumped += 1
        else:
            print(f"  [SKIP] {plugin}: no build.gradle.kts or no version line found")

    print(f"[auto_bump] Done: {bumped}/{len(changed)} plugin(s) version bumped.")


if __name__ == "__main__":
    main()