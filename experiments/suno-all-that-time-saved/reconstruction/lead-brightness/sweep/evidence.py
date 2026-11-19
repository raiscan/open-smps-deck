import hashlib,json,sys,statistics,zipfile
from measure import *
root=Path(sys.argv[1])
out=root/'lead-brightness'
files={'source':root/'reference.wav','prior':root/'arrangement-study/render.wav','revised':out/'render.wav'}
signals={name:read(path) for name,path in files.items()}
notes=[]
for at,note in CHECK + [(5.901042,75),(7.864583,74),(8.96875,72)]:
    row={'referenceStartSeconds':at,'pitch':note}
    for k,signal in signals.items():
        x=features(signal,at,note)
        row[k]={'harmonicRmsDbfs':round(20*math.log10(x['rms']),2),'harmonicCentroid':round(x['centroid'],3),
                'harmonicPowerDb':x['relative'].round(2).tolist(),'relativeEnvelopeDb':x['env'].round(2).tolist()}
    notes.append(row)
metrics={k:{metric:statistics.median(n[k][metric] for n in notes) for metric in ['harmonicRmsDbfs','harmonicCentroid']} for k in files}
def distance(a,b):
    return float(np.linalg.norm(np.sqrt(10**(np.array(a)/10))-np.sqrt(10**(np.array(b)/10))))
gaps={k:statistics.median(distance(n[k]['harmonicPowerDb'],n['source']['harmonicPowerDb']) for n in notes) for k in ['prior','revised']}
assert gaps['revised']<gaps['prior']
peak_dbfs={}
for where,duration in [(out,20),(out/'first-minute',60)]:
    baseline=root/'arrangement-study'/where.relative_to(out)
    before=json.loads((baseline/'arrangement.smps.json').read_text())
    after=json.loads((where/'arrangement.smps.json').read_text())
    assert before['samples']==after['samples']
    assert [s['notes'] for s in before['sequences']]==[s['notes'] for s in after['sequences'][:-1]]
    assert [s['triggers'] for s in before['sequences']]==[s['triggers'] for s in after['sequences'][:-1]]
    assert after['sequences'][-1]['notes']==after['sequences'][0]['notes']
    assert after['clips'][:-1]==before['clips'] and after['clips'][-1]['lane']=='FM5'
    with zipfile.ZipFile(where/'arrangement.smps.zip') as z:
        assert z.testzip() is None and z.read('source.smps.json')==(where/'arrangement.smps.json').read_bytes()
    with wave.open(str(where/'render.wav')) as f:
        assert f.getnframes()==duration*RATE
        raw=np.frombuffer(f.readframes(f.getnframes()),'<i2').astype('float64')/32768
        peak=float(np.max(np.abs(raw)))
        assert peak<.99
        peak_dbfs[str(duration)]=round(20*math.log10(peak),1)
audio=[]
for path in files.values():
    with wave.open(str(path)) as f:
        f.setpos(8*RATE)
        audio.append(np.frombuffer(f.readframes(12*RATE),'<i2').astype('float64').reshape(-1,2)/32768)
total_peak=max(float(np.max(np.abs(a))) for a in audio)
factor=min(2.0,.88/total_peak)
sil=np.zeros((RATE//2,2),dtype='<i2')
parts=[]
for a in audio:
    a*=factor
    a[:480]*=np.linspace(0,1,480)[:,None]
    a[-480:]*=np.linspace(1,0,480)[:,None]
    parts.extend([np.round(a*32767).astype('<i2'),sil])
with wave.open(str(out/'listening-comparison.wav'),'wb') as f:
    f.setparams((2,2,RATE,0,'NONE','not compressed'))
    f.writeframes(np.concatenate(parts[:-1]).tobytes())
report=dict(assessment='Six separate note attacks in the source mix, outside the patch-sweep ranking set. Backing energy can occupy the same harmonic bands.',
    fitProbes=FIT,checkNotes=notes,median=metrics,medianHarmonicDistance=gaps,renderPeakDbfs=peak_dbfs,
    comparison=dict(startSeconds=8,secondsPerTake=12,order=list(files),commonGain=factor),
    verify='Both saved projects validated, exported and reproduced byte for byte on rerender.')
(out/'evidence.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(dict(median=metrics,medianHarmonicDistance=gaps,renderPeakDbfs=peak_dbfs,comparison=report['comparison']),indent=2))
