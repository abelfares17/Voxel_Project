#!/usr/bin/env python3
"""ABBA cost of caster residency, NOT a visual qualification of the mountain.
Same existing moving-camera/moving-sun harness; saves disabled, no image readbacks.
Build installDist first. Outputs a uniquely named logs/caster_suite_* directory.
"""
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
    root = Path(__file__).resolve().parents[1]
    os.chdir(root)
    output = Path(tempfile.mkdtemp(prefix='caster_suite_', dir='logs'))
    print(f'OUTPUT {output}', flush=True)
    results = []
    for name, enabled in [('A1', False), ('B1', True), ('B2', True), ('A2', False)]:
        env = dict(os.environ)
        for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
            if env.get(key):
                raise SystemExit(f'Unset {key} before measurement')
        flags = {'testHarness': True, 'testStatic': False, 'greedyShadows': True,
                 'gpuTiming': False, 'compareGreedyImages': False, 'shadowCasterResidency': enabled}
        env['JAVA_OPTS'] = ' '.join(f'-Dvoxel.{k}={str(v).lower()}' for k, v in flags.items())
        console = output / f'{name}.txt'
        print(f'RUN {name}: {env["JAVA_OPTS"]}', flush=True)
        with console.open('w') as stream:
            run = subprocess.run([str(root / 'build/install/TOB_Voxel_Project/bin/TOB_Voxel_Project')],
                                 env=env, stdout=stream, stderr=subprocess.STDOUT, timeout=240)
        text = console.read_text()
        if run.returncode or 'TEST HARNESS COMPLETED' not in text or 'Application cleanup complete.' not in text:
            raise SystemExit(f'Incomplete run: {console}')
        if 'Exception' in text or 'GL ERROR' in text:
            raise SystemExit(f'Error: {console}')
        path = re.search(r'Performance logging started: (.+)', text).group(1).strip()
        metric = summarize(path)
        with open(path) as stream:
            rows = list(csv.DictReader(stream))
        elapsed = 0
        selected = []
        for row in rows:
            elapsed += float(row['dt'])
            if 3 < elapsed <= 19:
                selected.append(row)
        for key, expected in [('shadowCasterResidency', str(enabled).lower()), ('greedyShadows', 'true'),
                              ('visible', '1'), ('iconified', '0'), ('cursorMode', '212993')]:
            if {r[key] for r in selected} != {expected}:
                raise SystemExit(f'Unexpected {key}: {path}')
        for key in ('residentChunks', 'casterRequested', 'casterReady', 'casterCandidates', 'casterReach'):
            metric[key + '_mean'] = statistics.mean(float(r[key]) for r in selected)
        metric['casterPending_max'] = max(int(r['casterRequested'])-int(r['casterReady']) for r in selected)
        results.append({'run': name, 'flags': flags, 'console': str(console), 'performance': metric})
        (output/'results.json').write_text(json.dumps(results, indent=2))
        print(f'DONE {name}: {metric["fps"]:.2f} FPS', flush=True)
    for key in ('fbWidth', 'fbHeight', 'focused', 'indexedMeshes', 'compactVertices', 'perfBisectStage'):
        if any(r['performance'][key] != results[0]['performance'][key] for r in results):
            raise SystemExit(f'Non-comparable {key}; inspect individual measurements')
    means = {}
    for label, names in [('OFF', ('A1', 'A2')), ('ON', ('B1', 'B2'))]:
        runs = [r['performance'] for r in results if r['run'] in names]
        means[label] = sum(r['samples'] for r in runs)/sum(r['duration_s'] for r in runs)
    means['percent_change'] = (means['ON']/means['OFF']-1)*100
    (output/'aggregate.json').write_text(json.dumps(means, indent=2))
    print(json.dumps(means), flush=True)


if __name__ == '__main__':
    main()
