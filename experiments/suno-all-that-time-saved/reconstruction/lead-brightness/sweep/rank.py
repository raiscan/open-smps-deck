from measure import *
import json, sys
root=Path(sys.argv[1])
reference=read(Path(sys.argv[2]))
fit=[features(reference,t,p) for t,p in FIT]
results=[]
for candidate in sorted((root/'probes').glob('*.wav')):
    y=read(candidate)
    notes=[features(y,.1+.7*i,p) for i,p in enumerate(PITCHES)]
    pair=list(zip(fit,notes))
    errors=np.array([np.linalg.norm(np.sqrt(10**(a['relative']/10))-np.sqrt(10**(b['relative']/10))) for a,b in pair])
    shapes=np.array([np.mean(abs(a['env']-b['env'])) for a,b in pair])
    boost=float(np.median([20*math.log10(a['rms']/b['rms']) for a,b in pair]))
    results.append(dict(id=candidate.stem,score=float(np.median(errors)+.015*np.median(shapes)),shape=float(np.median(shapes)),
                        spectral=float(np.median(errors)),boostDb=boost,centroid=float(np.median([n['centroid'] for n in notes]))))
results.sort(key=lambda row:(row['score'],row['id']))
(root/'ranking.json').write_text(json.dumps(results, indent=2))
for row in results[:10]+[r for r in results if r['id']=='previous']:
    print(row)
