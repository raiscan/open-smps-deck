"""Build the song-specific native articulation audition."""
import json,sys
from pathlib import Path

source,dest=map(Path,sys.argv[1:])
project=json.loads(source.read_text())
project['name']='All That Time Saved - native articulation study'
main=next(s for s in project['sequences'] if s['name']=='Topline')
layer=next(s for s in project['sequences'] if s['name']=='Lead reinforcement')
# These outgoing pitches still have strong energy after the MIDI boundary.
tail_attacks={275,485,920}
for seq in (main,layer):
    notes=seq['notes']
    tails=[]
    if seq is layer:
        for left,right in zip(notes,notes[1:]):
            tick=left['tick']+left['duration']
            if left['tick'] in tail_attacks and tick==right['tick'] and right['duration']>=5:
                left['duration']+=2
                right['tick']+=2
                right['duration']-=2
                tails.append((tick,right['tick']))
    commands=[]
    enabled=False
    for note in notes:
        long=note['duration']>=20
        if long:
            commands.append(dict(id=f"mod-on-{note['tick']}",tick=note['tick'],command='MODULATION',
                                 arguments=[10,1,2,6]))
        elif enabled:
            commands.append(dict(id=f"mod-off-{note['tick']}",tick=note['tick'],command='MODULATION_OFF',
                                 arguments=[]))
        enabled=long
    for out,back in tails:
        commands.extend([
            dict(id=f'tail-down-{out}',tick=out,command='VOLUME',arguments=[seq['volume']-12]),
            dict(id=f'tail-up-{back}',tick=back,command='VOLUME',arguments=[seq['volume']])])
    seq['commands']=sorted(commands,key=lambda c:c['tick'])
    print(seq['name'],len([x for x in commands if x['command']=='MODULATION']),tails)
dest.write_text(json.dumps(project,indent=2)+'\n')
