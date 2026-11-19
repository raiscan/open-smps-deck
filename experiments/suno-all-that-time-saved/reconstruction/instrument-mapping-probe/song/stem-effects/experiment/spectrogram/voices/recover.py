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
                flux=float(rise[k]),growing=int(sum(rises>.22)),partialRises=rises.tolist(),contrast=contrast,
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

def sustain_support(audio, note, limit):
    start = note['start']
    f0 = 440 * 2**((note['pitch']-69)/12)
    times, power, floor, _ = spectral_region(audio, start-.025,
        min(max(limit+.035,start+.095), start+.65, len(audio)/RATE-.035), 2880, f0*np.arange(1,7))
    partials = np.maximum(0, (power-floor).mean(axis=1))
    body = np.median(partials[(times>=start+.025)&(times<start+.07)],axis=0)
    weights = np.sqrt(body)
    support = np.sum(weights*np.clip(partials/(body+.12),0,1.2),axis=1)/max(.001,weights.sum())
    # Allow brief interference dips, but stop before a separate onset can
    # revive a released pitch. Window blur makes the endpoint approximate.
    weak = np.flatnonzero((times>=start+.065)&(support<.32))
    groups = np.split(weak,np.flatnonzero(np.diff(weak)!=1)+1)
    release = next((times[g[0]] for g in groups if len(g)>=6), min(times[-1],limit))
    return times, support, float(release)

def select(notes, exemplars, audio, limit):
    if not notes:
        return dict(gridPeriod=None,lead=[],harmony=[],candidates=[])
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
    first_slot, last_slot = min(onset_slots), max(onset_slots)
    curves = {id(n): sustain_support(audio,n,limit) for n in notes}
    holds = {}
    for n in notes:
        times, support, release = curves[id(n)]
        for slot in range(n['slot']+1,last_slot+1):
            center = slot*period+phase
            if center+.035>release:
                break
            simultaneous = onset_slots.get(slot,[])
            # Same-pitch candidates with a fresh rise out of a trough are
            # attacks even when their MIDI pitch equals the held pitch.
            reattack = any(x['pitch']==n['pitch'] and x['flux']>=1.05
                           and np.min(support[abs(times-(x['start']-.013))<.012],initial=1)<.48
                           for x in simultaneous)
            if reattack:
                break
            local = np.interp(center+.025,times,support)
            if local>=.32:
                holds.setdefault(slot,[]).append((n,True,.70+.55*n["score"]+.55*min(1,local)-.22*(slot-n["slot"]-1)))
            else:
                break

    states = [[(None,False,.85), *((n,False,n['score']) for n in onset_slots.get(slot,[])),
               *holds.get(slot,[])] for slot in range(first_slot,last_slot+1)]
    scores = [state[2] for state in states[0]]
    backs = []
    for prior, options in zip(states,states[1:]):
        incoming = []
        back = []
        for n, held, emission in options:
            choices = []
            for (before,_,_), value in zip(prior,scores):
                if held:
                    choices.append(value if before is n else -np.inf)
                    continue
                if n is None or before is None:
                    penalty = .20 if n is not before else 0.
                else:
                    delta = abs(n['pitch']-before['pitch'])
                    # A steady stepwise line should not flip to its louder
                    # parallel voice for a single attack. Cap the penalty
                    # so a clear leap is still possible.
                    penalty = (.026 if n["slot"]==last_slot else .125)*min(delta,6)+.14*(delta in (12,19,24))
                choices.append(value-penalty)
            k = int(np.argmax(choices))
            incoming.append(choices[k]+emission)
            back.append(k)
        scores = incoming
        backs.append(back)
    best = int(np.argmax(scores))
    path = [states[-1][best]]
    for i in reversed(range(len(backs))):
        best = backs[i][best]
        path.append(states[i][best])
    path.reverse()
    lead = []
    for i,(n,held,_) in enumerate(path):
        if n is None or held:
            continue
        j = i+1
        while j<len(path) and path[j][0] is n and path[j][1]:
            j+=1
        next_note = path[j][0] if j<len(path) else None
        supported = curves[id(n)][2]
        end = min(supported, limit) if j>i+1 else min(n['end'],n['start']+period*.86,limit)
        if j<len(path):
            boundary = next_note['start']-.008 if next_note is not None else (first_slot+j)*period+phase
            end = min(end,boundary)
        lead.append({**n,'end':max(n['start']+.027,end),'heldSlots':j-i-1,'supportedUntil':supported})
    harmony = []
    last_harmony = None
    for slot, (anchor, held, _) in zip(range(first_slot,last_slot+1),path):
        if anchor is None:
            last_harmony = None
            continue
        # Verify a parallel attack against bins the chosen lead cannot
        # explain. The second voice can cross above the lead.
        similar = []
        lead_hz = 440 * 2**((anchor['pitch']-69)/12)
        for n in onset_slots.get(slot,[]):
            if n['pitch']==anchor['pitch'] or abs(n['pitch']-anchor['pitch'])>12 or n['confidence']<.26 or n['partialRises'][0]<1.1:
                continue
            lag = abs(n['start']-anchor['start'])
            if not held and lag>.035:
                continue
            fundamental = 440 * 2**((n['pitch']-69)/12)
            ratios = fundamental*np.arange(1,7)/lead_hz
            unshared = abs(ratios-np.round(ratios))*lead_hz>30
            rises = np.array(n['partialRises'])[unshared]
            if sum(rises>.67)<2:
                continue
            if n['score']<max(1.42,.70*anchor['score']) or n['contrast']<1.6:
                continue
            continuity = 0. if last_harmony is None else .07*min(6,abs(n['pitch']-last_harmony['pitch']))
            rank = n['score']+.23*np.mean(np.clip(rises,0,3))-.24*(n['pitch']>anchor['pitch'])-continuity
            similar.append((rank,n))
        if similar:
            winner=max(similar,key=lambda x:x[0])[1]
            harmony.append(winner)
            last_harmony=winner
        else:
            last_harmony=None
    def shape(n,following=None):
        # The near-note model end can include ringing. Bound it by the next
        # attack, or by the local short-note gate when it extends past a slot.
        end = min(n['end'], n['start']+period*.86)
        if following is not None:
            end=min(end,following['start']-.008)
        return {**n,'end':max(n['start']+.027,end)}
    return dict(gridPeriod=period,lead=lead,
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
        results[r['id']]=select(candidates(signal,basic,r['start_s'],r['end_s']),exemplars,signal,r['end_s'])
    args.output.write_text(json.dumps(results,indent=2)+'\n')

if __name__=='__main__':
    main()
