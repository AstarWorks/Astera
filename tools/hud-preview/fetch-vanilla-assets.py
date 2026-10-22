#!/usr/bin/env python3
"""Download official vanilla HUD/font assets for the catalog's Paper version; never redistribute the client jar."""
import hashlib
import io
import json
from pathlib import Path
import tomllib
from urllib.request import urlopen
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build/hud-preview/vanilla-assets.zip"


def fetch(url, sha1=None):
    with urlopen(url, timeout=120) as response:
        data = response.read()
    if sha1 is not None and hashlib.sha1(data).hexdigest() != sha1:
        raise ValueError(f"SHA-1 mismatch: {url}")
    return data


def main():
    version = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]["paper"].split(".build.")[0]
    manifest = json.loads(fetch("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
    entry = next(v for v in manifest["versions"] if v["id"] == version)
    metadata = json.loads(fetch(entry["url"], entry["sha1"]))
    client = metadata["downloads"]["client"]
    files = {}
    with ZipFile(io.BytesIO(fetch(client["url"], client["sha1"]))) as archive:
        for name in archive.namelist():
            if not name.endswith("/") and name.startswith(("assets/minecraft/font/", "assets/minecraft/textures/font/", "assets/minecraft/textures/gui/sprites/hud/")):
                files[name] = archive.read(name)
    index = metadata["assetIndex"]
    objects = json.loads(fetch(index["url"], index["sha1"]))["objects"]
    # The client jar's include/unifont.json is empty: the asset index supplies it and the hex archive.
    for name, obj in objects.items():
        if name.startswith(("minecraft/font/", "minecraft/textures/font/")):
            digest = obj["hash"]
            files["assets/" + name] = fetch(f"https://resources.download.minecraft.net/{digest[:2]}/{digest}", digest)
    files["hud-preview-source.json"] = json.dumps({"version": version, "clientSha1": client["sha1"], "assetIndexSha1": index["sha1"]}, indent=2).encode()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    temporary = OUT.with_suffix(".tmp")
    with ZipFile(temporary, "w", ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            archive.writestr(name, data)
    temporary.replace(OUT)
    print(OUT)


if __name__ == "__main__":
    main()
