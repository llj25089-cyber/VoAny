"""Resolve pinned source dependencies without replacing an existing checkout."""
import json, subprocess
from pathlib import Path

root=Path(__file__).resolve().parent
lock=json.loads((root/'upstream.lock.json').read_text())
for name,entry in lock.items():
    dest=root/'.upstream'/name
    def git(*args):
        return subprocess.check_output(['git','-c','safe.directory='+str(dest).replace('\\','/'),'-C',str(dest),*args],stderr=subprocess.STDOUT)
    if not dest.exists():
        dest.parent.mkdir(parents=True,exist_ok=True)
        subprocess.check_call(['git','clone',entry['url'],str(dest)])
        git('checkout','--detach',entry['commit'])
    actual=git('rev-parse','HEAD').decode().strip()
    if actual!=entry['commit']:raise SystemExit(name+' checkout differs from lock; preserve it and resolve explicitly')
    if 'patch' in entry:
        patch=str(root/entry['patch'])
        try:git('apply','--reverse','--check',patch)
        except subprocess.CalledProcessError:
            git('apply','--check',patch);git('apply',patch)
    print(name,actual)
