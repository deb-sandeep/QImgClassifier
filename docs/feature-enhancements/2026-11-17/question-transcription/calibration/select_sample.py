"""Picks the calibration sample (calibration.md §3.2, §3.5) from the dev DB and writes sample.jsonl.

One line per request: questions, LCT passages (kind lct-passage) and LCT question parts (kind lct-question).
Usage: python3 select_sample.py [--seed N]
"""
import argparse, collections, json, os, random, sys

sys.path.insert(0, '/Users/sandeep/Documents/StudyNotes/question-bank/supporting-artefacts/tag-workspace/scripts')
import tagdb

HERE = os.path.dirname(os.path.abspath(__file__))
SUBJECTS = {'P': 'Physics', 'C': 'Chemistry', 'M': 'Mathematics'}
CELLS = {  # calibration.md §3.2
    'SCA': {'P': 15, 'C': 20, 'M': 15}, 'MCA': {'P': 8, 'C': 10, 'M': 7}, 'IVT': {'P': 8, 'C': 8, 'M': 9},
    'NVT': {'P': 5, 'C': 5, 'M': 5}, 'ART': {'P': 5, 'C': 10, 'M': 5},
}
LCT_GROUPS = {'P': 4, 'C': 4, 'M': 4}
REGRESSION = [  # calibration.md §3.5: (source, image file name prefix)
    ('PYQ-M-240406-S2', 'M_SCA_10'), ('PYQ-A-13-P1', 'C_MCA_34'), ('PYQ-M-260121-S2', 'C_SCA_59'),
    ('AITS-13-A-FT1P2', 'C_MMT_2'), ('PYQ-M-260121-S2', 'C_SCA_52'), ('PYQ-M-250404-S2', 'P_SCA_31'),
    ('LK-A-C-04', 'C_SCA_6'), ('LK-A-M-28', 'M_SCA_36'),
]


def family(source):
    head = source.split('-')[0]
    return {'PYQ': 'PYQ', 'AITS': 'AITS', 'RB': 'RankBooker', 'LK': 'Laksha', 'MN': 'Manual'}.get(head, head)


def subject_of(question_id):
    return question_id.split('://')[1].split('/')[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--seed', type=int, default=20261117)
    args = ap.parse_args()
    rnd = random.Random(args.seed)

    loc = tagdb.image_index()
    rows = tagdb.q("select q.id,q.question_id,q.source_id,q.problem_type,q.topic_id,q.lct_sequence,q.xcription_needed "
                   "from question q")
    qs = {int(r[0]): dict(id=int(r[0]), questionId=r[1], source=r[2], type=r[3], topicId=int(r[4]),
                          lctSeq=r[5], pending=r[6] == '1', subject=SUBJECTS[subject_of(r[1])], s=subject_of(r[1]))
          for r in rows}
    imgs = collections.defaultdict(list)
    for qid, src, page, fname, ctx, seq in tagdb.q(
            "select i.question_id,q.source_id,i.page_number,i.file_name,ifnull(i.lct_ctx_image+0,0),i.sequence "
            "from question_image i join question q on q.id=i.question_id order by i.question_id,i.sequence"):
        name = tagdb.image_name(src, page, fname)
        imgs[int(qid)].append(dict(file=name, path=loc.get(name), ctx=ctx == '1'))

    def resolvable(q):
        return imgs[q['id']] and all(i['path'] for i in imgs[q['id']])

    # Regression set first.
    regression = []
    for src, prefix in REGRESSION:
        hit = [q for q in qs.values() if q['source'] == src
               and any(i['file'].split('.', 2)[2].startswith(prefix + '.') or i['file'].split('.', 2)[2].startswith(prefix + '(')
                       for i in imgs[q['id']])]
        if len(hit) != 1:
            sys.exit(f'regression {src} {prefix}: {len(hit)} matches')
        regression.append(hit[0]['id'])

    used_fam, used_topic = collections.Counter(), collections.Counter()

    def pick(cands, n):
        rnd.shuffle(cands)
        chosen = []
        for _ in range(min(n, len(cands))):
            cands.sort(key=lambda q: (used_fam[(q['type'], q['s'], family(q['source']))],
                                      used_topic[q['topicId']], used_fam[('src', q['source'])]))
            q = cands.pop(0)
            used_fam[(q['type'], q['s'], family(q['source']))] += 1
            used_fam[('src', q['source'])] += 1
            used_topic[q['topicId']] += 1
            chosen.append(q)
        return chosen

    out = []

    def add_question(q, kind='question', group=None, regression_case=False):
        images = [i for i in imgs[q['id']] if not (kind == 'lct-question' and i['ctx'])]
        out.append(dict(key=str(q['id']), id=q['id'], questionId=q['questionId'], subject=q['subject'], type=q['type'],
                        kind=kind, source=q['source'], family=family(q['source']), topicId=q['topicId'],
                        lctCtxId=group, regression=regression_case,
                        images=[i['file'] for i in images], paths=[i['path'] for i in images]))

    def add_group(group, members):
        first = members[0]
        ctx = [i for i in imgs[first['id']] if i['ctx']]
        out.append(dict(key=f"{first['id']}-ctx", id=first['id'], questionId=group, subject=first['subject'], type='LCT',
                        kind='lct-passage', source=first['source'], family=family(first['source']),
                        topicId=first['topicId'], lctCtxId=group, regression=False,
                        images=[i['file'] for i in ctx], paths=[i['path'] for i in ctx]))
        for m in members:
            add_question(m, 'lct-question', group)

    for qid in regression:
        add_question(qs[qid], regression_case=True)
    taken = set(regression)

    for typ, per in CELLS.items():
        for s, n in per.items():
            cands = [q for q in qs.values() if q['type'] == typ and q['s'] == s and q['pending']
                     and q['id'] not in taken and resolvable(q)]
            for q in pick(cands, n):
                add_question(q)
                taken.add(q['id'])

    for q in sorted((q for q in qs.values() if q['type'] == 'MMT'), key=lambda q: q['id']):
        if q['id'] not in taken and resolvable(q):
            add_question(q)
            taken.add(q['id'])

    groups = collections.defaultdict(list)
    for q in qs.values():
        if q['type'] == 'LCT':
            groups[q['questionId'].rsplit('/', 1)[0]].append(q)
    for s, n in LCT_GROUPS.items():
        cands = []
        for g, members in groups.items():
            members.sort(key=lambda q: q['id'])
            first = members[0]
            if first['s'] == s and all(m['pending'] and resolvable(m) for m in members) \
                    and any(i['ctx'] for i in imgs[first['id']]) and len(members) <= 4:
                cands.append(dict(first, group=g, members=members, type='LCTG'))
        for c in pick(cands, n):
            add_group(c['group'], c['members'])

    with open(os.path.join(HERE, 'sample.jsonl'), 'w') as f:
        for o in out:
            f.write(json.dumps(o, ensure_ascii=False) + '\n')
    c = collections.Counter((o['kind'], o['subject'], o['type']) for o in out)
    for k in sorted(c):
        print(k, c[k])
    print('requests', len(out), '| families', dict(collections.Counter(o['family'] for o in out)),
          '| topics', len({o['topicId'] for o in out}))


if __name__ == '__main__':
    main()
