#!/usr/bin/env python3
"""Check distributable identities and retain every previously listed plugin."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile

folder=Path(sys.argv[1])
before=json.loads(Path(sys.argv[2]).read_text()) if len(sys.argv)>2 else []
after=json.loads((folder/'plugins.json').read_text())
names={p['internalName'] for p in after}
assert {p['internalName'] for p in before} <= names, 'Previously published plugins missing'
expected={'AdiDrakor':'com.AdiDrakor','AdiFilmSemi':'com.michat88',
          'AdiXtream':'com.adixtream','Adicinemax21':'com.Adicinemax21','Moviebox':'com.Moviebox'}
for name,package in expected.items():
    item=next(p for p in after if p['internalName']==name)
    file=folder/(name+'.cs3')
    with zipfile.ZipFile(file) as archive:
        assert archive.testzip() is None
        manifest=json.loads(archive.read('manifest.json'))
        cls=package+'.'+('MovieboxPlugin' if name=='Moviebox' else name+'Plugin')
        assert manifest['pluginClassName']==cls, (name,manifest)
        assert manifest['name']==name
        assert manifest['version']==item['version']
        assert archive.read('classes.dex').startswith(b'dex\n')
    assert item['fileSize']==file.stat().st_size
    assert item['fileHash']=='sha256-'+hashlib.sha256(file.read_bytes()).hexdigest()
    print('PASS artifact',name,'version',item['version'],'single manifest entry',cls)
print('PASS distribution:',len(after),'plugins; all baseline identities preserved')
