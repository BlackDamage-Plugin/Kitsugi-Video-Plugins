import os
import json
import zipfile
import shutil

ROOT = r"c:\Kitsugi-Beta\Kitsugi-Plugins"
PREBUILT_DIR = os.path.join(ROOT, "prebuilt")
EXCLUDE_DIRS = {
    "gradle", "build", ".git", ".github", ".gradle", ".kotlin",
    ".idea", ".vscode", "prebuilt", "__pycache__", "__Temel", "__New"
}

def get_existing_dirs():
    dirs = {}
    for name in os.listdir(ROOT):
        path = os.path.join(ROOT, name)
        if not os.path.isdir(path):
            continue
        if name.startswith(".") or name in EXCLUDE_DIRS:
            continue
        dirs[name.lower()] = name
    return dirs

def main():
    existing = get_existing_dirs()
    print(f"Found {len(existing)} existing directories in root.")
    
    if not os.path.exists(PREBUILT_DIR):
        print("Prebuilt directory not found.")
        return

    processed = 0
    skipped = 0
    
    gradle_template = """import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

tasks.named("make") {
    doLast {
        val buildDir = layout.buildDirectory.get().asFile
        buildDir.mkdirs()
        val cs3File = File(buildDir, "${project.name}.cs3")
        val zipFile = ZipOutputStream(cs3File.outputStream())
        listOf("manifest.json", "classes.dex").forEach { fileName ->
            val file = file(fileName)
            if (file.exists()) {
                zipFile.putNextEntry(ZipEntry(fileName))
                file.inputStream().use { it.copyTo(zipFile) }
                zipFile.closeEntry()
            }
        }
        zipFile.close()
    }
}
"""

    manifest_template = """<?xml version="1.0" encoding="utf-8"?>
<manifest />
"""

    for filename in os.listdir(PREBUILT_DIR):
        if not filename.endswith(".cs3"):
            continue
        
        plugin_name = filename[:-4]
        plugin_lower = plugin_name.lower()
        
        # Check if it already exists and is a real source folder (has kotlin files/folders)
        is_real_source = False
        if plugin_lower in existing:
            real_name = existing[plugin_lower]
            plugin_dir = os.path.join(ROOT, real_name)
            if os.path.exists(os.path.join(plugin_dir, "classes.dex")):
                is_real_source = False
            else:
                is_real_source = True

        if is_real_source:
            print(f"Skipping {plugin_name} (already exists as real source project {existing[plugin_lower]})")
            skipped += 1
            continue
            
        cs3_path = os.path.join(PREBUILT_DIR, filename)
        plugin_dir = os.path.join(ROOT, plugin_name)
        
        # Clean previous generated content in case package structure changed
        src_dir = os.path.join(plugin_dir, "src")
        if os.path.exists(src_dir):
            shutil.rmtree(src_dir, ignore_errors=True)
            
        os.makedirs(plugin_dir, exist_ok=True)
        
        # Extract manifest.json and classes.dex
        plugin_class_name = None
        try:
            with zipfile.ZipFile(cs3_path, 'r') as zf:
                # Parse manifest to get package and class name
                manifest_content = zf.read("manifest.json").decode("utf-8")
                manifest_json = json.loads(manifest_content)
                plugin_class_name = manifest_json.get("pluginClassName")
                
                for item in ["manifest.json", "classes.dex"]:
                    if item in zf.namelist():
                        zf.extract(item, plugin_dir)
                    else:
                        print(f"  Warning: {item} not found in {filename}")
        except Exception as e:
            print(f"  Error extracting {filename}: {e}")
            shutil.rmtree(plugin_dir, ignore_errors=True)
            continue
            
        if not plugin_class_name:
            print(f"  Error: Could not parse pluginClassName from manifest for {plugin_name}")
            shutil.rmtree(plugin_dir, ignore_errors=True)
            continue
            
        # Parse package and class
        parts = plugin_class_name.split(".")
        if len(parts) < 2:
            print(f"  Error: Invalid pluginClassName '{plugin_class_name}' in {plugin_name}")
            continue
        package_name = ".".join(parts[:-1])
        class_name = parts[-1]
            
        # Create src/main/AndroidManifest.xml
        manifest_dir = os.path.join(plugin_dir, "src", "main")
        os.makedirs(manifest_dir, exist_ok=True)
        with open(os.path.join(manifest_dir, "AndroidManifest.xml"), "w", encoding="utf-8") as f:
            f.write(manifest_template)
            
        # Create src/main/kotlin/[package]/[Class].kt to trigger Gradle compiler
        kotlin_source_dir = os.path.join(plugin_dir, "src", "main", "kotlin", *parts[:-1])
        os.makedirs(kotlin_source_dir, exist_ok=True)
        kotlin_file_path = os.path.join(kotlin_source_dir, f"{class_name}.kt")
        with open(kotlin_file_path, "w", encoding="utf-8") as f:
            f.write(f"""package {package_name}

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class {class_name}: Plugin()
""")

        # Create build.gradle.kts
        with open(os.path.join(plugin_dir, "build.gradle.kts"), "w", encoding="utf-8") as f:
            f.write(gradle_template)
            
        print(f"Created subproject folder for {plugin_name} (Class: {plugin_class_name})")
        processed += 1
        
    print(f"\nDone. Processed: {processed}, Skipped: {skipped}")

if __name__ == "__main__":
    main()
