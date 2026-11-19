"""Compare note events on independently annotated excerpts."""
import argparse
import csv
import json
from pathlib import Path

import numpy as np
from scipy.optimize import linear_sum_assignment

def evaluate(labels, events, start, end, role):
    notes=[n for n in events if start-.04 <= n['start'] <= end-.012]
    cost=np.full((len(labels),len(notes)),100.)
    for i,a in enumerate(labels):
        for j,b in enumerate(notes):
            diff=abs(a['attack_s']-b['start'])
            if a['midi']==b['pitch'] and diff<=.05:
                cost[i,j]=diff
    pairs=[(i,j) for i,j in zip(*linear_sum_assignment(cost)) if cost[i,j]<100]
    count=len(pairs)
    ended=[(labels[i],notes[j]) for i,j in pairs if labels[i]['release_s'] is not None]
    # The labels report acoustic releases. Treat tails and key-off as
    # different quantities and report errors only for matched note attacks.
    offset=[abs(label['release_s']-n['end']) for label,n in ended]
    return dict(role=role,labels=len(labels),candidates=len(notes),matched=count,
                recall=count/max(1,len(labels)),
                precisionAgainstAnnotated=count/max(1,len(notes)),
                f1AgainstAnnotated=2*count/max(1,len(labels)+len(notes)),
                matchedOnsetMedianMs=float(np.median([cost[i,j]*1000 for i,j in pairs])) if count else None,
                matchedReleaseMedianMs=float(np.median(offset)*1000) if offset else None,
                matchedReleaseCount=len(offset),
                unmatchedLabels=[a for i,a in enumerate(labels) if all(i!=k for k,_ in pairs)])

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('annotations',type=Path)
    parser.add_argument('midi',type=Path)
    parser.add_argument('output',type=Path)
    parser.add_argument('--basic',type=Path)
    parser.add_argument('--fit',action='append',type=Path,required=True)
    args=parser.parse_args()
    hand=json.loads(args.annotations.read_text())
    with args.midi.open() as f:
        midi=[dict(start=float(x['start']),end=float(x['end']),pitch=int(x['pitch']))
              for x in csv.DictReader(f) if x['part']=='0']
    variants={'suno-midi':{'unassigned':midi}}
    if args.basic:
        data=json.loads(args.basic.read_text())
        variants['basic-pitch']={'unassigned':data['notes']}
    for path in args.fit:
        variants[path.parent.name]=json.loads(path.read_text())
    scores={}
    for region in hand['regions']:
        scope={}
        start,end=region['start_s'],region['end_s']
        for name,tracks in variants.items():
            all_candidates=[n for notes in tracks.values() for n in notes]
            lead_candidates=tracks.get('lead',tracks.get('unassigned',[]))
            harmony_candidates=[n for key,notes in tracks.items() if key.startswith('harmony') for n in notes]
            high=[n for n in region['notes'] if n['confidence']=='high']
            sets=[('pooled',high,all_candidates),
                  ('lead',[n for n in high if n['voice']=='lead'],lead_candidates)]
            harmony=[n for n in high if n['voice']=='harmony']
            if harmony and harmony_candidates:
                sets.append(('harmony',harmony,harmony_candidates))
            scope[name]={role:evaluate(labels,events,start,end,role) for role,labels,events in sets}
        scores[region['id']]=scope
    evidence=dict(
        onsetToleranceMs=50,
        caveats=['Small, independently annotated excerpts. Missing annotations can make valid extra detections look like false positives.',
                 'Suno and Basic Pitch emit unassigned candidates, so scored lead hits for those rows use all events.',
                 'Neither acoustic note ends nor this comparison are a ground truth for dry synth key-off.'],
        scores=scores)
    args.output.write_text(json.dumps(evidence,indent=2)+'\n')
    for region,results in scores.items():
        for name,rr in results.items():
            print(region,name,[(k,v['matched'],v['labels'],v['candidates']) for k,v in rr.items()])

if __name__=='__main__':
    main()
