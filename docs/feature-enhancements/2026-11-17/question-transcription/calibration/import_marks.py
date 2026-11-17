"""Turns the review page's download into marks.jsonl (calibration.md §6).

Usage: python3 import_marks.py [~/Downloads/transcription-calibration-marks.json]
"""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
src = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else '~/Downloads/transcription-calibration-marks.json')
marks = json.load(open(src))
with open(os.path.join(HERE, 'marks.jsonl'), 'w') as f:
    for m in marks:
        f.write(json.dumps({'key': m['key'], 'kind': m['kind'], 'mark': m['mark'],
                            'faults': [{'kind': x.get('kind'), 'severity': x.get('severity') or None, 'at': x.get('at', ''),
                                        'note': x.get('note', '')} for x in m.get('faults', [])] if m['mark'] == 'faulty' else [],
                            'fix': m.get('fix'), 'markedAt': m.get('markedAt')}, ensure_ascii=False) + '\n')
c = {}
for m in marks:
    c[m['mark']] = c.get(m['mark'], 0) + 1
print(f'{len(marks)} marks -> marks.jsonl', c)
nosev = [m['key'] for m in marks if m['mark'] == 'faulty' for x in m.get('faults', []) if not x.get('severity')]
nofault = [m['key'] for m in marks if m['mark'] == 'faulty' and not m.get('faults')]
if nosev: print('WARNING faults without severity:', nosev)
if nofault: print('WARNING faulty without a fault listed:', nofault)
