"""Local lead-effect audition. Generate native S3K candidates, score held-out audio."""
import argparse
import copy
import json
import math
import subprocess
import wave
from pathlib import Path

import numpy as np

PITCH_REGISTERS = (644,683,723,766,813,860,911,965,1023,1084,1148,1216)
LANES = ('fm1','lead-reinforcement')
PROFILES = {
    'baseline': None,
    'stereo-pan': ('wide',0,0),
    'stereo-double': ('wide',8,0),
    'chorus-light': ('wide',4,4),
    'chorus-medium': ('wide',9,7),
    'chorus-heavy': ('wide',14,10),
    'chorus-center-plus-side': ('one-side',9,7),
    'stereo-low-blend': ('one-side',0,0),
    'double-low-blend': ('one-side',6,0),
    'chorus-low-blend': ('one-side',6,5),
    'chorus-centered': ('center',9,7),
    'vibrato': ('center',0,13),
}

def candidate(original, name):
    project = copy.deepcopy(original)
    project['name'] += ' / ' + name
    profile = PROFILES[name]
    if profile is None:
        return project
    pan_mode, offset, depth = profile
    for sequence in project['sequences']:
        if sequence['id'] not in LANES:
            continue
        primary = sequence['id'] == LANES[0]
        pan = (0x80 if primary else 0x40) if pan_mode=='wide' else (0x40 if pan_mode=='one-side' and not primary else 0xc0)
        direction = -1 if primary and name!='vibrato' else 1
        rate, phase = (.82,.15) if primary or name=='vibrato' else (1.09,1.1)
        if name.endswith('-low-blend') and not primary:
            # The chip has discrete pan bits. Put a quiet detuned voice to one
            # side while keeping the dominant voice audible in both channels.
            sequence['volume'] -= 16
            for command in sequence['commands']:
                if command['command']=='VOLUME':
                    command['arguments'][0]=max(21,command['arguments'][0]-16)
        events = [{'id':'effect-pan','tick':0,'command':'PAN','arguments':[pan]}]
        previous = 0
        for note in sequence['notes']:
            for tick in range(note['tick'],note['tick']+note['duration'],2):
                cents = direction*(offset+depth*math.sin(math.tau*rate*tick/60+phase))
                value = round(PITCH_REGISTERS[note['pitch']%12]*(2**(cents/1200)-1))
                if value!=previous:
                    events.append({'id':f'effect-detune-{tick}','tick':tick,'command':'DETUNE','arguments':[value&255]})
                    previous = value
        sequence['commands'] = sorted(sequence['commands']+events,key=lambda e:e['tick'])
    return project

def read_audio(path):
    with wave.open(str(path),'rb') as wav:
        assert (wav.getframerate(),wav.getnchannels(),wav.getsampwidth())==(48000,2,2)
        frames = min(wav.getnframes(),60*48000)
        return np.frombuffer(wav.readframes(frames),dtype='<i2').reshape(-1,4,2).mean(axis=1)/32768

def probe_windows(project):
    by_id = {s['id']:s for s in project['sequences']}
    notes = by_id[LANES[0]]['notes']
    double = {n['tick']:n for n in by_id[LANES[1]]['notes']}
    backups = [n for s in project['sequences'] if s['id'] not in LANES for n in s['notes']]
    for n in notes:
        tick, duration, midi = n['tick'],n['duration'],n['pitch']
        twin = double.get(tick)
        if duration < 7 or not twin or twin['pitch']!=midi or twin['duration']<7:
            continue
        center = (tick+min(duration,twin['duration'])/2)/60
        if not 2 < center < 59.8:
            continue
        others = [x['pitch'] for x in backups if x['tick']-2 < center*60 < x['tick']+x['duration']+2]
        f0=440*2**((midi-69)/12)
        for h in range(1,7):
            hz=h*f0
            if not 480<=hz<4400:
                continue
            width=max(23,hz*.027)
            if any(1 <= round(hz/f) <= 4 and abs(hz-round(hz/f)*f) < width+12
                   for f in (440*2**((p-69)/12) for p in others)):
                continue
            yield center,hz,width,tick

def features(audio,probes):
    n=1152
    nfft=8192
    window=np.hanning(n)[:,None]
    motion_window=np.hanning(720)[:,None]
    freq=np.fft.rfftfreq(nfft,1/12000)
    observations=[]
    for t,hz,width,_ in probes:
        start=round(t*12000)-n//2
        bins=(freq>hz-width)&(freq<hz+width)
        z=np.fft.rfft(audio[start:start+n]*window,n=nfft,axis=0)[bins]
        energy=(abs(z)**2).sum(axis=0)
        total=energy.sum()
        side=np.sum(abs(z[:,0]-z[:,1])**2)/max(1e-12,2*total)
        asymmetry=(energy[0]-energy[1])/max(1e-12,total)
        centroids=(freq[bins,None]*(abs(z)**2)).sum(axis=0)/np.maximum(energy,1e-12)
        split=(centroids[1]-centroids[0])*1200/(hz*math.log(2))
        def mono_centroid(delta):
            start_at=round(t*12000)+delta-360
            power=np.sum(abs(np.fft.rfft(audio[start_at:start_at+720]*motion_window,n=nfft,axis=0)[bins])**2,axis=1)
            return float(freq[bins]@power/max(1e-12,power.sum()))
        drift=abs(mono_centroid(210)-mono_centroid(-210))*1200/(hz*math.log(2))
        observations.append((total,side,asymmetry,abs(split),drift))
    return np.array(observations)

def render(jar,path,wav):
    subprocess.run(['java','-jar',str(jar),'render-project',str(path),str(wav),'--frames','3600'],
                   stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,check=True)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('project','reference','jar','work'):
        parser.add_argument(name,type=Path)
    parser.add_argument('--renders',type=Path,help='Reuse an unchanged candidate bank and save this evaluation in WORK.')
    args=parser.parse_args()
    original=json.loads(args.project.read_text())
    probes=list(probe_windows(original))
    ref=features(read_audio(args.reference),probes)
    times=np.array([p[0] for p in probes])
    eligible_fit=(np.floor(times/4)%2==0)&~((times>=8)&(times<20))
    quiet=ref[:,0] > np.quantile(ref[eligible_fit,0],.30)
    fit=eligible_fit&quiet
    validation=(np.floor(times/4)%2==1)&quiet&~((times>=8)&(times<20))
    test=(times>=8)&(times<20)&quiet
    if any(mask.sum()<15 for mask in (fit,validation,test)):
        parser.error('Insufficient paired lead-harmonic observations in the fit, validation, or test window.')
    args.work.mkdir(parents=True, exist_ok=True)
    result={}
    observed={}
    for name in PROFILES:
        directory=args.renders or args.work
        project=directory/(name+'.json')
        wav=directory/(name+'.wav')
        content=json.dumps(candidate(original,name),indent=2)+'\n'
        if not wav.exists():
            project.write_text(content)
            render(args.jar,project,wav)
        elif project.read_text()!=content:
            parser.error(f'Candidate changed. Use a fresh render directory for {name}.')
        take=features(read_audio(wav),probes)
        # Separate width and pitch split. Evaluate distribution summaries so
        # stereo orientation and free-running chorus phase needn't match.
        def describe(data,mask):
            group=data[mask]
            return np.array([np.median(group[:,1]),np.median(np.abs(group[:,2])),
                             np.median(np.minimum(40,group[:,3]))/40,
                             np.median(np.minimum(30,group[:,4]))/30])
        result[name]={}
        observed[name]={}
        for group,mask in [('fit',fit),('validation',validation),('test',test)]:
            left,right=describe(ref,mask),describe(take,mask)
            result[name][group]=float(np.dot(np.abs(left-right),[.46,.12,.25,.17]))
            observed[name][group]=dict(reference=left.tolist(),candidate=right.tolist())
    best=min(result,key=lambda name:result[name]['fit'])
    runner=min((n for n in result if n!=best),key=lambda name:result[name]['fit'])
    selected=best if all(result[best][split]+.008<result['baseline'][split] for split in ('fit','validation')) else 'baseline'
    evidence=dict(sections={group:dict(harmonics=int(mask.sum()),notes=len(set(np.array([p[3] for p in probes])[mask]))) for group,mask in [('fit',fit),('validation',validation),('test',test)]},
                  bestFit=best, runnerUp=runner, selected=selected, distance=result, observations=observed,
                  note='Lower is closer. The aggregate score compares stereo side energy, channel balance, inter-channel pitch split, and common pitch drift around arranged lead harmonics. Other sources in the mix can still contribute.')
    (args.work/'effect-fit.json').write_text(json.dumps(evidence,indent=2)+'\n')
    print(json.dumps({k:evidence[k] for k in ('sections','bestFit','selected','distance')},indent=2))

if __name__=='__main__':
    main()
