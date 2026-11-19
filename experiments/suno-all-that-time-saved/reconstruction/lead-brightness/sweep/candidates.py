import csv,itertools,json,sys
from pathlib import Path
dest,study=map(Path,sys.argv[1:])
base=json.loads(study.read_text())
voice=next(v for v in base['voices'] if v['id']=='focused-lead')
rows=[['previous',121,*voice['bytes']]]
for fb,(h2,h3,h4),attack,shape in itertools.product(
        (0,2,4,5,6), ((0,7,15),(4,9,16),(7,14,21),(0,4,10)), (24,30), (0,1)):
    # All carriers. The per-operator TL differences set harmonic proportions.
    # Feedback on the fundamental adds upper harmonics beyond the four carriers.
    rates=([4,6,8,10] if shape else [3,3,4,5])
    levels=([17,34,51,68] if shape else [17,17,17,33])
    raw=[7+8*fb,1,2,3,4,attack,attack,30,31,*rates,0,0,0,0,*levels,132,132+h2,132+h3,132+h4]
    rows.append([f'add-{fb}-{h2}-{h3}-{attack}-{shape}',121,*raw])
with dest.open('w',newline='') as f:
    csv.writer(f).writerows(rows)
