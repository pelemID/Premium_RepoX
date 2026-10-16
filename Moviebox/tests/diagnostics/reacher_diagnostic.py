#!/usr/bin/env python3
"""Read-only diagnostic, never imported by production. No raw HTTP/log files are saved."""
import argparse
import base64
import hashlib
import hmac
import json
from pathlib import Path
import re
import selectors
import shutil
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

BASE = 'https://api3.aoneroom.com'
PREFIX = '/wefeed-mobile-bff/subject-api/'
SOURCE = ('https://raw.githubusercontent.com/trinityzanetamanu-code/Premium_RepoX/'
          '9d33848994275635a0e12acf0d81253f4fe927be/Moviebox/src/main/kotlin/com/Moviebox/MovieboxProvider.kt')
SOURCE_HASH = '7bbc358a31a7f3f874cb35d0fba0140c6e9d662e6c6904a6dcb85e560c20df0c'
SEED = '3620534210064175096'
FIELDS = ('subjectId','dubs','seasons','items','streams','dash','hls','resourceDetectors',
          'detailPath','detailUrl','hasResource','playUrl','pager','resourceId','resolutionList')


def objects(value):
    return [item for item in value if isinstance(item,dict)] if isinstance(value,list) else []


def identity(value):
    if type(value) not in (str,int): return 'UNKNOWN'
    value = str(value)
    return value if re.fullmatch(r'[A-Za-z0-9_-]{1,96}', value) else 'UNKNOWN'


def subject(value):
    if value.isdigit():
        return value
    u = urllib.parse.urlsplit(value)
    if u.scheme not in ('http','https'):
        raise ValueError('ID atau tautan HTTP(S) diperlukan')
    q = urllib.parse.parse_qs(u.query)
    sid = q.get('subjectId', q.get('id', ['']))[0]
    if not sid.isdigit():
        raise ValueError('Tautan tidak memiliki subjectId/id numerik')
    return sid


def category(dub):
    # Diagnostic selection only. Production audio whitelist remains untouched.
    if dub.get('original') is True:
        return 'ORIGINAL'
    groups = []
    for key in ('lanName','lanCode','lan','language'):
        raw = dub.get(key)
        if not isinstance(raw,str) or not raw.strip():
            continue
        clean = re.sub(r'\b(dub(?:bed)?|audio)\b','',raw,flags=re.I)
        clean = ' '.join(clean.replace('(',' ').replace(')',' ').lower().split())
        groups.append('ORIGINAL' if clean in ('original','default','asli') else
                      'INDONESIAN' if clean in ('id','ind','in_id','indonesia','indonesian','bahasa indonesia') else
                      'ENGLISH' if clean in ('en','eng','english','inggris') else 'UNSUPPORTED')
    return groups[0] if groups and len(set(groups)) == 1 else 'UNSUPPORTED'


def detail_path(data):
    for key in ('detailPath','detailUrl'):
        raw = data.get(key)
        if isinstance(raw,str):
            try: path = urllib.parse.urlsplit(raw).path.rstrip('/').rsplit('/',1)[-1]
            except ValueError: continue
            if re.fullmatch(r'[A-Za-z0-9_-]{1,240}',path):
                return path
    return 'UNKNOWN'


def summary(data):
    """Positive allowlist, not a recursive redactor: credentials/URLs cannot pass through."""
    if not isinstance(data,dict):
        return {'schema':'NON_OBJECT'}
    out = {'schema':{k:('array' if isinstance(data[k],list) else type(data[k]).__name__)
                     for k in FIELDS if k in data}}
    for key in ('subjectId','resourceId'):
        if key in data:
            out[key] = identity(data[key])
    for key in ('hasResource','vipLocked','limited'):
        if type(data.get(key)) is bool:
            out[key] = data[key]
    for key in ('streams','dash','hls','resourceDetectors','items'):
        if isinstance(data.get(key),list):
            out[key+'_count'] = len(data[key])
    title = data.get('title',data.get('subjectTitle'))
    if isinstance(title,str) and re.fullmatch(r"[\w \[\]():.,'’+-]{1,160}",title) and not re.search(r'bearer|cookie|password',title,re.I):
        out['title'] = title
    date = data.get('releaseDate')
    if isinstance(date,str) and re.match(r'^\d{4}(?:-|$)',date): out['year'] = int(date[:4])
    out['detailPath'] = detail_path(data)
    if isinstance(data.get('dubs'),list):
        out['dubs'] = [{'subjectId':identity(d.get('subjectId','')), 'category':category(d),
                        'original':d.get('original') is True}
                       for d in data['dubs'][:50] if isinstance(d,dict)]
    if isinstance(data.get('seasons'),list):
        out['seasons'] = []
        for s in data['seasons'][:30]:
            if not isinstance(s,dict): continue
            item = {k:s[k] for k in ('se','maxEp') if type(s.get(k)) is int}
            # Preserve sparse episode mapping; maxEp alone is not proof of a resource.
            eps = s.get('allEp')
            if isinstance(eps,str) and re.fullmatch(r'[0-9, -]{0,3000}',eps): item['allEp'] = eps
            out['seasons'].append(item)
    if isinstance(data.get('items'),list):
        out['resources'] = []
        for d in data['items'][:30]:
            if isinstance(d,dict):
                out['resources'].append({'resourceId':identity(d.get('resourceId','')),
                    **{k:d[k] for k in ('se','ep','resolution','linkType') if type(d.get(k)) is int},
                    'url_present':isinstance(d.get('url'),str) and bool(d['url']),
                    'media_validated':'NOT_TESTED'})
    play = data.get('playUrl')
    if isinstance(play,dict):
        typ = play.get('urlType')
        out['play_url_type'] = typ if typ in ('PLAY_URL_ANDROID','PLAY_URL_WEB') else 'UNKNOWN'
        out['play_url_present'] = bool(play.get('playUrl'))
    return out


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs): return None


class Guest:
    """Existing catalog guest protocol; not a capture of APK 50020133 or a user session."""
    def __init__(self):
        self.key = None
        self.token = None
        self.instance = uuid.uuid4().hex  # New diagnostic instance, never borrowed or saved.
        self.opener = urllib.request.build_opener(NoRedirect())

    def configure(self):
        # Read the already-public protocol key in memory. Never bundle/cache provider source,
        # dormant device-bound profiles, credentials, keys, headers or response bodies.
        with urllib.request.urlopen(SOURCE,timeout=12) as r: raw = r.read(262145)
        if hashlib.sha256(raw).hexdigest() != SOURCE_HASH:
            raise ValueError('PINNED_SOURCE_HASH_MISMATCH')
        text = raw.decode()
        match = re.search(r'private val SECRET_BYTES: ByteArray by lazy\s*\{\s*val step1 = String\(Base64.decode\("([A-Za-z0-9+/=]+)"',text)
        if not match: raise ValueError('PINNED_PROTOCOL_NOT_FOUND')
        self.key = base64.b64decode(base64.b64decode(match[1]))

    def get(self,path,params):
        if path not in (PREFIX+'get',PREFIX+'dub-info',PREFIX+'season-info',PREFIX+'resource',
                        '/wefeed-mobile-bff/tab/ranking-list'):
            raise ValueError('ENDPOINT_NOT_ALLOWED')
        query = urllib.parse.urlencode(params)
        canonical_path = path + ('?'+query if query else '')
        ts = str(int(time.time()*1000))
        canon = '\n'.join(('GET','application/json','application/json','',ts,'',canonical_path))
        info = {'package_name':'com.community.oneroom','version_name':'3.0.13.0325.03',
                'version_code':50020088,'os':'android','os_version':'13','device_id':self.instance,
                'install_store':'ps','system_language':'en','net':'NETWORK_WIFI','region':'US',
                'timezone':'Asia/Calcutta','sp_code':''}
        headers = {'User-Agent':'com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Samsung; Build/TQ3A.230901.001)',
                   'Accept':'application/json','Content-Type':'application/json',
                   'x-client-token':ts+','+hashlib.md5(ts[::-1].encode()).hexdigest(),
                   'x-tr-signature':ts+'|2|'+base64.b64encode(hmac.new(self.key,canon.encode(),hashlib.md5).digest()).decode(),
                   'x-client-info':json.dumps(info,separators=(',',':')),'x-client-status':'0'}
        if self.token: headers['Authorization'] = 'Bearer '+self.token
        try:
            with self.opener.open(urllib.request.Request(BASE+canonical_path,headers=headers),timeout=8) as r:
                code = r.status; blob = r.read(262145); user = r.headers.get('x-user','')
        except urllib.error.HTTPError as e:
            code = e.code; blob = e.read(262145); user = ''
        except Exception as e:
            return {'http':'UNKNOWN','error':type(e).__name__}, {}
        if len(blob)>262144: return {'http':code,'error':'RESPONSE_TOO_LARGE'}, {}
        try: root = json.loads(blob)
        except (ValueError,UnicodeError): return {'http':code,'schema':'NON_JSON'}, {}
        if not isinstance(root,dict): return {'http':code,'schema':'NON_OBJECT'}, {}
        if path.endswith('ranking-list') and code == 200:
            try:
                tok = json.loads(user).get('token')
                if isinstance(tok,str): self.token = tok
            except (ValueError,AttributeError): pass
        rc = str(root.get('code','UNKNOWN'))
        record = {'http':code,'code':rc if re.fullmatch(r'-?\d{1,6}',rc) else 'UNKNOWN'}
        data = root.get('data',{})
        if code == 200 and rc == '0': record['data'] = summary(data)
        return record, data if code == 200 and rc == '0' and isinstance(data,dict) else {}


def episode_declared(data,se,ep):
    for s in objects(data.get('seasons')):
        if not isinstance(s,dict) or s.get('se') != se: continue
        # Match the APK's episode index construction. Declaration is NOT media availability.
        raw = s.get('allEp')
        if isinstance(raw,str) and raw.strip():
            return str(ep) in [p.strip() for p in raw.split(',')]
        maximum = s.get('maxEp')
        return type(maximum) is int and ((se == 0 and ep == 0 and maximum == 0) or 1 <= ep <= maximum)
    return False


def denied(record):
    return record.get('http') in (401,403,441) or str(record.get('code')) in ('401','403','441')


def probe(client,seeds):
    report = {'request_profile':'existing provider catalog guest; NOT official APK runtime parity',
              'device_playback':'NOT_TESTED','media_validation':'NOT_TESTED','requests':[],'relationships':[]}
    def ask(endpoint,sid,params=None):
        record,data = client.get(PREFIX+endpoint,params or {'subjectId':sid})
        record = {'endpoint':endpoint,'subjectId':sid,**record}
        report['requests'].append(record)
        return record,data
    for sid,se,ep in seeds:
        first,dubs = ask('dub-info',sid)
        if denied(first):
            report['requests'][-1]['stop_reason'] = 'ACCESS_DENIED_NO_HOST_OR_REGION_WORKAROUND'
            continue
        related = [(sid,'SELECTED')]
        seen = {sid}
        for d in objects(dubs.get('dubs'))[:50]:
            if not isinstance(d,dict): continue
            target = identity(d.get('subjectId','')); cat = category(d)
            if target.isdigit() and cat != 'UNSUPPORTED':
                report['relationships'].append({'from':sid,'to':target,'category':cat,'evidence':'dub-info'})
                if target not in seen and len(related)<4:
                    related.append((target,cat)); seen.add(target)
        for target,cat in related:
            meta,detail = ask('get',target)
            if denied(meta): continue
            seasons,season_data = ask('season-info',target)
            if denied(seasons): continue
            report['requests'][-1]['audio_context'] = cat
            # Default Reacher S1E1; S1E2 only when explicitly present in allEp.
            episodes = [ep] + ([ep+1] if se == 1 and ep == 1 and episode_declared(season_data,se,ep+1) else [])
            for number in episodes:
                if not episode_declared(season_data,se,number): continue
                params = {'subjectId':target,'page':'1','perPage':10,'all':0,'startPosition':1,
                          'endPosition':1,'pagerMode':0,'resolution':0,'se':se,'epFrom':number,'epTo':number}
                rec,resource = ask('resource',target,params)
                rec['requested_se'] = se; rec['requested_ep'] = number
                # Resource URLs are download metadata, never promoted to playable streams here.
                same = identity(resource.get('subjectId','')) == target
                rec['exact_subject'] = same
                rec['exact_episode_resources'] = sum(isinstance(item,dict) and item.get('se')==se and item.get('ep')==number
                    for item in objects(resource.get('items'))) if same else 'UNKNOWN'
    return report


def sanitize_log(line):
    # Reconstruct allowlisted fields; never save an original log line.
    if 'MovieBox' not in line and 'Moviebox' not in line: return None
    out = {}
    for key,pattern in (('subjectId',r'\bsubject=(\d+)\b'),('se',r'\bse=(\d+)\b'),('ep',r'\bep=(\d+)\b'),
                        ('http',r'\bHTTP=(\d{3})\b'),('parsed',r'\bparsed=(\d+)\b'),
                        ('audioFiltered',r'\baudioFiltered=(\d+)\b'),('candidates',r'\bcandidates=(\d+)\b'),
                        ('validated',r'\bvalidated=(\d+)\b')):
        m = re.search(pattern,line)
        if m: out[key] = m[1] if key=='subjectId' else int(m[1])
    m = re.search(r'\bdetailPathAvailable=(true|false)\b',line)
    if m: out['detailPathAvailable'] = m[1]=='true'
    m = re.search(r'\[PLAYBACK\] ([A-Za-z0-9.-]+)(/wefeed-[A-Za-z0-9_/-]+) HTTP=',line)
    if m: out['host'] = m[1]; out['endpoint'] = m[2]
    return out or None


def capture(seconds):
    if not shutil.which('adb'): return {'status':'BLOCKED','reason':'ADB_NOT_INSTALLED','events':[]}
    p = subprocess.Popen(['adb','logcat','-T','1','-v','brief'],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
    events=[]; selector=selectors.DefaultSelector();selector.register(p.stdout,selectors.EVENT_READ)
    deadline=time.monotonic()+seconds
    try:
        while time.monotonic()<deadline and len(events)<3000:
            if selector.select(.2):
                line=p.stdout.readline(65536)
                if not line: break
                event=sanitize_log(line.decode('utf8','replace'))
                if event: events.append(event)
            elif p.poll() is not None: break
    finally:
        p.terminate()
        try: p.wait(timeout=2)
        except subprocess.TimeoutExpired: p.kill();p.wait()
        selector.close();p.stdout.close()
    return {'status':'PASS' if events else 'BLOCKED','events':events,
            'scope':'installed Moviebox plugin logs; NOT other-app HTTPS capture'}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--subject',default=SEED,help='Exact selected Reacher ID or shared URL')
    parser.add_argument('--control',help='Working control ID or shared URL (no title search)')
    parser.add_argument('--control-movie',action='store_true',help='Use 0/0 for the control')
    parser.add_argument('--adb-seconds',type=int,default=0)
    parser.add_argument('--logs-only',action='store_true',help='No network or guest session')
    parser.add_argument('--out',default=str(Path.home()/'storage/downloads' if (Path.home()/'storage/downloads').is_dir() else Path.cwd()))
    args=parser.parse_args(); seeds=[(subject(args.subject),1,1)]
    if args.control: seeds.append((subject(args.control),0 if args.control_movie else 1,0 if args.control_movie else 1))
    report={'version':1,'selections':[{'subjectId':sid,'se':se,'ep':ep} for sid,se,ep in seeds]}
    if not args.logs_only:
        client=Guest()
        try:
            client.configure()
            boot,_=client.get('/wefeed-mobile-bff/tab/ranking-list',{'page':1,'perPage':1,'tabId':0})
            report['bootstrap']={'http':boot.get('http'),'code':boot.get('code'),'guest_session_available':bool(client.token)}
            if denied(boot): report['probe']={'status':'BLOCKED','reason':'GUEST_BOOTSTRAP_DENIED'}
            else: report['probe']=probe(client,seeds)
        except Exception as e: report['probe']={'status':'BLOCKED','reason':type(e).__name__}
        finally: client.token=None;client.key=None
    if args.adb_seconds: report['log_capture']=capture(max(1,min(120,args.adb_seconds)))
    report['notice']='No raw responses, credentials, signed URLs, APK hooks or playback patches included.'
    out=Path(args.out);out.mkdir(parents=True,exist_ok=True)
    name='Reacher_Diagnostic_'+time.strftime('%Y%m%d_%H%M%S')+'_'+uuid.uuid4().hex[:6]+'.zip'
    with zipfile.ZipFile(out/name,'w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('diagnostic.json',json.dumps(report,indent=2))
    print(out/name)

if __name__=='__main__': main()
