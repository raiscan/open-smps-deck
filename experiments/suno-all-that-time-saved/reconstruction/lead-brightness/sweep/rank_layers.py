from measure import *
import json,statistics,sys
root=Path(sys.argv[1])
reference=read(Path(sys.argv[2]))
test=[(5.901042,75),(7.864583,74),(8.96875,72)]
groups=[FIT,CHECK,test]
targets=[[features(reference,t,p) for t,p in g] for g in groups]
def compare(signal):
 rows=[]
 for group,tgt in zip(groups,targets):
    audio=[features(signal,t,p) for t,p in group]
    norms=[float(np.linalg.norm(np.sqrt(10**(a['relative']/10))-np.sqrt(10**(b['relative']/10)))) for a,b in zip(tgt,audio)]
    diffs=[20*math.log10(b['rms']/a['rms']) for a,b in zip(tgt,audio)]
    errors=np.array(norms)+np.abs(diffs)*.023
    rows.append(dict(score=float(np.median(errors)),spectrum=statistics.median(norms),dbDelta=statistics.median(diffs)))
 return rows
ranking=[]
for k,file in [('linked',Path(sys.argv[3])),('single',root/'opening-single-render.wav'),*[(a.stem,a) for a in sorted((root/'layers').glob('*.wav'))]]:
    ranking.append(dict(id=k,results=compare(read(file))))
ranking.sort(key=lambda r:(r['results'][0]['score'],r['id']))
(root/'layer-ranking.json').write_text(json.dumps(ranking,indent=2))
for k in [*ranking[:5],*[r for r in ranking if r['id'] in ['linked','single']]]: print(k)
