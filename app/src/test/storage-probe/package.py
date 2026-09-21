"""Package production debug DEX/native libraries into the isolated validation APK."""
import argparse
from pathlib import Path
import zipfile

p = argparse.ArgumentParser()
p.add_argument("--apk", type=Path, required=True)
p.add_argument("--work", type=Path, required=True)
p.add_argument("--demos", type=Path, required=True)
p.add_argument("--fixtures", type=Path, required=True)
args = p.parse_args()
with zipfile.ZipFile(args.work / "probe.apk", "a") as target:
    target.write(args.work / "dex/classes.dex", "classes.dex")
    with zipfile.ZipFile(args.apk) as app:
        for name in app.namelist():
            if name.startswith("lib/") or (name.startswith("classes") and name.endswith(".dex")):
                destination = name
                if name.startswith("classes"):
                    destination = f"classes{int(name[7:-4] or '1') + 1}.dex"
                target.writestr(destination, app.read(name))
    for game, files in [("cavestory", ["EBOOT.PBP", "data.csz"]), ("locoroco", ["EBOOT.PBP"])]:
        for name in files:
            target.write(args.demos / game / name, f"assets/{game}/{name}")
    for fixture in args.fixtures.iterdir():
        if fixture.suffix in (".iso", ".cso", ".chd"):
            target.write(fixture, f"assets/formats/{fixture.name}")
