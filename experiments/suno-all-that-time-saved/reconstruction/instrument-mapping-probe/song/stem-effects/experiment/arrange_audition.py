"""Apply prototype note tracks to the existing S3K roles for a listening render."""
import argparse
import json
from pathlib import Path

cli=argparse.ArgumentParser(description=__doc__)
for name in ('source','notes','output'):
    cli.add_argument(name,type=Path)
args=cli.parse_args()
project=json.loads(args.source.read_text())
roles=json.loads(args.notes.read_text())
project['name']='All That Time Saved - score-guided note fit audition'
lanes={'fm1':'lead','lead-reinforcement':'lead','fm3':'harmony-1','fm4':'harmony-2'}
for sequence in project['sequences']:
    if sequence['id'] not in lanes:
        continue
    notes=[]
    for i,note in enumerate(roles[lanes[sequence['id']]]):
        start=round(note['start']*60)
        end=min(sequence['lengthTicks'],round(note['end']*60))
        if end>start:
            notes.append(dict(id=f'fit-{i}',tick=start,duration=end-start,pitch=note['pitch']))
    sequence['notes']=notes
    sequence['commands']=[]
args.output.write_text(json.dumps(project,indent=2)+'\n')
