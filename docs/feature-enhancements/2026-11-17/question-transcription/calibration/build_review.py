"""Builds review.html (calibration.md §3.4): each image beside its rendered transcription, with a marking panel.

Usage: python3 build_review.py [--responses responses]
The page loads images from the question bank by file:// path, so it is opened locally. Marks autosave in the
browser; Save downloads transcription-calibration-marks.json, which import_marks.py turns into marks.jsonl.
"""
import argparse, html, json, os, urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))


def file_url(p):
    return 'file://' + urllib.parse.quote(p)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--responses', default='responses')
    args = ap.parse_args()
    sample = [json.loads(l) for l in open(os.path.join(HERE, 'sample.jsonl'))]
    gates = {g['key']: g for g in map(json.loads, open(os.path.join(HERE, 'gate.jsonl')))}
    items = []
    passages = {}
    for s in sample:
        f = os.path.join(HERE, args.responses, f"{s['key']}.json")
        if not os.path.exists(f):
            continue
        r = json.load(open(f))
        md = r['response']['response']
        if s['kind'] == 'lct-passage':
            passages[s['lctCtxId']] = md
        imgs = [file_url(os.path.join(HERE, r['collatedImage']))] if r.get('collatedImage') else [file_url(p) for p in s['paths']]
        g = gates.get(s['key'], {})
        cands = [dict(line=c['line'], where=c['where'], chosen=c['chosen'], pChosen=c['pChosen'],
                      rival=c.get('mdRival', c['rival']) if c['contestedMd'] else c['rival'],
                      pRival=c.get('mdPRival', c['pRival']) if c['contestedMd'] else c['pRival'],
                      diff=c.get('mdDiff', c['diff']) if c['contestedMd'] else c['diff'],
                      md=c['contestedMd'], spec=c['contested'], ctx=c['continuation'])
                 for c in g.get('candidates', []) if c['contested']]
        items.append(dict(key=s['key'], kind=s['kind'], subject=s['subject'], type=s['type'], source=s['source'],
                          questionId=s['questionId'], regression=s['regression'], lctCtxId=s.get('lctCtxId'),
                          images=imgs, md=md, wellFormed=g.get('wellFormed', []), cands=cands,
                          seconds=r.get('wallSeconds'), tokens=r['response'].get('eval_count')))

    # Order: regression set, then by subject and type, each LCT group as passage then its parts.
    order = {'Physics': 0, 'Chemistry': 1, 'Mathematics': 2}
    types = ['SCA', 'MCA', 'IVT', 'NVT', 'ART', 'MMT', 'LCT']
    items.sort(key=lambda i: (not i['regression'], order[i['subject']], types.index(i['type']),
                              i.get('lctCtxId') or '', i['kind'] != 'lct-passage', i['key']))
    for i in items:
        if i['kind'] == 'lct-question':
            i['passage'] = passages.get(i['lctCtxId'], '')
    page = TEMPLATE.replace('__DATA__', json.dumps(items, ensure_ascii=False).replace('</', '<\\/'))
    open(os.path.join(HERE, 'review.html'), 'w').write(page)
    print(f'review.html: {len(items)} transcriptions')


TEMPLATE = r'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Transcription Calibration</title>
<script>
window.MathJax = { loader: { load: ['[tex]/mhchem'] },
  tex: { packages: { '[+]': ['mhchem'] }, inlineMath: [['$', '$']], displayMath: [['$$', '$$']] },
  startup: { typeset: false } };
</script>
<script defer src="https://cdn.jsdelivr.net/npm/mathjax@3.2.2/es5/tex-mml-chtml.js"></script>
<script src="https://cdn.jsdelivr.net/npm/markdown-it@14.1.0/dist/markdown-it.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/smiles-drawer@1.2.0/dist/smiles-drawer.min.js"></script>
<style>
:root { --bg:#f5f4f0; --card:#fff; --ink:#1d1d1b; --muted:#6b6a66; --line:#e2e0da; --accent:#2f5d8a;
  --ok:#e6f4ea; --okb:#2f7d45; --bad:#fce8ec; --badb:#b0304a; --unsure:#f1efe8; --diag:#f3f6fb; --code:#f1f0ec; }
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) { --bg:#171715; --card:#22221f; --ink:#ecebe7;
  --muted:#a3a29c; --line:#3a3935; --accent:#8bb4de; --ok:#1c3324; --okb:#7cc792; --bad:#3c1d25; --badb:#ef8aa0;
  --unsure:#2c2b27; --diag:#1f2733; --code:#2b2a26; } }
:root[data-theme="dark"] { --bg:#171715; --card:#22221f; --ink:#ecebe7; --muted:#a3a29c; --line:#3a3935; --accent:#8bb4de;
  --ok:#1c3324; --okb:#7cc792; --bad:#3c1d25; --badb:#ef8aa0; --unsure:#2c2b27; --diag:#1f2733; --code:#2b2a26; }
* { box-sizing:border-box } body { margin:0; background:var(--bg); color:var(--ink); font:15px/1.55 -apple-system,system-ui,sans-serif }
header.top { position:sticky; top:0; z-index:5; background:var(--bg); border-bottom:1px solid var(--line); padding:10px 16px }
.bar { height:8px; background:var(--line); border-radius:4px; overflow:hidden; margin:6px 0 }
.bar > div { height:100%; background:var(--okb); width:0 }
.tools { display:flex; flex-wrap:wrap; gap:8px; align-items:center; font-size:14px }
button { font:inherit; padding:5px 12px; border-radius:6px; border:1px solid var(--line); background:var(--card); color:var(--ink); cursor:pointer }
button.primary { background:var(--accent); color:#fff; border-color:var(--accent) }
main { max-width:1500px; margin:0 auto; padding:16px }
.card { background:var(--card); border:1px solid var(--line); border-radius:10px; margin-bottom:18px; overflow:hidden }
.card > .head { display:flex; flex-wrap:wrap; gap:6px 14px; align-items:baseline; padding:10px 14px; border-bottom:1px solid var(--line) }
.num { font-weight:700 } .meta { color:var(--muted); font-size:13px } .pill { font-size:12px; padding:1px 8px; border-radius:10px; background:var(--code) }
.pill.reg { background:var(--accent); color:#fff }
.cols { display:grid; grid-template-columns:minmax(0,1fr) minmax(0,1fr) 300px; }
@media (max-width:1100px) { .cols { grid-template-columns:1fr } }
.cols > div { padding:12px 14px; min-width:0 } .imgcol img { max-width:100%; background:#fff; border:1px solid var(--line); border-radius:4px; display:block; margin-bottom:6px }
.render { overflow-x:auto } .render table { border-collapse:collapse; margin:6px 0 } .render td, .render th { border:1px solid var(--line); padding:3px 8px; vertical-align:top }
.diagram { background:var(--diag); border:1px dashed var(--accent); border-radius:6px; padding:8px 10px; margin:8px 0; white-space:pre-wrap; font-size:14px }
.diagram .lbl { font-size:11px; text-transform:uppercase; letter-spacing:.05em; color:var(--accent); display:block; margin-bottom:2px }
.smiles { font-family:ui-monospace,monospace; font-size:13px } .smiles canvas { display:block; background:#fff; border-radius:4px; margin-top:4px }
details.raw pre, .passage { white-space:pre-wrap; word-break:break-word; background:var(--code); padding:8px; border-radius:6px; font-size:12px; max-height:300px; overflow:auto }
.passage { font-size:13px; max-height:200px }
.panel { border-left:1px solid var(--line); background:var(--unsure) } .panel.correct { background:var(--ok) } .panel.faulty { background:var(--bad) }
.panel label { display:block; margin:3px 0; cursor:pointer } .panel h4 { margin:8px 0 4px; font-size:12px; text-transform:uppercase; letter-spacing:.05em; color:var(--muted) }
.fault { display:grid; grid-template-columns:1fr; gap:4px; border:1px solid var(--line); border-radius:6px; padding:6px; margin:6px 0; background:var(--card) }
select, input[type=text], textarea { font:inherit; font-size:13px; width:100%; padding:4px 6px; border:1px solid var(--line); border-radius:5px; background:var(--card); color:var(--ink) }
textarea { min-height:70px; font-family:ui-monospace,monospace }
.gate { margin-top:10px; font-size:12px } .gate .c { font-family:ui-monospace,monospace; background:var(--card); border-radius:4px; padding:4px 6px; margin:3px 0; word-break:break-all }
.wf { color:var(--badb); font-size:12px } .fault.nosev { outline:2px solid var(--badb) } .hidden { display:none }
.help { font-size:13px; color:var(--muted); max-width:1500px; margin:0 auto; padding:10px 16px 0 }
</style></head><body>
<header class="top"><div class="tools">
<strong>Transcription calibration</strong><span id="count"></span>
<button id="next">Next unmarked</button><label><input type="checkbox" id="onlyUnmarked"> show unmarked only</label>
<span style="flex:1"></span><button class="primary" id="save">Save marks</button></div>
<div class="bar"><div id="prog"></div></div></header>
<p class="help">Mark each transcription against its image. <b>Correct</b>: every printed character, symbol and figure is there and right
(notation that renders the same, spacing and harmless line breaks are fine; a source typo copied faithfully is correct).
<b>Faulty</b>: add each fault with its kind, its <b>severity</b> and where it is. <b>Unsure</b>: can't tell from the image. A fix is optional.<br>
<b>Material</b>: changes what the tagger or a search would take from the question (a wrong or missing concept word, compound, structure,
reaction, statement, option's meaning, or figure content the question depends on; added text that brings in another idea).
<b>Minor</b>: still reads as the same problem (a wrong digit, exponent, sign, unit, subscript or symbol case, a stray character, spacing,
a layout slip that doesn't change the reading). Test: would a tag chosen from this text differ from one chosen from the image?<br>
After you mark a question, the gate's contested positions appear under the panel. Marks save in this browser as you go;
Save downloads them as <code>transcription-calibration-marks.json</code>.</p>
<main id="list"></main>
<script id="data" type="application/json">__DATA__</script>
<script>
const ITEMS = JSON.parse(document.getElementById('data').textContent);
const STORE = 'transcription-calibration-marks-v1';
const KINDS = [['misread','misread: a character, symbol, number or sign written as something else'],['missing','missing: printed text left out'],
 ['added','added: text not printed (solution, correction, completed blank)'],['smiles','smiles: SMILES of the wrong structure'],
 ['diagram','diagram: description wrong or missing something needed'],['layout','layout: structure wrong enough to mislead'],
 ['format','format: markdown or LaTeX that doesn\'t render']];
let marks = {};
try { marks = JSON.parse(localStorage.getItem(STORE) || '{}') } catch (e) { marks = {} }
function persist() { try { localStorage.setItem(STORE, JSON.stringify(marks)) } catch (e) {} progress() }

const md = window.markdownit({ html: false, linkify: false, breaks: false });
const esc = s => s.replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
md.renderer.rules.fence = (tokens, idx) => {
  const t = tokens[idx];
  if (t.info.trim() !== 'diagram') return '<pre>' + esc(t.content) + '</pre>';
  let out = '<div class="diagram"><span class="lbl">diagram</span>';
  for (const line of t.content.replace(/\n$/, '').split('\n')) {
    const m = line.match(/^\s*smiles:\s*(.*)$/i);
    out += m ? '<div class="smiles">smiles: ' + esc(m[1]) + '<canvas data-smiles="' + esc(m[1]) + '" width="220" height="160"></canvas></div>'
             : esc(line) + '\n';
  }
  return out + '</div>';
};
function render(text) {
  // protect maths from markdown outside fences, render, then restore for MathJax
  const maths = [];
  const parts = text.split(/(^```[\s\S]*?^```[ \t]*$)/m);
  const prot = parts.map((p, i) => i % 2 ? p : p.replace(/\$\$[\s\S]+?\$\$|\$(?:\\\$|[^$\n])+?\$/g, m => { maths.push(m); return 'MATHPH' + (maths.length - 1) + 'X'; })).join('');
  return md.render(prot).replace(/MATHPH(\d+)X/g, (_, n) => esc(maths[+n]));
}
function drawSmiles(root) {
  if (!window.SmilesDrawer) return;
  root.querySelectorAll('canvas[data-smiles]').forEach(cv => {
    const d = new SmilesDrawer.Drawer({ width: 220, height: 160 });
    SmilesDrawer.parse(cv.dataset.smiles,
      tree => { try { d.draw(tree, cv, 'light', false) } catch (e) { cv.outerHTML = '<div class="meta">(could not draw)</div>' } },
      () => { cv.outerHTML = '<div class="wf">SMILES does not parse</div>' }); });
}
function card(it, n) {
  const el = document.createElement('section'); el.className = 'card'; el.id = 'k' + it.key;
  const kind = it.kind === 'question' ? '' : ' · ' + it.kind;
  el.innerHTML = `<div class="head"><span class="num">#${n}</span><span>${esc(it.subject)} · ${it.type}${kind}</span>
    ${it.regression ? '<span class="pill reg">regression</span>' : ''}<span class="meta">${esc(it.questionId)} · key ${it.key} · ${it.seconds}s · ${it.tokens} tokens</span></div>
    <div class="cols"><div class="imgcol">${it.images.map(u => `<img loading="lazy" src="${u}" alt="question image">`).join('')}</div>
    <div class="render">${it.passage !== undefined ? `<details><summary class="meta">Passage (context only; not part of this transcription)</summary><div class="passage">${esc(it.passage)}</div></details>` : ''}
    ${it.wellFormed.length ? `<div class="wf">Well-formed check: ${esc(it.wellFormed.join('; '))}</div>` : ''}
    <div class="md">${render(it.md)}</div><details class="raw"><summary class="meta">Raw markdown</summary><pre>${esc(it.md)}</pre></details></div>
    <div class="panel"><h4>Mark</h4>
      ${['correct','faulty','unsure'].map(m => `<label><input type="radio" name="m${it.key}" value="${m}"> ${m}</label>`).join('')}
      <div class="faults hidden"><h4>Faults</h4><div class="flist"></div><button class="addf" type="button">+ add fault</button></div>
      <h4>Fix (optional, corrected markdown)</h4><textarea class="fix" placeholder="Leave empty unless you want to give the corrected text"></textarea>
      <div class="gate hidden"></div></div></div>`;
  return el;
}
function faultRow(f) {
  const d = document.createElement('div'); d.className = 'fault';
  d.innerHTML = `<select class="kind">${KINDS.map(([k, t]) => `<option value="${k}">${esc(t)}</option>`).join('')}</select>
    <select class="sev"><option value="">severity: choose…</option><option value="material">material: changes what a tag or search would take</option><option value="minor">minor: still the same problem</option></select>
    <input type="text" class="at" placeholder="where (the text in the transcription)"><input type="text" class="note" placeholder="note (what it should be)">
    <button type="button" class="rm">remove</button>`;
  d.querySelector('.kind').value = f.kind || 'misread'; d.querySelector('.sev').value = f.severity || '';
  d.classList.toggle('nosev', !f.severity); d.querySelector('.at').value = f.at || ''; d.querySelector('.note').value = f.note || '';
  return d;
}
function gateHtml(it) {
  if (!it.cands.length && !it.wellFormed.length) return '<h4>Gate</h4>No contested positions.';
  return '<h4>Gate: contested positions</h4>' + it.cands.map(c =>
    `<div class="c">line ${c.line} (${c.where}${c.md ? '' : ', notation only by the variant'}): <b>${esc(JSON.stringify(c.chosen))}</b> ${c.pChosen} vs ${esc(JSON.stringify(c.rival))} ${c.pRival} · margin ${c.diff}<br>…${esc(c.ctx)}</div>`).join('');
}
function sync(it, el) {
  const m = marks[it.key] || {};
  const panel = el.querySelector('.panel');
  panel.className = 'panel ' + (m.mark || '');
  el.querySelector('.faults').classList.toggle('hidden', m.mark !== 'faulty');
  const g = el.querySelector('.gate'); g.classList.toggle('hidden', !m.mark); if (m.mark && !g.innerHTML) g.innerHTML = gateHtml(it);
}
function collect(it, el) {
  const m = marks[it.key] || { key: it.key, kind: it.kind };
  const r = el.querySelector(`input[name="m${it.key}"]:checked`); m.mark = r ? r.value : undefined;
  m.faults = [...el.querySelectorAll('.fault')].map(f => { f.classList.toggle('nosev', !f.querySelector('.sev').value);
    return { kind: f.querySelector('.kind').value, severity: f.querySelector('.sev').value, at: f.querySelector('.at').value, note: f.querySelector('.note').value }; });
  m.fix = el.querySelector('.fix').value || undefined; m.markedAt = new Date().toISOString();
  marks[it.key] = m; persist(); sync(it, el); filter();
}
function progress() {
  const done = ITEMS.filter(i => marks[i.key] && marks[i.key].mark).length;
  const faulty = ITEMS.filter(i => marks[i.key] && marks[i.key].mark === 'faulty').length;
  const all = ITEMS.map(i => marks[i.key]).filter(m => m && m.mark === 'faulty');
  const material = all.filter(m => (m.faults || []).some(f => f.severity === 'material')).length;
  const nosev = all.reduce((n, m) => n + (m.faults || []).filter(f => !f.severity).length, 0);
  const nofault = all.filter(m => !(m.faults || []).length).length;
  document.getElementById('count').textContent = `${done} / ${ITEMS.length} marked · ${faulty} faulty (${material} material)`
    + (nosev ? ` · ${nosev} fault(s) without severity` : '') + (nofault ? ` · ${nofault} faulty without a fault listed` : '');
  document.getElementById('prog').style.width = (100 * done / ITEMS.length) + '%';
}
function filter() {
  const only = document.getElementById('onlyUnmarked').checked;
  ITEMS.forEach(i => document.getElementById('k' + i.key).classList.toggle('hidden', only && !!(marks[i.key] && marks[i.key].mark)));
}
const list = document.getElementById('list');
ITEMS.forEach((it, n) => {
  const el = card(it, n + 1); list.appendChild(el);
  const m = marks[it.key];
  if (m) { if (m.mark) el.querySelector(`input[value="${m.mark}"]`).checked = true;
    (m.faults || []).forEach(f => el.querySelector('.flist').appendChild(faultRow(f))); el.querySelector('.fix').value = m.fix || ''; }
  el.querySelector('.panel').addEventListener('change', () => collect(it, el));
  el.querySelector('.panel').addEventListener('input', e => { if (e.target.matches('input[type=text],textarea')) collect(it, el); });
  el.querySelector('.addf').addEventListener('click', () => { el.querySelector('.flist').appendChild(faultRow({})); collect(it, el); });
  el.querySelector('.flist').addEventListener('click', e => { if (e.target.matches('.rm')) { e.target.closest('.fault').remove(); collect(it, el); } });
  sync(it, el); drawSmiles(el);
});
progress(); filter();
document.getElementById('onlyUnmarked').addEventListener('change', filter);
document.getElementById('next').addEventListener('click', () => {
  const it = ITEMS.find(i => !(marks[i.key] && marks[i.key].mark)); if (it) document.getElementById('k' + it.key).scrollIntoView({ behavior: 'smooth' });
});
document.getElementById('save').addEventListener('click', () => {
  const out = ITEMS.filter(i => marks[i.key] && marks[i.key].mark).map(i => marks[i.key]);
  const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([JSON.stringify(out, null, 1)], { type: 'application/json' }));
  a.download = 'transcription-calibration-marks.json'; a.click();
});
window.addEventListener('load', () => { if (window.MathJax && MathJax.typesetPromise) MathJax.startup.promise.then(() => MathJax.typesetPromise([list])); });
</script></body></html>'''

if __name__ == '__main__':
    main()
