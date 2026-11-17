"""Runs Qwen over sample.jsonl as calibration.md §2 specifies and stores each full response (§6).

Usage: python3 run.py [--keys k1,k2|--regression] [--parallel N] [--out responses] [--no-logprobs] [--force]
LCT passages run before their question parts, whose {{PASSAGE}} is the passage as transcribed (or the user's fix).
"""
import argparse, base64, hashlib, io, json, os, re, threading, time, urllib.request
from concurrent.futures import ThreadPoolExecutor
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
PROMPT_FILE = '/Users/sandeep/projects/sconsole/QImgClassifier/src/main/resources/apps/transcriber/qtranscriber.prompt'
URL = 'http://mac-studio.local:11434/api/generate'
MODEL = 'qwen3.5:27b-q8_0'
OPTIONS = {'temperature': 0, 'top_k': 1, 'presence_penalty': 0, 'frequency_penalty': 0, 'repeat_penalty': 1,
           'num_ctx': 8192, 'num_predict': 4096}


def load_prompt():
    raw = open(PROMPT_FILE, encoding='utf-8').read()
    sec, cur = {}, None
    for line in raw.splitlines():
        if line.startswith('#'):
            continue
        m = re.match(r'^=== (.+) ===$', line)
        if m:
            cur = m.group(1)
            sec[cur] = []
        elif cur:
            sec[cur].append(line)
    return {k: '\n'.join(v).strip() for k, v in sec.items()}, hashlib.sha256(raw.encode('utf-8')).hexdigest()


def fill(sec, item, passage=None):
    typ = item['type']
    note = sec[f'TYPE {typ}']
    name = note.split('.')[0]
    task = {'question': 'TASK QUESTION', 'lct-passage': 'TASK LCT_PASSAGE', 'lct-question': 'TASK LCT_QUESTION'}[item['kind']]
    task = sec[task].replace('{{PASSAGE}}', passage or '')
    return (sec['PROMPT'].replace('{{SUBJECT}}', item['subject']).replace('{{TYPE}}', name)
            .replace('{{SUBJECT_NOTE}}', sec.get(f"SUBJECT {item['subject']}", ''))
            .replace('{{TYPE_NOTE}}', note).replace('{{TASK}}', task))


def to_rgb(im):
    if im.mode in ('I;16', 'I;16B', 'I;16L', 'I'):  # 16-bit greyscale: scale, don't clip
        im = im.point(lambda v: v / 256).convert('L')
    if im.mode in ('RGBA', 'LA', 'P'):
        im = im.convert('RGBA')
        bg = Image.new('RGBA', im.size, 'white')
        bg.alpha_composite(im)
        im = bg
    return im.convert('RGB')


def image_bytes(paths):
    """One image as stored, or the parts stacked top to bottom, left-aligned, white, no gap (ImageCollator)."""
    if len(paths) == 1:
        return open(paths[0], 'rb').read(), False
    ims = [to_rgb(Image.open(p)) for p in paths]
    out = Image.new('RGB', (max(i.width for i in ims), sum(i.height for i in ims)), 'white')
    y = 0
    for i in ims:
        out.paste(i, (0, y))
        y += i.height
    buf = io.BytesIO()
    out.save(buf, 'PNG')
    return buf.getvalue(), True


def passage_for(item, outdir):
    marks = os.path.join(HERE, 'marks.jsonl')
    ctx_key = item['lctCtxKey']
    if os.path.exists(marks):
        for line in open(marks):
            m = json.loads(line)
            if m.get('key') == ctx_key and m.get('fix'):
                return m['fix'], 'fix'
    r = json.load(open(os.path.join(outdir, f'{ctx_key}.json')))
    return r['response']['response'], 'qwen'


def run_one(item, sec, sha, outdir, logprobs, force):
    path = os.path.join(outdir, f"{item['key']}.json")
    if os.path.exists(path) and not force:
        return None
    passage, passage_src = (passage_for(item, outdir) if item['kind'] == 'lct-question' else (None, None))
    prompt = fill(sec, item, passage)
    data, collated = image_bytes(item['paths'])
    if collated:
        os.makedirs(os.path.join(HERE, 'images'), exist_ok=True)
        open(os.path.join(HERE, 'images', f"{item['key']}.png"), 'wb').write(data)
    body = {'model': MODEL, 'prompt': prompt, 'images': [base64.b64encode(data).decode()], 'stream': False,
            'think': False, 'options': OPTIONS}
    if logprobs:
        body.update(logprobs=True, top_logprobs=5)
    t = time.time()
    req = urllib.request.Request(URL, json.dumps(body).encode(), {'Content-Type': 'application/json'})
    resp = json.load(urllib.request.urlopen(req, timeout=1800))
    wall = time.time() - t
    record = {'key': item['key'], 'prompt': prompt, 'promptSha256': sha, 'imageFiles': item['images'],
              'collatedImage': f"images/{item['key']}.png" if collated else None,
              'passageSource': passage_src, 'wallSeconds': round(wall, 2),
              'settings': {k: v for k, v in body.items() if k not in ('prompt', 'images')}, 'response': resp}
    tmp = path + '.tmp'
    json.dump(record, open(tmp, 'w'), ensure_ascii=False)
    os.replace(tmp, path)
    return wall, resp.get('eval_count', 0), resp.get('done_reason')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--keys')
    ap.add_argument('--regression', action='store_true')
    ap.add_argument('--parallel', type=int, default=1)
    ap.add_argument('--out', default='responses')
    ap.add_argument('--no-logprobs', action='store_true')
    ap.add_argument('--force', action='store_true')
    args = ap.parse_args()
    outdir = os.path.join(HERE, args.out)
    os.makedirs(outdir, exist_ok=True)
    sec, sha = load_prompt()
    items = [json.loads(l) for l in open(os.path.join(HERE, 'sample.jsonl'))]
    ctx_key = {i['lctCtxId']: i['key'] for i in items if i['kind'] == 'lct-passage'}
    for i in items:
        i['lctCtxKey'] = ctx_key.get(i.get('lctCtxId'))
    if args.keys:
        want = set(args.keys.split(','))
        items = [i for i in items if i['key'] in want]
    elif args.regression:
        items = [i for i in items if i['regression']]
    lock = threading.Lock()
    done = [0]
    t0 = time.time()

    def job(item):
        try:
            r = run_one(item, sec, sha, outdir, not args.no_logprobs, args.force)
        except Exception as e:
            r = ('ERROR', repr(e)[:200])
        with lock:
            done[0] += 1
            print(f"[{done[0]}/{len(items)}] {item['key']:>14} {item['kind']:12} {item['subject'][:4]} {item['type']} -> {r}", flush=True)
        return r

    passages = [i for i in items if i['kind'] == 'lct-passage']
    rest = [i for i in items if i['kind'] != 'lct-passage']
    results = []
    with ThreadPoolExecutor(args.parallel) as ex:
        results += list(ex.map(job, passages))
        results += list(ex.map(job, rest))
    ran = [r for r in results if r and r[0] != 'ERROR']
    total = time.time() - t0
    toks = sum(r[1] for r in ran)
    print(f'ran {len(ran)} in {total:.0f}s | {toks} output tokens | {toks / max(total, 1):.1f} tok/s total | '
          f'{total / max(len(ran), 1):.1f} s/question | prompt sha256 {sha}')


if __name__ == '__main__':
    main()
