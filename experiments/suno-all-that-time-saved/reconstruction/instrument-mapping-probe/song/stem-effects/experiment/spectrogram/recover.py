"""Stereo harmonic attack verification and phrase-level melody selection."""
import argparse
import json
import wave
from pathlib import Path
import numpy as np

RATE = 48000
HOP = 240
FFT = 8192

def read(path):
    with wave.open(str(path), 'rb') as wav:
        assert (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) == (RATE,2,2)
        return np.frombuffer(wav.readframes(60*RATE), '<i2').reshape(-1,2).astype('float32')/32768

def spectral_region(audio, start, end, window, freqs):
    # Evaluate only bins around the nominated partials at each time scale.
    first = round(start*RATE/HOP)
    last = round(end*RATE/HOP)
    frames = np.arange(first,last)
    starts = frames*HOP-window//2
    span = audio[starts[0]:starts[-1]+window]
    snippets = np.lib.stride_tricks.sliding_window_view(span,window,axis=0)[::HOP]
    z = np.fft.rfft(snippets*np.hanning(window),n=FFT,axis=-1)
    power = (abs(z)**2)
    centers = np.rint(freqs*FFT/RATE).astype(int)
    local = centers[:,None]+np.arange(-2,3)
    flank = centers[:,None]+np.r_[np.arange(-8,-4),np.arange(5,9)]
    selected = power[:,:,local].mean(axis=-1)
    floor = power[:,:,flank].mean(axis=-1)
    side = abs(z[:,0,local]-z[:,1,local]).mean(axis=-1)
    return frames*HOP/RATE, selected, floor, side

def characterize(audio, note):
    pitch = int(note['pitch'])
    onset = note['start']
    f0 = 440 * 2**((pitch-69)/12)
    freqs = f0*np.arange(1,7)
    times, short, _, _ = spectral_region(audio,onset-.065,onset+.075,1200,freqs)
    short = short.mean(axis=1)
    energy = np.log1p(short/.12)
    rise = np.average(np.maximum(0,energy[4:]-energy[:-4]),axis=1,
                      weights=np.clip(freqs/600,.45,1.6))
    attack_times = (times[4:]+times[:-4])/2
    near = np.flatnonzero(abs(attack_times-onset)<=.023)
    k = near[np.argmax(rise[near])]
    # Use the model timestamp as a prior. Steer it only toward a clear local rise.
    attack = onset*.55+float(attack_times[k])*.45
    rises = energy[k+4]-energy[k]
    times, long, floor, side = spectral_region(audio, onset+.025, onset+.095,4320,freqs)
    body = long.mean(axis=(0,1))
    contrast = float(np.mean(np.clip(np.log((body+.03)/(floor.mean(axis=(0,1))+.03)),0,5)))
    balance = float(np.log((long[:,0].sum()+.03)/(long[:,1].sum()+.03)))
    width = float(side.mean()/max(.001,np.sqrt(body).mean()))
    profile = np.log(body+.1)
    profile -= profile.mean()
    return dict(pitch=pitch,proposal=onset,start=attack,end=note['end'],confidence=note['confidence'],
                flux=float(rise[k]),growing=int(sum(rises>.22)),contrast=contrast,
                body=float(np.log1p(body.sum())),balance=balance,width=width,
                profile=profile.tolist())

def candidates(audio, notes, start, end):
    return [characterize(audio,n) for n in notes if start-.038<=n['start']<end
            and 52<=n['pitch']<=95 and n['end']-n['start']>=.04]

def grid(notes):
    onsets=np.array([n['proposal'] for n in notes])
    strengths=np.array([n['confidence']**3 for n in notes])
    periods=np.arange(.092,.133,.001)
    phases=np.exp(2j*np.pi*onsets[None,:]/periods[:,None])@strengths
    k=np.argmax(abs(phases))
    return periods[k],np.angle(phases[k])*periods[k]/(2*np.pi)

def select(notes, exemplars):
    period,phase=grid(notes)
    onset_slots={}
    for n in notes:
        n['slot']=round((n['start']-phase)/period)
        onset_slots.setdefault(n['slot'],[]).append(n)
    profiles=np.array([n['profile'] for n in exemplars])
    median=np.median(profiles,axis=0)
    power=np.array([n['body'] for n in notes])
    low,high=np.percentile(power,[20,90])
    for slot, options in onset_slots.items():
        for n in options:
            conf=n['confidence']
            power=float(np.clip((n['body']-low)/(high-low+.001),0,1.25))
            fit=float(np.sqrt(np.mean((n['profile']-median)**2)))
            ghost=any(x['pitch'] in (n['pitch']-12,n['pitch']-19) and
                      abs(x['start']-n['start'])<.023 and x['body']>n['body']-.5 and
                      abs(x['balance']-n['balance'])<.5 for x in options)
            n['score']=(1.20*conf+.48*power+.64*min(2,n['flux'])+.08*min(5,n['growing'])
                        + .22*n['contrast']-.47*fit-.42*ghost
                        -.24*abs(n['start']-slot*period-phase)/period)
    states=[]
    for slot in range(min(onset_slots),max(onset_slots)+1):
        states.append([None,*onset_slots.get(slot,[])])
    scores=[.85 if n is None else n['score'] for n in states[0]]
    backs=[]
    for options in states[1:]:
        prior=states[len(backs)]
        row=[]
        back=[]
        for n in options:
            choices=[]
            for before,v in zip(prior,scores):
                if n is None or before is None:
                    penalty=.20 if n is not before else 0.
                else:
                    delta=abs(n['pitch']-before['pitch'])
                    penalty=.026*min(delta,18)+.14*(delta in (12,19,24))
                choices.append(v-penalty)
            k=int(np.argmax(choices))
            row.append(choices[k]+(.85 if n is None else n['score']))
            back.append(k)
        scores=row
        backs.append(back)
    best=int(np.argmax(scores))
    chosen=[states[-1][best]]
    for i in reversed(range(len(backs))):
        best=backs[i][best]
        chosen.append(states[i][best])
    chosen=list(reversed(chosen))
    lead=[n for n in chosen if n is not None]
    harmony=[]
    for slot,n in zip(range(min(onset_slots),max(onset_slots)+1),chosen):
        eligible=[x for x in onset_slots.get(slot,[]) if x is not n and x['confidence']>=.54
                  and x['growing']>=2 and (n is None or x['pitch']<n['pitch'])
                  and (n is None or n['pitch']-x['pitch'] not in (12,19,24))]
        harmony.extend(sorted(eligible,key=lambda q:q['score'],reverse=True)[:2])
    def shape(n,following=None):
        # The near-note model end can include ringing. Bound it by the next
        # attack, or by the local short-note gate when it extends past a slot.
        end = min(n['end'], n['start']+period*.86)
        if following is not None:
            end=min(end,following['start']-.008)
        return {**n,'end':max(n['start']+.027,end)}
    return dict(gridPeriod=period,lead=[shape(n,next_note) for n,next_note in zip(lead,lead[1:]+[None])],
                harmony=[shape(n) for n in sorted(harmony,key=lambda n:n['start'])],candidates=notes)

def main():
    cli=argparse.ArgumentParser()
    for name in ('stem','basic','development','holdout','output'):
        cli.add_argument(name,type=Path)
    args=cli.parse_args()
    signal=read(args.stem)
    basic=json.loads(args.basic.read_text())['notes']
    dev=json.loads(args.development.read_text())['regions']
    # Learn timbre from the development labels only. The independent phrase
    # contributes boundaries, never pitch or timing labels, to the decoder.
    exemplars=[characterize(signal,dict(pitch=n['midi'],start=n['attack_s'],
                end=n['release_s'] or n['attack_s']+.09,confidence=1))
               for region in dev for n in region['notes'] if n['voice']=='lead' and n['confidence']=='high']
    hold=json.loads(args.holdout.read_text())['regions']
    results={}
    for r in dev+hold:
        results[r['id']]=select(candidates(signal,basic,r['start_s'],r['end_s']),exemplars)
    args.output.write_text(json.dumps(results,indent=2)+'\n')

if __name__=='__main__':
    main()
