import sys, zipfile
from pathlib import Path

src, out = Path(sys.argv[1]), Path(sys.argv[2])
# The release APK of this version. Stale ones from earlier versions tend to linger in module/
# and deleting them first is not always possible (host policies refuse), while shipping two APKs
# makes KernelSU install both and verify_aura.py reject the archive. So filter instead of delete.
keep = sys.argv[3] if len(sys.argv) > 3 else None
if out.exists():
    out.unlink()
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for p in sorted(src.rglob("*")):
        if not p.is_file():
            continue
        rel = p.relative_to(src).as_posix()
        if keep and rel.startswith("omnicam-aura-") and rel.endswith(".apk") and rel != keep:
            print("skipped stale apk:", rel)
            continue
        z.write(p, rel)
with zipfile.ZipFile(out) as z:
    assert z.testzip() is None
    assert all("\\" not in n for n in z.namelist())
print(out, out.stat().st_size)
