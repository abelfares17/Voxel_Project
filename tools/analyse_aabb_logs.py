#!/usr/bin/env python3
"""Read explicitly selected CSVs; never mix validation/profile runs into FPS conclusions.

Window: 3 < elapsed <= 19 seconds. CPU elapsed is sum(dt), GPU elapsed is elapsed_s.
Usage: python3 tools/analyse_aabb_logs.py logs/perf_test_...csv [logs/...csv ...]
"""
import csv
import json
import statistics
import sys


def summarize(path):
    with open(path, newline="") as stream:
        rows = list(csv.DictReader(stream))
    if not rows:
        raise ValueError(f"Empty CSV: {path}")
    gpu = "elapsed_s" in rows[0]
    elapsed = 0.0
    selected = []
    for row in rows:
        elapsed = float(row["elapsed_s"]) if gpu else elapsed + float(row["dt"])
        if 3 < elapsed <= 19:
            selected.append(row)
    if not selected:
        raise ValueError(f"No samples in window: {path}")

    def values(key):
        return [float(row[key]) for row in selected]

    def percentile(data, fraction):
        ordered = sorted(data)
        return ordered[round((len(ordered) - 1) * fraction)]

    result = {"file": path, "kind": "GPU-profile" if gpu else "CPU", "samples": len(selected)}
    keys = (["tight_shadow_bounds", "tight_camera_bounds", "fb_width", "fb_height"] if gpu else
            ["tightShadowBounds", "tightCameraBounds", "fbWidth", "fbHeight", "perfBisectStage"])
    for key in keys:
        result[key] = sorted({row.get(key, "not logged") for row in selected})
    for key in ("indexedMeshes", "indexed_meshes", "focused", "visible", "iconified", "cursorMode"):
        if key in selected[0]:
            result[key] = sorted({row[key] for row in selected})
    for key in ("storedVertices", "meshBytes"):
        if key in selected[0]:
            result[key + "_mean"] = statistics.mean(values(key))
    time_key = "time_of_day" if gpu else "timeOfDay"
    result["sun_time_range"] = [min(values(time_key)), max(values(time_key))]
    if gpu:
        for key in ("shadow_draws", "shadow_vertices", "voxel_draws", "voxel_vertices",
                    "gpu_shadow_ms", "gpu_sky_sun_ms", "gpu_voxel_ms", "gpu_ui_ms"):
            result[key + "_mean"] = statistics.mean(values(key))
    else:
        duration = sum(values("dt"))
        dt_ms = [value * 1000 for value in values("dt")]
        result.update(duration_s=duration, fps=len(selected) / duration,
                      mean_ms=statistics.mean(dt_ms), p50_ms=percentile(dt_ms, .5),
                      p95_ms=percentile(dt_ms, .95), render_median_ms=statistics.median(values("render_ms")),
                      swap_median_ms=statistics.median(values("swap_ms")),
                      voxel_vertices_mean=statistics.mean(values("renderedVertices")))
    return result


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    print(json.dumps([summarize(path) for path in sys.argv[1:]], indent=2))
