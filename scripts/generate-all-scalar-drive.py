#!/usr/bin/env python3
"""Build fictional, decoder-quantized test data; never a vehicle capability claim.
No runtime Python dependency. Default inputs/outputs live in test resources.
"""
import argparse
import hashlib
import json
import math
import pathlib
import re
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
RES = ROOT / 'kotlin/androidApp/src/test/resources'
NAME = 'SYNTHETIC-Legacy-inspired-55-channel-5min-drive.json'
MISSING = {211, 212}
HISTORY = {0x21, 0x30, 0x31, 0x4D, 0x4E, 0xA6}
NOT_APPLICABLE = {0x5B, 0xA5}
PERCENT = {0x04, 0x11, 0x2E, 0x2F, 0x45, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x52, 0x5A, 0x5B, 0x8D}
TEMP = {0x05, 0x0F, 0x46, 0x5C}
TRIM = {0x06, 0x07, 0x08, 0x09}
TORQUE = {0x61, 0x62, 0x8E}
INTEGER = {0x1F, 0x21, 0x30, 0x31, 0x4D, 0x4E, 0x63}


def catalog():
    source = (ROOT / 'kotlin/shared/src/commonMain/kotlin/org/openwrench/nativecore/ObdDecoder.kt').read_text()
    structured = {int(s, 16) for s in re.search(r'private val structuredPids = setOf\((.*?)\)', source).group(1).split(', ')}
    rows = {int(pid, 16): {'name': name, 'unit': unit[1:-1] if unit != 'null' else None}
            for pid, name, unit in re.findall(r'PidMetadata\(0x([0-9A-F]+), "([^"]+)", (null|"[^"]*")', source)}
    return rows, structured


def quantize(pid, value):
    """Choose a representable raw code and decode using the existing app formulas."""
    if value is None:
        return None
    def q(step, offset=0, maximum=65535):
        raw = max(0, min(maximum, math.floor((value - offset) / step + .5)))
        return raw * step + offset
    if pid in PERCENT: return q(100 / 255, maximum=255)
    if pid in TEMP: return q(1, -40, 255)
    if pid in TRIM: return q(100 / 128, -100, 255)
    if pid in TORQUE: return q(1, -125, 255)
    if pid == 0x0A: return q(3, maximum=255)
    if pid in {0x0B, 0x0D, 0x33}: return q(1, maximum=255)
    if pid == 0x0C: return q(.25)
    if pid == 0x0E: return q(.5, -64, 255)
    if pid == 0x10: return q(.01)
    if pid in INTEGER: return q(1, maximum=255 if pid == 0x30 else 65535)
    if pid == 0x22: return q(.079)
    if pid in {0x23, 0x59}: return q(10)
    if pid == 0x32: return max(-32768, min(32767, round(value * 4))) / 4
    if pid == 0x3C: return q(.1, -40)
    if pid in {0x42, 0xA4}: return q(.001)
    if pid == 0x43: return q(100 / 255)
    if pid == 0x44: return q(2 / 65536)
    if pid == 0x50: return q(10, maximum=255)
    if pid == 0x53: return q(.005)
    if pid == 0x5D: return q(1 / 128, -210)
    if pid == 0x5E: return q(.05)
    if pid == 0xA5: return q(.5, maximum=255)
    if pid == 0xA6: return q(.1, maximum=0xffffffff)
    raise ValueError(f'No quantizer for {pid:02X}')


def build(seed):
    cat, structured = catalog()
    scalars = sorted(cat.keys() - structured)
    assert len(scalars) == 55, 'Review scenario coverage if the actual decoder changes'
    base = {s[0]: s[2] for s in seed['review']['series']}
    assert seed['origin'] == 'SYNTHETIC' and seed['review']['elapsed_ms'] == 300000
    assert all(len(points) == 301 for points in base.values())
    points_by_pid = {pid: [] for pid in scalars}
    fuel_litres = 0.
    for t in range(301):
        known = {pid: points[t][1] for pid, points in base.items()}
        if t not in MISSING:
            rpm, speed, load, maf = (known[p] for p in (0x0C, 0x0D, 0x04, 0x10))
            coast = (100 <= t < 120 or 235 <= t < 275) and speed > 0
            idle = speed == 0
            throttle = 12 + max(0, load - 20) * .48 if not coast else 12
            pedal = 0 if idle or coast else 10 + max(0, load - 25) * .65
            map_kpa = 28 if idle else 20 if coast else 30 + load * .65
            # Generic closed-loop-like fuel estimate. No fuel-cut state is invented.
            rate = maf * 3600 / (14.7 * 745)
            fuel_litres += rate / 3600 if t else 0
            # A single fictional pressure system, quantized at each decoder boundary.
            rail_absolute = quantize(0x59, 300 + 101)
            rail_manifold_relative = rail_absolute - quantize(0x0B, map_kpa)
            ratio = 3.54 if speed < 16 else 2.264 if speed < 31 else 1.471 if speed < 47 else 1.0 if speed < 67 else .834
            values = {
                **known, 0x0A: 300, 0x0B: map_kpa, 0x0E: 12 if idle else 20 if coast else 32 - max(0, load - 35) * .4,
                0x0F: 30 + (4 if idle else 1) + t / 300, 0x11: throttle, 0x1F: 900 + t,
                0x22: rail_manifold_relative, 0x23: 300, 0x2E: 0 if idle or coast else 12 + load * .3,
                0x2F: 65 - fuel_litres / 70 * 100, 0x32: -100 - load, 0x33: 101,
                0x3C: 490 + min(t, 180) * .5 + load * .8,
                0x42: 13.95 + .08 * min(rpm / 1500, 1), 0x43: load,
                0x44: 1.0, 0x45: max(0, throttle - 12), 0x46: 23,
                0x47: throttle, 0x48: throttle, 0x49: pedal, 0x4A: pedal, 0x4B: pedal,
                0x4C: throttle, 0x50: 180, 0x52: 10, 0x53: 101 + (-100 - load) / 1000,
                0x59: rail_absolute, 0x5A: pedal, 0x5C: 93 + t / 100,
                0x5D: 5 + load * .15, 0x5E: rate, 0x61: load - 10,
                0x62: load - 12, 0x63: 335, 0x8D: throttle, 0x8E: 8 + rpm / 1000,
                0xA4: ratio,
            }
        for pid in scalars:
            unavailable = pid in HISTORY | NOT_APPLICABLE or t in MISSING
            value = None if unavailable else quantize(pid, values[pid])
            points_by_pid[pid].append([t * 1000, value, t in {0, 213}])
    report = [
        'OPENWRENCH live health monitoring',
        'SYNTHETIC / FICTIONAL. Decoder-coverage demo inspired by a warm 2013 Subaru Legacy 3.6R drive; NOT a vehicle capture or published reference range.',
        '55 scalar channels are supported by this app decoder, NOT a verified ECU capability list. 47 contain fictional numeric receipts; 8 remain explicitly unavailable.',
        'No VIN, ECU identity, actual Subaru PID availability, DTC history, health clearance or manufacturer-specific diagnostics is claimed.',
        'Canadian model context: 3.6L horizontally opposed six / five-speed 5EAT. Shift ratios, sensor availability and calibration below are illustrative, not model-verified.',
        'Five-minute warm-engine/level-road/mild-ambient scenario: idle, gentle 50 km/h drive, stop, gentle 80 km/h drive, stop, idle.',
        'All signals use a synthetic common 1 Hz clock. UTC metadata is fictional. Null ticks at 211 and 212 seconds and a recovery break at 213 seconds are intentional test gaps.',
        'Gasoline airflow and fuel-rate estimates use an illustrative stoichiometric ratio and density, with trim hold on lift-off. No measured fuel-cut/fuel-system state is asserted.',
        'Gasoline pressure variants, redundant throttle/pedal channels, timing, torque and gearing are generic decoder demonstrations, not claims that this Subaru reports them.',
        'Pressure variants share one fictional 300 kPa gauge baseline: absolute = gauge + barometric (10 kPa quantization); manifold-relative = quantized absolute minus MAP.',
        'History/counters 21,30,31,4D,4E,A6 are unknown, not zero. Hybrid battery 5B and diesel dosing A5 are not applicable and stay null.',
        'Structured PIDs omitted (not coerced to scalar): ' + ','.join(f'{p:02X}' for p in sorted(structured)) + '.',
        'Source for model facts only: https://www.subaru.ca/content/7907/media/en-ca/download/2013LEG_PG_EN%20FINAL%20v2.pdf (Canadian 2013 Legacy guide).',
    ]
    summaries = []
    coverage = []
    for pid, points in points_by_pid.items():
        valid = [p for p in points if p[1] is not None]
        if valid:
            vals = [p[1] for p in valid]
            summaries.append([pid, len(vals), vals[0], vals[-1], min(vals), max(vals), valid[0][0], valid[-1][0]])
        state = 'unavailable: scenario not applicable' if pid in NOT_APPLICABLE else 'unavailable: history unverified' if pid in HISTORY else 'fictional numeric receipts; ECU capability unverified'
        coverage.append({'pid': f'01{pid:02X}', **cat[pid], 'coverage': state, 'numeric_receipts': len(valid), 'null_receipts': len(points)-len(valid)})
    highlights = [
        'SYNTHETIC / NOT LIVE. Every app-decodable scalar channel is represented; real Subaru capability support is unverified.',
        '55 channels: 47 fictional numeric series; 8 explicit unavailable channels. Not all channels change continuously (constants remain constants).',
        'History unknown stays null; hybrid battery and diesel dosing are not applicable. No health assessment or clearance.',
        'Requires schema 7 support. Earlier installed apps reject this extended file.',
    ]
    retention = ['301 ticks per channel, 0–300000 ms. 16555 bounded points; nulls are not numeric samples.',
                 'Artificial missing receipts at 211–212 s, recovery break at 213 s. No interpolation or invented missing values.',
                 'Rolling 60 s replay history; 300 s retained span plus 1 s final hold. No new vehicle reads or history writes.',
                 'All values and timestamps are fictional. See report and coverage manifest for per-PID scope.']
    for entry in coverage:
        report.append(f"{entry['pid']} {entry['name']} ({entry['unit']}): {entry['coverage']}; {entry['numeric_receipts']} numeric / {entry['null_receipts']} null receipts")
    review = dict(instance=0, started_monotonic_ms=0, elapsed_ms=300000, assessment=None,
                  highlights=highlights, retention=retention, dropped_events=0, signals=summaries,
                  conditions=[], series=[[pid, 2000, pts] for pid, pts in points_by_pid.items()], bookmarks=[])
    drive = dict(schema='openwrench.drive-history/7', id=str(uuid.uuid5(uuid.NAMESPACE_URL, 'openwrench:synthetic:legacy-inspired:55-channel:v1')),
                 origin='SYNTHETIC', started_epoch_ms=946684800000, saved_epoch_ms=946685100000, status='COMPLETED',
                 end_reason='Synthetic demonstration complete; no real vehicle', ecu=None, generation=0, report='\n'.join(report)+'\n', review=review)
    meta = {'schema': 'openwrench.synthetic-coverage/1', 'vehicle_capabilities': 'UNVERIFIED', 'synthetic': True,
            'scalar_channels': len(scalars), 'structured_omitted': [f'01{p:02X}' for p in sorted(structured)], 'channels': coverage}
    return drive, meta


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seed', type=pathlib.Path, default=RES / 'legacy-synthetic-v6.json')
    parser.add_argument('--output-dir', type=pathlib.Path, default=RES)
    args = parser.parse_args()
    seed_bytes = args.seed.read_bytes()
    expected = 'e70a3e8ce7ea023943a76f429b341070ae040303abe5eba1d3f10e519be2f9a9'
    assert hashlib.sha256(seed_bytes).hexdigest() == expected, 'Seed bytes differ from the verified synthetic Library input'
    drive, coverage = build(json.loads(seed_bytes))
    raw = (json.dumps(drive, ensure_ascii=False, allow_nan=False, separators=(',', ':'))+'\n').encode()
    assert len(raw) <= 2_000_000
    coverage.update(seed_sha256=expected, drive_sha256=hashlib.sha256(raw).hexdigest(), drive_bytes=len(raw))
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / NAME).write_bytes(raw)
    (args.output_dir / 'coverage.json').write_text(json.dumps(coverage, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({'file': str(args.output_dir / NAME), 'bytes': len(raw), 'sha256': coverage['drive_sha256'], 'channels': len(drive['review']['series'])}))

if __name__ == '__main__':
    main()
