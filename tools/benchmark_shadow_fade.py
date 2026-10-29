#!/usr/bin/env python3
"""Sequential shader ABBA, policy ABBA and separate GPU shader ABBA. Build installDist first.
Real moving-camera/moving-sun harness, no saves or image readback. --suite range compares old/new distance budgets.
"""
import argparse
import csv
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import sys
import tempfile
sys.dont_write_bytecode = True
from analyse_aabb_logs import summarize


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', choices=['shader', 'global', 'gpu', 'range', 'all'], default='all')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    os.chdir(root)
    output = Path(tempfile.mkdtemp(prefix='fade_suite_', dir='logs'))
    print(f'OUTPUT {output}', flush=True)
    results = []
    suites = ['shader', 'global', 'gpu'] if args.suite == 'all' else [args.suite]
    for suite in suites:
        for name, on in [('A1', False), ('B1', True), ('B2', True), ('A2', False)]:
            flags = dict(testHarness=True, testStatic=False, greedyShadows=True, gpuTiming=suite == 'gpu',
                         compareGreedyImages=False, shadowCasterResidency=True,
                         shadowFade=on, legacyCasterResidency=suite == 'global' and not on,
                         validateShadowCoverage=True)
            if suite == 'range':
                flags.update(shadowFade=True, shadowFadeStart=160 if on else 80,
                             shadowFadeEnd=256 if on else 128, shadowCasterReach=320 if on else 256,
                             shadowCasterBudget=1024 if on else 640)
            env = dict(os.environ)
            for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
                if env.get(key): raise SystemExit(f'Unset {key}')
            env['JAVA_OPTS'] = ' '.join(f'-Dvoxel.{k}={str(v).lower()}' for k,v in flags.items())
            console = output / f'{suite}_{name}.txt'
            print(f'RUN {suite}_{name}: {env["JAVA_OPTS"]}', flush=True)
            with console.open('w') as stream:
                run = subprocess.run([str(root/'build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project')], env=env,
                                     stdout=stream, stderr=subprocess.STDOUT, timeout=240)
            text = console.read_text()
            if run.returncode or 'TEST HARNESS COMPLETED' not in text or 'Application cleanup complete.' not in text:
                raise SystemExit(f'Incomplete: {console}')
            if 'Exception' in text or 'GL ERROR' in text: raise SystemExit(f'Error: {console}')
            path = re.search(r'Performance logging started: (.+)', text).group(1).strip()
            cpu = summarize(path)
            with open(path) as stream: rows=list(csv.DictReader(stream))
            elapsed=0; selected=[]
            for row in rows:
                elapsed+=float(row['dt'])
                if 3 < elapsed <= 19: selected.append(row)
            expected={'shadowFadePreviousFrame':str(flags['shadowFade']).lower(),'legacyCasterResidency':str(flags['legacyCasterResidency']).lower(),
                      'visible':'1','iconified':'0','cursorMode':'212993','samplingGuardValidPreviousFrame':'true'}
            for key,value in expected.items():
                if {r[key] for r in selected}!={value}: raise SystemExit(f'Unexpected {key}: {path}')
            if suite == 'range':
                for key,flag in [('fadeStart','shadowFadeStart'),('fadeEnd','shadowFadeEnd')]:
                    if {float(r[key]) for r in selected}!={float(flags[flag])}: raise SystemExit(f'Unexpected {key}: {path}')
            for key in ('residentChunks','casterRequested','requiredReadyPreviousFrame','requiredPreviousFrame','prefetchReadyPreviousFrame','prefetchPreviousFrame'):
                cpu[key+'_mean']=statistics.mean(float(r[key]) for r in selected)
            cpu['coverage_complete_fraction']=sum(r['coverageCompletePreviousFrame']=='true' for r in selected)/len(selected)
            entry={'suite':suite,'run':name,'flags':flags,'console':str(console),'cpu':cpu}
            if suite=='gpu':
                gpu=re.search(r'GPU pass timing: ([^;]+);',text).group(1)
                entry['gpu']=summarize(gpu)
            results.append(entry)
            (output/'results.json').write_text(json.dumps(results,indent=2))
            print(f'DONE {suite}_{name}: {cpu["fps"]:.2f} FPS'+(' (profile, not FPS reference)' if suite=='gpu' else ''),flush=True)
        group=[r for r in results if r['suite']==suite]
        for key in ('fbWidth','fbHeight','focused','indexedMeshes','compactVertices','perfBisectStage'):
            if any(r['cpu'][key]!=group[0]['cpu'][key] for r in group): raise SystemExit(f'Non-comparable {key}')
    aggregates={}
    for suite in suites:
        means={}
        labels = ('OLD', 'EXTENDED') if suite == 'range' else ('OFF', 'ON')
        for label,names in [(labels[0],('A1','A2')),(labels[1],('B1','B2'))]:
            group=[r for r in results if r['suite']==suite and r['run'] in names]
            means[label]=(statistics.mean(r['gpu']['gpu_voxel_ms_mean'] for r in group) if suite=='gpu'
                          else sum(r['cpu']['samples'] for r in group)/sum(r['cpu']['duration_s'] for r in group))
        means['delta']=means[labels[1]]-means[labels[0]]
        means['percent_change']=(means[labels[1]]/means[labels[0]]-1)*100
        aggregates[suite]=means
    (output/'aggregate.json').write_text(json.dumps(aggregates,indent=2))
    print(json.dumps(aggregates),flush=True)


if __name__=='__main__': main()
