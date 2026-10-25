#!/usr/bin/env python3
"""Sequential real-GL qualification. Build installDist first; never build during a run.
Usage: python3 tools/benchmark_greedy_shadows.py [--mode all|images|bench]
Outputs live under a unique logs/greedy_suite_* directory. No world edits/saves.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from analyse_aabb_logs import summarize

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=['all', 'images', 'bench'], default='all')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    os.chdir(root)
    executable = root / 'build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project'
    if not executable.is_file():
        raise SystemExit('Run ./gradlew --offline installDist first')
    Path('logs').mkdir(exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='greedy_suite_', dir='logs'))
    print(f'OUTPUT {output}', flush=True)
    jobs = []
    if args.mode != 'bench':
        jobs.append(('control', True, False, True))
        jobs.append(('images', True, False, True))
    if args.mode != 'images':
        jobs += [(name, on, False, False) for name, on in [('A1',False),('B1',True),('B2',True),('A2',False)]]
        jobs += [('gpu_off',False,True,False), ('gpu_on',True,True,False)]
    results = []
    for name, on, profile, images in jobs:
        flags = {'testHarness':True, 'testStatic':False, 'greedyShadows':on,
                 'gpuTiming':profile, 'compareGreedyImages':images}
        opts = ' '.join(f'-Dvoxel.{k}={str(v).lower()}' for k,v in flags.items())
        if images:
            opts += f' -Dvoxel.imageComparisonDir={output}/{name}'
            opts += f' -Dvoxel.greedyImageControl={str(name == "control").lower()}'
        env = dict(os.environ)
        # Prevent inherited flags from silently mixing profiling/validation into benchmarks.
        for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
            if env.get(key): raise SystemExit(f'Unset {key} before this controlled suite')
        env['JAVA_OPTS'] = opts
        print(f'RUN {name}: {opts}', flush=True)
        console = output / f'{name}.txt'
        with console.open('w') as log:
            try:
                process = subprocess.run([str(executable)], env=env, stdout=log,
                                         stderr=subprocess.STDOUT, timeout=240)
            except subprocess.TimeoutExpired:
                raise SystemExit(f'Timeout; inspect {console}')
        text = console.read_text()
        if process.returncode or 'TEST HARNESS COMPLETED' not in text or 'Application cleanup complete.' not in text:
            raise SystemExit(f'Incomplete run; inspect {console}')
        if 'Exception' in text or 'GL ERROR' in text:
            raise SystemExit(f'Run error; inspect {console}')
        cpu = re.search(r'Performance logging started: (.+)', text).group(1).strip()
        item = {'run':name, 'flags':flags, 'console':str(console), 'cpu_csv':cpu}
        if images:
            item['comparisons'] = [line for line in text.splitlines() if line.startswith('GREEDY_IMAGE ')]
            if len(item['comparisons']) < 3: raise SystemExit(f'Too few image comparisons: {console}')
        elif profile:
            gpu = re.search(r'GPU pass timing: ([^;]+);', text).group(1)
            item['profile'] = summarize(gpu)
        else:
            item['performance'] = summarize(cpu)
            metric = item['performance']
            if metric.get('greedyShadows') != [str(on).lower()]:
                raise SystemExit(f'Wrong shadow flag in {cpu}')
            for key, expected in [('visible',['1']), ('iconified',['0']), ('cursorMode',['212993'])]:
                if metric.get(key) != expected: raise SystemExit(f'Unexpected {key} in {cpu}')
        results.append(item)
        (output / 'results.json').write_text(json.dumps(results, indent=2))
        print(f'DONE {name}', flush=True)
    if args.mode != 'images':
        measurements = [r['performance'] for r in results if 'performance' in r]
        for key in ('fbWidth','fbHeight','focused','indexedMeshes','compactVertices','perfBisectStage'):
            if any(m.get(key) != measurements[0].get(key) for m in measurements):
                raise SystemExit(f'Non-comparable runs: {key}')
        means = {}
        for label, names in [('OFF',('A1','A2')), ('ON',('B1','B2'))]:
            runs = [r['performance'] for r in results if r['run'] in names]
            means[label] = sum(r['samples'] for r in runs)/sum(r['duration_s'] for r in runs)
        means['percent_change'] = (means['ON']/means['OFF']-1)*100
        (output/'aggregate.json').write_text(json.dumps(means, indent=2))
        print(json.dumps(means), flush=True)
    print(f'RESULTS {output}/results.json', flush=True)

if __name__ == '__main__': main()
