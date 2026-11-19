import numpy as np
import wave, math
from pathlib import Path

RATE=48000
# C5, Eb5, Bb4, D5, F5. The fit and check sets use different note attacks.
PITCHES=[72,75,70,74,77]
FIT=[(4.578125,72),(5.453125,75),(9.411458,70),(13.359375,74),(15.666667,77)]
CHECK=[(8.088542,72),(14.458333,74),(15.338542,75)]
STAGES=[.023,.068,.125,.172]
HARMONICS=np.arange(1,10)
def read(path):
    with wave.open(str(path)) as f:
        assert f.getframerate()==RATE and f.getnchannels()==2 and f.getsampwidth()==2
        return np.frombuffer(f.readframes(f.getnframes()), '<i2').reshape(-1,2).astype('float32').mean(axis=1)/32768
def features(signal,t,pitch):
    f=440*2**((pitch-69)/12)
    n=round(.040*RATE)
    time=np.arange(n)/RATE
    win=np.hanning(n)
    search=f*np.exp2(np.linspace(-.12,.12,17)/12)
    def kernel(freq):
        return np.exp(-2j*np.pi*freq[...,None]*time)*win
    samples=np.stack([signal[round((t+offset)*RATE)-n//2:round((t+offset)*RATE)+n//2] for offset in STAGES])
    if len(samples[0])!=n:return None
    # Refine the fundamental across sustain windows so a shallow pitch bend does not look like spectral rolloff.
    peak=np.argmax(np.sum(np.abs(samples[1:] @ kernel(search).T)**2,axis=0))
    amplitudes=2*np.abs(samples @ kernel(search[peak]*HARMONICS).T)/win.sum()
    spectrum=np.mean(amplitudes[1:]**2,axis=0)
    rms=float(np.sqrt(spectrum.sum()/2))
    centroid=float((spectrum*HARMONICS).sum()/spectrum.sum())
    relative=10*np.log10(np.maximum(spectrum/spectrum.sum(),1e-7))
    env=10*np.log10(np.maximum((amplitudes**2).sum(axis=1)/spectrum.sum(),1e-7))
    return dict(rms=rms,centroid=centroid,relative=relative,env=env)
if __name__=='__main__':
    import sys
    for p in map(Path,sys.argv[1:]):
        print(p.name)
        y=read(p)
        for start,pitch in FIT+CHECK:
            x=features(y,start,pitch)
            print(start,pitch,round(20*math.log10(x['rms']),1),round(x['centroid'],2),np.round(x['relative'][:6],1),np.round(x['env'],1))
