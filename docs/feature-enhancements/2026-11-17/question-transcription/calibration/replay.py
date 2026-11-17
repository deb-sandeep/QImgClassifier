"""Replays the transcription gate (logprob-confidence.md §4.1-§4.2) from stored responses, offline.

Usage:
  python3 replay.py gate [--responses responses]      -> gate.jsonl: §4.1 result, candidates, metrics per request
  python3 replay.py report [--marks marks.jsonl]       -> calibration tables (calibration.md §3.6) on stdout
  python3 replay.py golden                             -> adds the rev 3 rule's expected C3 to golden-cases.json

Rev 3 (logprob-confidence.md §4.2a, added by the QTranscriber session): gate3() is the rule the app uses, the md-minor
rule plus dash mapping and the split UTF-8 byte comparison, defined per position (see gate3). The Java gate
(TranscriptionGate) must reproduce C3 and contested3 of the golden cases.

The Java gate must reproduce `gate` exactly; the golden cases (calibration.md §5) are taken from gate.jsonl.
"""
import argparse, collections, json, math, os, re

HERE = os.path.dirname(os.path.abspath(__file__))
MARGINS = [0.1, 0.2, 0.3, 0.4, 0.5]
C_MAXES = [0, 1, 2, 3]

REMOVED = ['text', 'mathrm', 'operatorname', 'rm', 'mathbf', 'left', 'right', 'displaystyle', 'ce', 'quad', 'qquad']
RE_REMOVED = re.compile(r'\\(?:' + '|'.join(sorted(REMOVED, key=len, reverse=True)) + r')(?![A-Za-z])|\\[,;!]')
RE_PARTIAL = re.compile(r'\\([A-Za-z]*)$')
RE_SOLUTION = re.compile(r'^\s*(?:[-*]\s*)?(Answer|Solution|Hence|∴)', re.M)
RE_META = re.compile(r'here is the transcription|transcription of the|i cannot|i can\'t', re.I)


# ---------------------------------------------------------------- §4.2 normalisation

def normalise(s, partial=False):
    if partial:
        m = RE_PARTIAL.search(s)
        if m and any(c.startswith(m.group(1)) for c in REMOVED):
            s = s[:m.start()]
    s = RE_REMOVED.sub('', s)
    s = s.replace('\\', '')
    return re.sub(r'[\s${}^_]', '', s)


def normalise_md(s, partial=False):
    """Variant (not in the spec): also treats markdown structure and dot notation as notation."""
    s = re.sub(r'\\(?:l|c)?dots(?![A-Za-z])|\.{3,}|…', '...', s)
    s = re.sub(r'\\cdot(?![A-Za-z])', '.', s)
    s = re.sub(r'(?m)^[ \t]*\|?[ \t:]*-{3,}[-| \t:]*$', '', s)  # table separator rows
    s = re.sub(r'(?m)^[ \t]*-(?:[ \t]+|$)', '', s)               # list markers
    s = re.sub(r'[|`*#]', '', s)
    return normalise(s, partial)


RE_MINOR_CHARS = re.compile(r'[\s${}^_\\]')


def minor_only(chosen, rival):
    """True if chosen and rival differ only in digits, decimal point or sign: a contest that can only be a minor fault
    (calibration.md rev 2 §3.6). Exponents are covered because ^ and braces are ignored."""
    a, b = RE_MINOR_CHARS.sub('', chosen), RE_MINOR_CHARS.sub('', rival)
    return bool(a or b) and all(re.fullmatch(r'[0-9.,+\-−±]*', x) for x in (a, b))


def same_reading(before, rival, text, norm=normalise):
    a = norm(before + rival, partial=True)
    b = norm(text)
    return b.startswith(a) or a.startswith(b)


# ---------------------------------------------------------------- structure of the reply

def line_spans(text):
    """For each char offset: 'desc' inside a diagram description line, 'smiles' on a smiles: line, else 'text'.
    Also returns the fence problems found (§4.1)."""
    kind = ['text'] * len(text)
    problems, pos, in_fence, fence_info, fence_lines = [], 0, False, None, []
    for line in text.split('\n'):
        end = pos + len(line)
        stripped = line.strip()
        if stripped.startswith('```'):
            if not in_fence:
                in_fence, fence_info, fence_lines = True, stripped[3:].strip(), []
                if fence_info not in ('diagram', 'uncertain'):
                    problems.append(f'fence info string {fence_info!r}')
            else:
                if fence_info == 'diagram' and not any(l.strip() and not l.strip().lower().startswith('smiles:')
                                                       for l in fence_lines):
                    problems.append('diagram block without description')
                in_fence = False
        elif in_fence:
            fence_lines.append(line)
            k = 'smiles' if stripped.lower().startswith('smiles:') else ('desc' if fence_info == 'diagram' else 'meta')
            for i in range(pos, min(end + 1, len(text))):
                kind[i] = k
        pos = end + 1
    if in_fence:
        problems.append('unclosed code fence')
    return kind, problems


def outside_fences(text):
    out, in_fence = [], False
    for line in text.split('\n'):
        if line.strip().startswith('```'):
            in_fence = not in_fence
            continue
        if not in_fence:
            out.append(line)
    return '\n'.join(out)


def maths_problems(text):
    body = outside_fences(text).replace('\\$', '')
    problems = []
    if body.count('$$') % 2:
        problems.append('unbalanced $$')
    single = body.replace('$$', '')
    if single.count('$') % 2:
        problems.append('unbalanced $')
    for seg in re.findall(r'\$\$(.+?)\$\$|\$(.+?)\$', body, re.S):
        s = (seg[0] or seg[1]).replace('\\{', '').replace('\\}', '')
        depth = 0
        for ch in s:
            depth += ch == '{'
            depth -= ch == '}'
            if depth < 0:
                break
        if depth != 0:
            problems.append('unbalanced {} in maths')
            break
    return problems


def smiles_lines(text):
    return [l.split(':', 1)[1].strip() for l in text.split('\n') if l.strip().lower().startswith('smiles:')]


def well_formed(resp, text):
    problems = []
    if resp.get('done_reason') == 'length':
        problems.append('cut off (length)')
    if not text.strip():
        problems.append('empty')
    _, fence_problems = line_spans(text)
    problems += fence_problems + maths_problems(text)
    if '![' in text:
        problems.append('image placeholder')
    if RE_META.search(outside_fences(text)):
        problems.append('meta text')
    if RE_SOLUTION.search(outside_fences(text)):
        problems.append('solution text')
    return problems


# ---------------------------------------------------------------- gate per request

def gate(record):
    resp = record['response']
    text = resp['response']
    toks = resp.get('logprobs') or []
    kind, _ = line_spans(text)
    cands, ps, pos = [], [], 0
    for t in toks:
        tok, start = t['token'], pos
        pos += len(tok)
        where = kind[start] if start < len(kind) else 'text'
        if where == 'desc':
            continue
        p = math.exp(t['logprob'])
        if tok.strip():
            ps.append(p)
        rivals = [r for r in t.get('top_logprobs', []) if r['token'] != tok]
        best = best_md = None
        for r in rivals:
            pr = math.exp(r['logprob'])
            diff = p - pr
            if diff >= max(MARGINS):
                continue
            contested = not same_reading(text[:start], r['token'], text)
            line_text = text[text.rfind('\n', 0, start) + 1:text.find('\n', start) if '\n' in text[start:] else len(text)]
            contested_md = contested and not line_text.strip().startswith('```') \
                and not same_reading(text[:start], r['token'], text, normalise_md)
            c = dict(at=start, line=text.count('\n', 0, start) + 1, where=where, chosen=tok, pChosen=round(p, 4),
                     rival=r['token'], pRival=round(pr, 4), diff=round(diff, 4), contested=contested,
                     contestedMd=contested_md, minorOnly=minor_only(tok, r['token']),
                     continuation=text[start:start + 24], digit=bool(re.search(r'\d', tok + r['token'])))
            # keep, per position, the most threatening rival: contested first, then the smallest diff
            if best is None or (c['contested'], -c['diff']) > (best['contested'], -best['diff']):
                best = c
            if c['contestedMd'] and (best_md is None or c['diff'] < best_md['diff']):
                best_md = c
        if best:
            if best_md and best_md is not best:  # keep the variant's own most threatening rival too
                best = dict(best, mdRival=best_md['rival'], mdPRival=best_md['pRival'], mdDiff=best_md['diff'],
                            mdMinorOnly=best_md['minorOnly'])
            elif best_md is None:
                best = dict(best, contestedMd=False)
            cands.append(best)
    wf = well_formed(resp, text)
    metrics = dict(tokens=len(toks), geoMeanP=round(math.exp(sum(map(math.log, ps)) / len(ps)), 4) if ps else None,
                   minP=round(min(ps), 4) if ps else None, below09=sum(p < .9 for p in ps),
                   below07=sum(p < .7 for p in ps), below05=sum(p < .5 for p in ps))
    C = {str(m): sum(1 for c in cands if c['contested'] and c['diff'] < m) for m in MARGINS}
    Cmd = {str(m): sum(1 for c in cands if c['contestedMd'] and c.get('mdDiff', c['diff']) < m) for m in MARGINS}
    Cx = {str(m): sum(1 for c in cands if c['contested'] and not c['minorOnly'] and c['diff'] < m) for m in MARGINS}
    Cmdx = {str(m): sum(1 for c in cands if c['contestedMd'] and not c.get('mdMinorOnly', c['minorOnly'])
                        and c.get('mdDiff', c['diff']) < m) for m in MARGINS}
    return dict(key=record['key'], wellFormed=wf, C=C, Cmd=Cmd, Cx=Cx, Cmdx=Cmdx, candidates=cands, smiles=smiles_lines(text), metrics=metrics,
                outputTokens=resp.get('eval_count'), wallSeconds=record.get('wallSeconds'))


def cmd_gate(args):
    src = os.path.join(HERE, args.responses)
    out = []
    for f in sorted(os.listdir(src)):
        if f.endswith('.json'):
            out.append(gate(json.load(open(os.path.join(src, f)))))
    with open(os.path.join(HERE, 'gate.jsonl'), 'w') as fh:
        for g in out:
            fh.write(json.dumps(g, ensure_ascii=False) + '\n')
    print(f'{len(out)} requests -> gate.jsonl')
    print('well-formed failures:', sum(bool(g['wellFormed']) for g in out),
          collections.Counter(p for g in out for p in g['wellFormed']).most_common())
    for m in MARGINS:
        print(f'MARGIN {m}: C>0 on {sum(g["C"][str(m)] > 0 for g in out)} requests')


# ---------------------------------------------------------------- rev 3 rule (the app's)

RE_DASHES = re.compile('[\u2013\u2014\u2212]')


def normalise3(s, partial=False):
    """The md normalisation with dashes mapped to '-' (rev 3, §4.2a step 2)."""
    return normalise_md(RE_DASHES.sub('-', s), partial)


def incomplete_utf8(b):
    """True if b isn't valid UTF-8 on its own (e.g. the first byte of a multi-byte character)."""
    try:
        b.decode('utf-8')
        return False
    except UnicodeDecodeError:
        return True


def gate3(record):
    """The rev 3 gate, per position: a scored token (not on a diagram description line) is contested at margin m if
    some rival r with p(chosen) - p(r) < m is
      - not on a fence line,
      - not a split UTF-8 piece of the chosen token or of the text from it (bytes compared),
      - not the same reading under normalise3 (prefix either way, the partial-command rule on the rival side),
      - not minor-only (both differ only in digits, '.', ',', signs).
    C3[m] is the number of contested positions; contested3 lists each with its smallest such diff."""
    resp = record['response']
    text = resp['response']
    toks = resp.get('logprobs') or []
    kind, _ = line_spans(text)
    norm_text = normalise3(text)
    contested, pos = [], 0
    for t in toks:
        tok, start = t['token'], pos
        pos += len(tok)
        where = kind[start] if start < len(kind) else 'text'
        if where == 'desc':
            continue
        line_end = text.find('\n', start)
        line_text = text[text.rfind('\n', 0, start) + 1:line_end if line_end >= 0 else len(text)]
        if line_text.strip().startswith('```'):
            continue
        p = math.exp(t['logprob'])
        chosen_bytes = bytes(t.get('bytes') or tok.encode('utf-8'))
        cont_bytes = text[start:].encode('utf-8')
        best = None
        for r in t.get('top_logprobs', []):
            if r['token'] == tok:
                continue
            diff = p - math.exp(r['logprob'])
            if diff >= max(MARGINS):
                continue
            rb = bytes(r.get('bytes') or r['token'].encode('utf-8'))
            if incomplete_utf8(rb) and (chosen_bytes.startswith(rb) or cont_bytes.startswith(rb)):
                continue
            a = normalise3(text[:start] + r['token'], partial=True)
            if norm_text.startswith(a) or a.startswith(norm_text):
                continue
            if minor_only(tok, r['token']):
                continue
            if best is None or diff < best['diff']:
                best = dict(line=text.count('\n', 0, start) + 1, chosen=tok, rival=r['token'], diff=round(diff, 4))
        if best:
            contested.append(best)
    C3 = {str(m): sum(1 for c in contested if c['diff'] < m) for m in MARGINS}
    return C3, contested


def cmd_golden(args):
    path = os.path.join(HERE, 'golden-cases.json')
    cases = json.load(open(path))
    for c in cases:
        C3, contested = gate3(json.load(open(os.path.join(HERE, c['response']))))
        c['expected']['C3'] = C3
        c['expected']['contested3'] = contested
        print(c['key'], 'Cmdx', c['expected']['Cmdx'], 'C3', C3)
    with open(path, 'w') as fh:
        json.dump(cases, fh, ensure_ascii=False, indent=2)
        fh.write('\n')
    # The app's copy: what the Java gate needs of each case, checked when QTranscriber starts.
    app = os.path.join(HERE, '../../../../../src/main/resources/apps/transcriber/gate-golden.json')
    compact = []
    for c in cases:
        resp = json.load(open(os.path.join(HERE, c['response'])))['response']
        compact.append(dict(
            key=c['key'], kind=c['kind'], why=c['why'], text=resp['response'], doneReason=resp.get('done_reason'),
            tokens=[dict(t=t['token'], lp=t['logprob'], b=t.get('bytes'),
                         top=[dict(t=r['token'], lp=r['logprob'], b=r.get('bytes'))
                              for r in t.get('top_logprobs', [])]) for t in resp.get('logprobs') or []],
            wellFormed=c['expected']['wellFormed'], C3=c['expected']['C3'],
            contested3=[dict(line=x['line'], chosen=x['chosen'], rival=x['rival']) for x in c['expected']['contested3']]))
    with open(app, 'w') as fh:
        json.dump(dict(version=1, margins=MARGINS, cases=compact), fh, ensure_ascii=False, separators=(',', ':'))
    print('wrote', os.path.normpath(app))


# ---------------------------------------------------------------- report against the user's marks
# calibration.md rev 2 §3.6: caught / missed on material faults only; false escalations = acceptable ones escalated.

RULES = {'spec': 'C', 'md': 'Cmd', 'spec-minor': 'Cx', 'md-minor': 'Cmdx'}
RULE_NAMES = {'spec': '§4.2 as written', 'md': '+ markdown structure as notation',
              'spec-minor': '§4.2, digit/sign-only contests ignored', 'md-minor': 'markdown + digit/sign-only ignored'}


def escalated(g, m, cmax, rule='spec'):
    return bool(g['wellFormed']) or g[RULES[rule]][str(m)] > cmax


def pct(a, b):
    return f'{a}/{b} ({a / b:.0%})' if b else f'{a}/0'


def cmd_report(args):
    gates = {g['key']: g for g in map(json.loads, open(os.path.join(HERE, 'gate.jsonl')))}
    sample = {s['key']: s for s in map(json.loads, open(os.path.join(HERE, 'sample.jsonl')))}
    marks = {m['key']: m for m in map(json.loads, open(os.path.join(HERE, args.marks)))}
    rows = []
    for k, mk in marks.items():
        if k not in gates or mk['mark'] not in ('correct', 'faulty'):
            continue
        faults = mk.get('faults') or []
        material = mk['mark'] == 'faulty' and any(f.get('severity') == 'material' for f in faults)
        minor = mk['mark'] == 'faulty' and not material
        rows.append(dict(key=k, g=gates[k], s=sample[k], m=mk, material=material, minor=minor, faults=faults))
    n = len(rows)
    nmat = sum(r['material'] for r in rows)
    nmin = sum(r['minor'] for r in rows)
    nacc = n - nmat
    unsure = sum(m['mark'] == 'unsure' for m in marks.values())
    print(f'## Marks\n\nmarked {n} (+{unsure} unsure): correct {n - nmat - nmin}, minor faults only {nmin}, '
          f'material {nmat} ({nmat / n:.0%}); acceptable {nacc}\n')

    print('## Material faults by subject, type and kind\n')
    for label, keyf in (('subject', lambda r: r['s']['subject']), ('type', lambda r: r['s']['type'] + (' passage' if r['s']['kind'] == 'lct-passage' else ''))):
        c = collections.Counter(keyf(r) for r in rows)
        cm = collections.Counter(keyf(r) for r in rows if r['material'])
        print(f'| {label} | marked | material |\n|---|---|---|')
        for k in sorted(c):
            print(f'| {k} | {c[k]} | {pct(cm[k], c[k])} |')
        print()
    ck = collections.Counter((f.get('kind'), f.get('severity')) for r in rows for f in r['faults'])
    print('| fault kind | material | minor |\n|---|---|---|')
    for kind in sorted({k for k, _ in ck}, key=str):
        print(f'| {kind} | {ck[(kind, "material")]} | {ck[(kind, "minor")]} |')
    print()

    print('## Settings\n\nescalated = fails §4.1 or C > C_MAX. caught/missed on material faults; '
          'false = acceptable transcriptions escalated; minor esc = minor-only ones escalated.\n')
    best = []
    for rule in RULES:
        print(f'### {rule}: {RULE_NAMES[rule]}\n')
        print('| MARGIN | C_MAX | escalated | caught | missed | false escalations | minor esc |\n|---|---|---|---|---|---|---|')
        for m in MARGINS:
            for c in C_MAXES:
                esc = {r['key']: escalated(r['g'], m, c, rule) for r in rows}
                caught = sum(esc[r['key']] for r in rows if r['material'])
                false = sum(esc[r['key']] for r in rows if not r['material'])
                minor_esc = sum(esc[r['key']] for r in rows if r['minor'])
                print(f'| {m} | {c} | {pct(sum(esc.values()), n)} | {pct(caught, nmat)} | {nmat - caught} | '
                      f'{pct(false, nacc)} | {minor_esc}/{nmin} |')
                best.append((rule, m, c, caught, false, sum(esc.values())))
        print()

    print('## Material faults the gate never catches (C = 0 at every margin, any rule; well-formed)\n')
    never = [r for r in rows if r['material'] and not r['g']['wellFormed']
             and all(r['g'][col][str(m)] == 0 for col in RULES.values() for m in MARGINS)]
    print(f'{len(never)} of {nmat}. By kind:', dict(collections.Counter(f['kind'] for r in never for f in r['faults']
                                                                    if f.get('severity') == 'material')))
    for r in never:
        print(f"- {r['key']} {r['s']['subject']} {r['s']['type']}: " +
              '; '.join(f"{f['kind']} `{f.get('at', '')}` {f.get('note', '')}" for f in r['faults'] if f.get('severity') == 'material'))
    print()

    print('## Candidates judged the same reading at a fault\'s place\n')
    for r in rows:
        if r['m']['mark'] != 'faulty':
            continue
        text = json.load(open(os.path.join(HERE, args.responses, r['key'] + '.json')))['response']['response']
        for f in r['faults']:
            at = (f.get('at') or '').strip()
            i = text.find(at) if at else -1
            if i < 0:
                continue
            for c in r['g']['candidates']:
                if not c['contested'] and i - 2 <= c['at'] <= i + len(at) + 2:
                    print(f"- {r['key']} ({f.get('severity')} {f['kind']} `{at}`): {c['chosen']!r} {c['pChosen']} vs "
                          f"{c['rival']!r} {c['pRival']}")
    print()

    print('## Notation contested in acceptable transcriptions (spec rule; most frequent rival pairs)\n')
    pairs = collections.Counter((c['chosen'].strip(), c['rival'].strip()) for r in rows if not r['material']
                                for c in r['g']['candidates'] if c['contested'] and c['diff'] < 0.3)
    for (a, b), k in pairs.most_common(15):
        print(f'- {k}× {a!r} vs {b!r}')
    print()

    print('## Digit/sign-only contests\n')
    dig = [(r, c) for r in rows for c in r['g']['candidates'] if c['contested'] and c['minorOnly'] and c['diff'] < 0.5]
    print(f'{len(dig)} digit/sign-only contested positions at MARGIN < 0.5, in {len({r["key"] for r, _ in dig})} transcriptions; '
          f'of those, in transcriptions with a material fault: {len({r["key"] for r, _ in dig if r["material"]})}')
    for r, c in dig[:30]:
        print(f"- {r['key']} ({'material' if r['material'] else 'minor' if r['minor'] else 'correct'}): "
              f"{c['chosen']!r} {c['pChosen']} vs {c['rival']!r} {c['pRival']} …{c['continuation']!r}")
    print()

    print('## Well-formed checks that fired\n')
    for r in rows:
        if r['g']['wellFormed']:
            print(f"- {r['key']} ({'material' if r['material'] else 'acceptable'}): {'; '.join(r['g']['wellFormed'])}")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    g = sub.add_parser('gate')
    g.add_argument('--responses', default='responses')
    r = sub.add_parser('report')
    r.add_argument('--marks', default='marks.jsonl')
    r.add_argument('--responses', default='responses')
    sub.add_parser('golden')
    args = ap.parse_args()
    {'gate': cmd_gate, 'report': cmd_report, 'golden': cmd_golden}[args.cmd](args)


if __name__ == '__main__':
    main()
