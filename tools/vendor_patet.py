"""Put the PATET repository's files into the app's protocol/ folder (a plain copy, with a note saying which commit)."""
import os, shutil, subprocess, sys
SRC, DST = r'C:\Users\LYS\patet', r'C:\Users\LYS\KITE\protocol'
SKIP_DIRS = {'.git', 'target', 'node_modules', '__pycache__', '.pytest_cache', 'dist'}
for name in os.listdir(DST):
    p = os.path.join(DST, name)
    shutil.rmtree(p) if os.path.isdir(p) else os.remove(p)
n = 0
for root, dirs, files in os.walk(SRC):
    dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.endswith('.egg-info')]
    rel = os.path.relpath(root, SRC)
    for f in files:
        if f in ('Cargo.lock', '.gitignore', '.gitattributes') or f.endswith('.pyc'): continue
        os.makedirs(os.path.join(DST, rel), exist_ok=True)
        shutil.copy2(os.path.join(root, f), os.path.join(DST, rel, f)); n += 1
rev = subprocess.run(['git', '-C', SRC, 'rev-parse', '--short', 'HEAD'], capture_output=True, text=True).stdout.strip()
dirty = subprocess.run(['git', '-C', SRC, 'status', '--porcelain'], capture_output=True, text=True).stdout.strip()
open(os.path.join(DST, 'VENDORED.md'), 'w', encoding='utf-8', newline='\n').write(
    f"# A copy of PATET\n\nThis folder is a copy of [PATET](https://github.com/fidenit/patet), the protocol this app is built on, at commit `{rev}`{' plus uncommitted changes' if dirty else ''}.\n"
    "Do not edit it here: change PATET, then copy it over again (`python tools/vendor_patet.py` with the PATET repository beside this one).\n"
    "The app builds its Rust code from `rust/`, gives `GUIDE.md` to outside agents, and runs `conformance/` in its tests.\n")
print('copied', n, 'files at', rev, 'dirty' if dirty else 'clean')
