"""Score-guided stereo synth-stem experiment. Requires NumPy and SciPy."""
import argparse
import csv
import json
import wave
from pathlib import Path

import numpy as np
from scipy import signal

RATE=12000
HOP=120
SIZE=768
FFT=2048
PITCHES=np.arange(52,98)
ROLES=('lead','harmony-1','harmony-2')
WINDOW=np.hanning(SIZE)

def read(path):
    with wave.open(str(path),'rb') as wav:
        assert (wav.getframerate(),wav.getnchannels(),wav.getsampwidth())==(48000,2,2)
        return np.frombuffer(wav.readframes(60*48000),'<i2').reshape(-1,4,2).mean(axis=1)/32768

def spectrum(audio):
    padded=np.pad(audio,((SIZE//2,SIZE//2),(0,0)))
    windowed=np.lib.stride_tricks.sliding_window_view(padded,SIZE,axis=0)[::HOP]
    return np.fft.rfft(windowed*WINDOW,n=FFT,axis=2).astype(np.complex64)

def dictionary(path):
    pcm=np.fromfile(path,'<i2').reshape(len(ROLES),len(PITCHES),24*200).astype(np.float32)/32768
    states=[]
    for centers in [(230,410),(680,1000,1350,1700,2100,2500),(3050,3450,3850)]:
        rows=[]
        for center in centers:
            idx=np.arange(-SIZE//2,SIZE//2)+center
            snippet=np.zeros((*pcm.shape[:2],SIZE))
            good=(idx>=0)&(idx<pcm.shape[-1])
            snippet[...,good]=pcm[...,idx[good]]
            rows.append(abs(np.fft.rfft(snippet*WINDOW,n=FFT))**2)
        states.append(np.mean(rows,axis=0))
    return np.stack(states,axis=2).astype(np.float32)

def prior(times,midi,basic):
    boost=np.ones((len(times),len(PITCHES)),np.float32)
    for notes,delta in [(midi,.11),(basic,.14)]:
        for n in notes:
            pitch=int(n['pitch'])
            if PITCHES[0] <= pitch <= PITCHES[-1]:
                mask=(times>=n['start']-.045)&(times<n['end']+.075)
                boost[mask,pitch-PITCHES[0]]+=delta
    return boost

def joint_fit(power,templates,boost):
    # Downweight strong bands using the recording's own median spectrum.
    support=np.mean(power,axis=0)
    keep=(np.arange(power.shape[-1])*RATE/FFT>=105)&(np.arange(power.shape[-1])*RATE/FFT<=5200)
    weight=(support[keep]+.007*np.max(support))**-.40
    shape=templates.shape[:-1]
    raw=templates.reshape(-1,templates.shape[-1])
    weighted=raw[:,keep]*weight
    norm=np.linalg.norm(weighted,axis=1).clip(1e-8)
    atoms=np.ascontiguousarray(weighted/norm[:,None])
    gram=atoms@atoms.T
    observed=np.ascontiguousarray(power[:,keep]*weight)
    residual=(observed@atoms.T).astype(np.float32)
    prior_weight=np.tile(np.repeat(boost,shape[2],axis=1),(1,shape[0]))
    scale=np.linalg.norm(observed,axis=1).clip(1e-6)
    frame=np.arange(len(power))
    coefficients=np.zeros_like(residual)
    # Coordinate updates, with a small threshold to avoid filling quiet
    # residual bins with spurious notes. Each update fits against the others.
    for _ in range(6):
        entry=np.argmax(residual*prior_weight,axis=1)
        gain=np.maximum(0,residual[frame,entry]-.09*scale)
        coefficients[frame,entry]+=gain
        residual-=gram[entry]*gain[:,None]
    contributions=(coefficients/norm).reshape(len(power),*shape)
    return contributions,coefficients.reshape(len(power),*shape),scale

def trace(values,scale,skip=None):
    levels=(values/scale[:,None]).clip(0,1)
    strength=np.max(levels,axis=1)
    active=np.clip(levels*2.4,0,1)
    if skip is not None:
        rows=np.flatnonzero(skip>=0)
        active[rows,skip[rows]]*=.12
    scores=np.column_stack([active,np.full(len(active),.28)])
    pitch=np.r_[PITCHES, -1]
    intervals=np.abs(pitch[:,None]-pitch)
    movement=np.where(intervals==0,0,.24+np.minimum(intervals,12)*.018)
    movement[-1,:]=.22
    movement[:,-1]=.22
    movement[-1,-1]=0
    back=np.empty(scores.shape,np.int16)
    current=scores[0]
    for t in range(1,len(scores)):
        transition=current[:,None]-movement
        back[t]=np.argmax(transition,axis=0)
        current=scores[t]+np.max(transition,axis=0)
    states=np.empty(len(scores),np.int16)
    states[-1]=np.argmax(current)
    for t in range(len(scores)-1,0,-1):
        states[t-1]=back[t,states[t]]
    states=np.where(states==len(PITCHES),-1,states)
    return states,strength

def events(states):
    boundaries=np.r_[0,np.flatnonzero(np.diff(states))+1,len(states)]
    return [dict(pitch=int(PITCHES[states[a]]),start=round(a*HOP/RATE,4),end=round(b*HOP/RATE,4))
            for a,b in zip(boundaries[:-1],boundaries[1:]) if b-a>=4 and states[a]>=0]

def separated(stereo,coeff,templates):
    lead=np.einsum('tps,psb->tb',coeff[:,0],templates[0],optimize=True)
    combined=np.einsum('trps,rpsb->tb',coeff,templates,optimize=True)
    mask=np.sqrt(np.divide(lead,combined,out=np.zeros_like(lead),where=combined>1e-8))
    audio=np.fft.irfft(stereo*mask[:,None],n=FFT,axis=-1)[...,:SIZE]
    result=np.zeros(((len(audio)-1)*HOP+SIZE,2))
    denominator=np.zeros(len(result))
    for i,windowed in enumerate(audio):
        offset=i*HOP
        result[offset:offset+SIZE]+=windowed.T*WINDOW[:,None]
        denominator[offset:offset+SIZE]+=WINDOW**2
    return (result/np.maximum(denominator[:,None],.01))[SIZE//2:-SIZE//2]

def write_wave(path,pcm):
    with wave.open(str(path),'wb') as wav:
        wav.setparams((2,2,RATE,0,'NONE','not compressed'))
        wav.writeframes(np.rint(np.clip(pcm,-1,1)*32767).astype('<i2').tobytes())

def main():
    cli=argparse.ArgumentParser(description=__doc__)
    for name in ('stem','templates','midi','output'):
        cli.add_argument(name,type=Path)
    cli.add_argument('--basic-pitch',type=Path)
    args=cli.parse_args()
    audio=read(args.stem)
    stereo=spectrum(audio)
    frames=np.arange(len(stereo))*HOP/RATE
    power=np.mean(np.abs(stereo)**2,axis=1)
    templates=dictionary(args.templates)
    with args.midi.open() as file:
        midi=[dict(pitch=int(x['pitch']),start=float(x['start']),end=float(x['end']))
              for x in csv.DictReader(file) if x['part']=='0']
    basic=json.loads(args.basic_pitch.read_text()) if args.basic_pitch else []
    if isinstance(basic,dict):
        basic=basic['notes']
    boost=prior(frames,midi,basic)
    physical,fit,scale=joint_fit(power,templates,boost)
    scores=fit.sum(axis=3)
    # A pitch has one role at a time in this experiment. Unison is left to
    # the downstream renderer's existing lead double.
    lead,_=trace(scores[:,0],scale)
    harmony1,_=trace(scores[:,1],scale,lead)
    harmony2,_=trace(scores[:,2],scale,np.where(harmony1>=0,harmony1,lead))
    args.output.mkdir(parents=True,exist_ok=True)
    result=dict(zip(ROLES,map(events,[lead,harmony1,harmony2])))
    (args.output/'joint-notes.json').write_text(json.dumps(result,indent=2)+'\n')
    write_wave(args.output/'lead-masked.wav',separated(stereo,physical,templates))
    print({role:len(notes) for role,notes in result.items()})

if __name__=='__main__':
    main()
