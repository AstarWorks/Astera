#!/usr/bin/env python3
"""Manager-run integration check: real shipped model → material → history → export.

Not run by the implementation worker. Uses no fakes, network or test framework.
"""
import copy
import os
from pathlib import Path
import sys
import tempfile
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from core import Project, ROOT


def main():
    # Non-render operations must work on a machine with no installed client jar.
    os.environ['MODEL_FORGE_CLIENT_JAR'] = '/missing/model-forge-client.jar'
    with tempfile.TemporaryDirectory() as directory:
        p = Project(Path(directory)/'project')
        p.call('import', {'path':str(ROOT/'content/models/items/verdict-hammer.model.json')})
        original = copy.deepcopy(p.state)
        p.call('history.snapshot', {'name':'before'})
        id = p.model['elements'][0]['id']
        # Exercise the extended catalog through persistent apply/undo, without client pixels.
        for material in ('wood.stripped_crimson_stem', 'wood.stripped_warped_stem',
                         'gem.redstone_block', 'stone.quartz_pillar', 'concrete.red_concrete',
                         'leather.mushroom_stem'):
            p.call('material.apply', {'ids':[id], 'material':material})
            assert p.call('validate')['valid']
            p.call('history.undo')
            assert p.state == original
        args = {'ops':[
            {'op':'element.update','args':{'id':id,'changes':{'to':[8.75,19.5,8.75]}}},
            {'op':'material.apply','args':{'ids':[id],'material':'wood.stripped_oak_log','seed':7}}]}
        p.call('batch', args)
        applied = copy.deepcopy(p.state)
        assert p.call('validate')['valid']
        assert any(v.startswith('minecraft:block/') for v in p.call('scene.get')['textures'].values())
        assert len(p.model['elements']) == len(original['model']['elements'])+1
        assert set(p.state['images']) == set(original['images'])
        changed = [row for row in p.call('measure')['density'] if row['id']==id]
        assert all(abs(d-1)<1e-8 for row in changed for d in row['texels_per_unit'])
        p.call('history.undo')
        assert p.state == original
        p.call('batch', args)
        assert p.state == applied
        try:
            p.call('batch', {'ops':[
                {'op':'material.apply','args':{'ids':[id],'material':'metal.gold_block'}},
                {'op':'material.apply','args':{'ids':['missing'],'material':'metal.iron_block'}}]})
            raise AssertionError('Expected atomic rollback')
        except ValueError: pass
        assert p.state == applied
        export = Path(directory)/'export'
        p.call('export', {'path':str(export)})
        assert {f.stem for f in (export/'textures').glob('*.png')} == set(p.state['images'])
        imported = Project(Path(directory)/'reimport')
        imported.call('import', {'path':str(export/'model.json')})
        assert imported.call('validate')['valid']
        assert imported.model['textures'][next(k for k,v in p.model['textures'].items() if v=='minecraft:block/stripped_oak_log')] == 'minecraft:block/stripped_oak_log'
        try:
            imported.call('board', {'views':['front'],'resolution':32})
            raise AssertionError('Expected missing jar message')
        except ValueError as ex: assert 'MODEL_FORGE_CLIENT_JAR' in str(ex)
        p.call('history.restore', {'name':'before'})
        assert p.state == original
    print('material integration passed')

if __name__=='__main__': main()
