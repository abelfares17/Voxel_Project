#!/usr/bin/env python3
"""Real GL qualification, sequential, no builds during measurements.
Static ABBA isolates sky cost; moving/accelerated runs qualify the live cycle separately.
Run installDist first. All artifacts in logs/celestial_suite_*. Never saves a world.
"""
import argparse
import csv
import json
import math
import os
from pathlib import Path
import re
import statistics
import subprocess
import tempfile


def save_fingerprint(root):
    # Detect any writes/additions/removals, including chunk saves, without hashing gigabytes.
    return {str(p): (p.stat().st_size, p.stat().st_mtime_ns)
            for p in (root / 'saves').rglob('*') if p.is_file()}


def summarize(path):
    with open(path) as f:
        rows = [r for r in csv.DictReader(f) if 5 <= float(r['elapsed_s']) <= 19.5]
    if not rows:
        raise RuntimeError('No steady-state GPU samples')
    metrics = {}
    for key in ('gpu_shadow_ms', 'gpu_sky_sun_ms', 'gpu_voxel_ms', 'gpu_ui_ms', 'gpu_scene_ms'):
        if key not in rows[0]: continue
        values = [float(r[key]) for r in rows]
        if not all(math.isfinite(v) for v in values): continue
        metrics[key] = {'mean': statistics.mean(values), 'median': statistics.median(values),
                        'p95': sorted(values)[int(.95*(len(values)-1))],
                        'zero_fraction': sum(v==0 for v in values)/len(values)}
    return {'samples': len(rows), 'metrics': metrics,
            'invariants': {k: sorted({r[k] for r in rows}) for k in
                ('fb_width','fb_height','time_of_day','active_light','paused','sky_mode',
                 'shadow_draws','shadow_vertices','voxel_draws','voxel_vertices','pos_x','pos_y','pos_z')}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=['all','smoke','bench'], default='all')
    parser.add_argument('--combined-scene', action='store_true',
                        help='Time sky + voxels together when isolated sky queries return zero')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    os.chdir(root)
    executable = root / 'build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project'
    if not executable.exists():
        raise SystemExit('Run ./gradlew --offline installDist first')
    for key in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'):
        if os.environ.get(key):
            raise SystemExit('Unset ' + key)
    Path('logs').mkdir(exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='celestial_suite_', dir='logs'))
    print('OUTPUT', output, flush=True)
    fingerprint = save_fingerprint(root)
    jobs = []
    if args.mode != 'bench':
        jobs += [('cycle', {'startHour':12,'timeScale':120,'testStatic':False,'gpuTiming':True}),
                 ('night_images', {'startHour':0,'timeScale':1,'testStatic':False,
                    'compareGreedyImages':True,'greedyImageControl':True,
                    'imageComparisonDir':str(output/'night_images')}),
                 ('night_greedy', {'startHour':0,'timeScale':1,'testStatic':False,
                    'compareGreedyImages':True,'greedyImageControl':False,
                    'imageComparisonDir':str(output/'night_greedy')})]
    if args.mode != 'smoke':
        for phase, hour in [('noon',12),('moon',0),('twilight',6)]:
            for repetition, mode in [('A1','flat'),('B1','procedural'),('B2','procedural'),('A2','flat')]:
                jobs.append((phase+'_'+repetition, {'startHour':hour,'timeScale':1,'pauseTime':True,
                        'testStatic':True,'gpuTiming':True,'skyMode':mode}))
    results = []
    for name, extra in jobs:
        flags = {'testHarness':True, 'skyMode':'procedural', 'gpuTimingScene':args.combined_scene, **extra}
        opts = ' '.join('-Dvoxel.'+k+'='+str(v).lower() if isinstance(v,bool)
                        else '-Dvoxel.'+k+'='+str(v) for k,v in flags.items())
        env = dict(os.environ, JAVA_OPTS=opts)
        console = output/(name+'.txt')
        print('RUN', name, opts, flush=True)
        with console.open('w') as log:
            process = subprocess.run([str(executable)], env=env, stdout=log,
                                     stderr=subprocess.STDOUT, timeout=240)
        text = console.read_text()
        if process.returncode or 'TEST HARNESS COMPLETED' not in text or 'Application cleanup complete.' not in text:
            raise RuntimeError('Incomplete run: '+str(console))
        if 'Exception' in text or 'GL ERROR' in text:
            raise RuntimeError('GL/run error: '+str(console))
        if save_fingerprint(root) != fingerprint:
            raise RuntimeError('A save file changed during tests')
        cpu = re.search(r'Performance logging started: (.+)', text).group(1).strip()
        item = {'run':name,'flags':flags,'console':str(console),'cpu_csv':cpu}
        with open(cpu) as f:
            cpu_rows=list(csv.DictReader(f))
        for key, expected in [('visible',{'1'}),('iconified',{'0'}),('cursorMode',{'212993'})]:
            if {r[key] for r in cpu_rows} != expected:
                raise RuntimeError('Unexpected window state '+key)
        if flags.get('gpuTiming'):
            gpu = re.search(r'GPU pass timing: ([^;]+);', text)
            if not gpu: raise RuntimeError('GPU queries not available: '+str(console))
            item['gpu_csv']=gpu.group(1); item['summary']=summarize(gpu.group(1))
            if name=='cycle':
                with open(gpu.group(1)) as f: rows=list(csv.DictReader(f))
                lights={r['active_light'] for r in rows}
                if lights != {'SUN','MOON','NONE'}: raise RuntimeError('Cycle did not visit all lights')
                if any(int(r['shadow_draws']) for r in rows if r['active_light']=='NONE'):
                    raise RuntimeError('Shadow production during NONE')
                item['visited_lights']=sorted(lights)
        if flags.get('compareGreedyImages'):
            item['comparisons']=[l for l in text.splitlines() if l.startswith('GREEDY_IMAGE')]
            if len(item['comparisons'])<3: raise RuntimeError('Not enough image comparisons')
        results.append(item)
        (output/'results.json').write_text(json.dumps(results,indent=2))
        print('DONE',name,flush=True)
    comparison={}
    for phase in ('noon','moon','twilight'):
        runs=[r for r in results if r['run'].startswith(phase+'_')]
        if not runs: continue
        for k in ('fb_width','fb_height','time_of_day','active_light','paused','pos_x','pos_y','pos_z'):
            if any(r['summary']['invariants'][k]!=runs[0]['summary']['invariants'][k] for r in runs):
                raise RuntimeError('ABBA mismatch: '+phase+' '+k)
        metric='gpu_scene_ms' if args.combined_scene else 'gpu_sky_sun_ms'
        means={mode:statistics.mean(r['summary']['metrics'][metric]['mean']
                for r in runs if r['flags']['skyMode']==mode) for mode in ('flat','procedural')}
        means['extra_gpu_ms']=means['procedural']-means['flat']
        usable=all(r['summary']['metrics'][metric]['zero_fraction']<.01
                   for r in runs if r['flags']['skyMode']=='procedural')
        means['metric']=metric
        means['timing_usable']=usable
        means['under_1ms']=means['extra_gpu_ms']<1 if usable else None
        comparison[phase]=means
    (output/'aggregate.json').write_text(json.dumps(comparison,indent=2))
    print(json.dumps(comparison,indent=2),flush=True)
    print('RESULTS',output/'results.json',flush=True)


if __name__=='__main__':
    main()
