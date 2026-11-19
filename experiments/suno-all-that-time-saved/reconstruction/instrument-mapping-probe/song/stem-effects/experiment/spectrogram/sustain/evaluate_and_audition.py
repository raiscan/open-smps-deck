"""Score the frozen note fit and prepare excerpt-only audition source and audio."""
import argparse
import copy
import json
import wave
from pathlib import Path

import numpy as np
from scipy.optimize import linear_sum_assignment

RATE=48000
TOLERANCE=.030

def load(path):
    return json.loads(path.read_text())

def write(path, data):
    path.write_text(json.dumps(data,indent=2)+'\n')

def score(labels, predicted):
    cost=np.full((len(labels),len(predicted)),100.)
    for i,a in enumerate(labels):
        for j,b in enumerate(predicted):
            diff=abs(a['attack_s']-b['start'])
            if a['midi']==b['pitch'] and diff<=TOLERANCE:
                cost[i,j]=diff
    hits=[(i,j) for i,j in zip(*linear_sum_assignment(cost)) if cost[i,j]<100]
    ends=[abs(labels[i]['release_s']-predicted[j]['end'])*1000 for i,j in hits
          if labels[i]['release_s'] is not None]
    return dict(labels=len(labels),candidates=len(predicted),hits=len(hits),
                missed=[{'midi':a['midi'],'seconds':a['attack_s']} for i,a in enumerate(labels)
                        if all(i!=k for k,_ in hits)],
                unmatchedCandidates=len(predicted)-len(hits),
                medianAcousticOffsetErrorMs=float(np.median(ends)) if ends else None,
                matchedOffsets=len(ends))

def excerpt(path,start,end):
    with wave.open(str(path),'rb') as w:
        assert (w.getframerate(),w.getnchannels(),w.getsampwidth())==(RATE,2,2)
        w.setpos(round(start*RATE))
        return np.frombuffer(w.readframes(round((end-start)*RATE)),dtype='<i2').reshape(-1,2).astype(float)/32768

def write_wave(path,audio,rate):
    samples=np.rint(np.clip(audio,-1,1)*32767).astype('<i2')
    with wave.open(str(path),'wb') as w:
        w.setparams((2,2,rate,0,'NONE','not compressed'))
        w.writeframes(samples.tobytes())

def montage(parts,rate):
    length=round(rate*.015)
    fade=np.linspace(0,1,length)[:,None]
    ready=[]
    for part in parts:
        part=part.copy()
        part[:length]*=fade
        part[-length:]*=fade[::-1]
        if ready:
            ready.append(np.zeros((int(rate*.35),2)))
        ready.append(part)
    return np.concatenate(ready)

def overlay(audio, start, notes):
    guide=np.zeros(len(audio))
    for n in notes:
        first=max(0,round((n['start']-start)*RATE))
        end=min(len(guide),round((n['end']-start)*RATE))
        if end<=first:
            continue
        t=np.arange(end-first)/RATE
        envelope=np.minimum(1,np.minimum(t/.008,(t[-1]-t)/.012))
        guide[first:end]+=.075*envelope*np.sin(2*np.pi*440*2**((n['pitch']-69)/12)*t)
    return .9*audio+guide[:,None]

def edit(project, regions, decoded):
    result=copy.deepcopy(project)
    result['name'] += ' / spectrogram melody audition'
    lanes={s['id']:s for s in result['sequences']}
    for region in regions:
        start,end=(round(region[key]*60) for key in ('start_s','end_s'))
        recovered=decoded[region['id']]
        for role,lane_id in [('lead','fm1'),('lead','lead-reinforcement'),('harmony','fm3'),('harmony','fm4')]:
            lane=lanes[lane_id]
            # Preserve events outside the excerpt, including notes starting
            # before its boundary. Only fit notes in available intervals.
            kept=[n for n in lane['notes'] if n['tick']<start or n['tick']>=end]
            occupied=max((n['tick']+n['duration'] for n in kept if n['tick']<start),default=start)
            new=[]
            for i,n in enumerate(recovered[role]):
                if role=='harmony' and i%2 != (lane_id=='fm4'):
                    continue
                t=max(start,round(n['start']*60))
                stop=min(end,round(n['end']*60))
                if t<occupied or stop<=t:
                    continue
                occupied=stop
                new.append(dict(id=f"spectral-{region['id']}-{i}",tick=t,duration=stop-t,pitch=n['pitch']))
            lane['notes']=sorted(kept+new,key=lambda n:n['tick'])
    return result

def main():
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument('root',type=Path)
    cli.add_argument('--render',type=Path)
    args=cli.parse_args()
    out=args.root
    parent=out.parent.parent
    song=parent.parent.parent
    dev=load(parent/'annotations.json')['regions']
    hold=load(out/'holdout.json')['regions']
    decoded=load(out/'melody-notes.json')
    baseline=load(parent/'joint-midi/joint-notes.json')
    basic=load(parent/'basic-pitch.json')['notes']
    regions=dev+hold
    if args.render:
        native=[]
        for r in regions:
            native.append(excerpt(args.render,r['start_s']-.20,r['end_s']+.20))
        write_wave(out/'native-audition.wav',montage(native,RATE),RATE)
        return
    reports={}
    overlays=[]
    for region in regions:
        key=region['id']
        start,end=region['start_s'],region['end_s']
        notes=decoded[key]
        events=region['notes']
        lead=[n for n in events if n['confidence']=='high' and n['voice']=='lead']
        harmony=[n for n in events if n['confidence']=='high' and n['voice']=='harmony']
        candidates=[n for n in basic if start-.038 <= n['start']<end]
        previous=[n for n in baseline['lead'] if start-.038 <= n['start']<end]
        reports[key]=dict(
            leadCandidates=score(lead,candidates),
            priorJointLead=score(lead,previous),
            recoveredLead=score(lead,notes['lead']),
            recoveredHarmony=score(harmony,notes['harmony']),
            mediumConfidenceLabels=sum(n['confidence']=='medium' for n in events))
        start-=.10
        end+=.10
        overlays.append(overlay(excerpt(song/'stem-effects/synth.wav',start,end),start,notes['lead']))
    output=dict(onsetToleranceMs=int(TOLERANCE*1000),regions=reports,
                releaseMethod='Sustained notes use harmonic support and a next-attack boundary. Short notes use an approximate gate. Acoustic release is not a known key-off.',
                note='Unmatched candidates may include plausible notes outside the independently annotated lead/harmony. Holdout labels were not used by recovery.')
    write(out/'evaluation.json',output)
    write(out/'audition.smps.json',edit(load(song/'arrangement.smps.json'),regions,decoded))
    write_wave(out/'sine-overlay.wav',montage(overlays,RATE),round(RATE*.75))
    print({k:{label:(s['hits'],s['labels'],s['candidates']) for label,s in row.items() if isinstance(s,dict)}
           for k,row in reports.items()})

if __name__=='__main__':
    main()
