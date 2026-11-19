import csv
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

root = Path(sys.argv[1])
rate, length, size = 48000, 11200, 12
pcm = {int(path.stem): np.fromfile(path, dtype='>f4').reshape(size,length).astype(float)
       for path in root.glob('[0-9][0-9].f32')}
offset_pcm = {int(path.stem.split('-')[0]): np.fromfile(path, dtype='>f4').reshape(size,length).astype(float)
              for path in root.glob('*-offset.f32')}
window = np.hanning(2048)
bins = np.fft.rfftfreq(4096, 1/rate)
use_bins = (bins >= 50) & (bins <= 10000)
centers = [.030,.062,.102,.144,.184,.209]

def features(signal):
    return np.concatenate([
        abs(np.fft.rfft(signal[...,start:start+len(window)]*window,n=4096))[...,use_bins]**2
        for t in centers
        for start in [round(rate*t)-len(window)//2]
    ], axis=-1)

def normalize(v):
    return v/np.linalg.norm(v,axis=-1,keepdims=True).clip(1e-20)

cache = {}
results = defaultdict(lambda: np.zeros(4,int))
for row in csv.DictReader((root/'baseline.csv').open()):
    p, q, db, a, b, old = map(int,row.values())
    if (p,q) not in cache:
        p_features = features(pcm[p])
        q_features = features(pcm[q])
        # Compress loudest spectral bins, leaving a floor for spectral regions
        # where the catalogue has little energy.
        proxy = np.maximum(np.max(p_features,axis=0),np.max(q_features,axis=0))
        weight = (proxy + np.max(proxy)*.001)**-.40
        x, y = normalize(p_features*weight), normalize(q_features*weight)
        cross = x @ y.T
        cache[p,q] = weight, x, y, cross
    weight,x,y,cross = cache[p,q]
    dry,under = pcm[p][a],offset_pcm[q][b]
    gain = 10**(db/20)*np.linalg.norm(dry)/np.linalg.norm(under)
    observation = normalize(features(dry+gain*under)*weight)
    u, v = (x @ observation)[:,None], (y @ observation)[None,:]
    det = np.maximum(1e-9,1-cross**2)
    alpha, beta = (u - cross*v)/det, (v - cross*u)/det
    score = np.where((alpha >= 0) & (beta >= 0),
                     alpha*u+beta*v,np.maximum(u*u,v*v))
    new_p,new_q = np.unravel_index(np.argmax(score),score.shape)
    results[p,q,db] += [1,old==a,new_p==a,new_p==a and new_q==b]

print('target,interferer,levelDb,trials,currentCorrect,jointCorrect,bothCorrect')
for key, counts in sorted(results.items()):
    print(','.join(map(str,[*key,*counts])))
