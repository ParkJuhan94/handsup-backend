#!/usr/bin/env python3
"""trace_ttl.sh 결과(요청별 CSV, db-r*.log)를 시간 구간별로 요약한다. 사용: python3 perf/analyze_ttl.py perf/results/ttl"""
import csv, glob, os, re, statistics as st, sys
from collections import defaultdict

def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, int(len(v) * q))] if v else float('nan')

# 구간(초, 첫 요청 기준): 콜드 스타트, 안정 구간, Caffeine 만료(Redis가 받음), 두 계층이 거의 동시에 만료(DB로 감), 그 이후
WINDOWS = [("콜드 0~30s", 0, 30), ("콜드 30~60s", 30, 60), ("안정 120~280s", 120, 280),
           ("5분 전후 290~340s", 290, 340), ("10분 전후 590~680s", 590, 680), ("만료 후 700~780s", 700, 780)]

def load_requests(path):
    per_sec = defaultdict(list)
    t0 = None
    with open(path, newline='') as f:
        for row in csv.DictReader(f):
            if row['metric_name'] != 'http_req_duration':
                continue
            t = int(float(row['timestamp']))
            t0 = t if t0 is None else min(t0, t)
            per_sec[t].append(float(row['metric_value']))
    return t0, per_sec

def load_db(path):
    rows = []
    for line in open(path):
        p = line.split()
        if len(p) >= 2 and p[0].isdigit() and p[1].isdigit():
            rows.append((int(p[0]), int(p[1]), int(p[2]) if len(p) > 2 and p[2].isdigit() else None,
                         int(p[3]) if len(p) > 3 and p[3].isdigit() else None))
    return rows

for csv_path in sorted(glob.glob(os.path.join(sys.argv[1], 'ttl-r*.csv'))):
    r = re.search(r'ttl-r(\d+)\.csv', csv_path).group(1)
    t0, per_sec = load_requests(csv_path)
    db = load_db(os.path.join(sys.argv[1], f'db-r{r}.log'))
    sel = {}
    for (t_prev, c_prev, *_), (t, c, *_) in zip(db, db[1:]):
        if t > t_prev:
            sel[t] = (c - c_prev) / (t - t_prev)
    print(f"\n=== 실행 r{r} (요청 {sum(len(v) for v in per_sec.values())}건, 시작 {t0}) ===")
    print(f"{'구간':<20}{'요청':>7}{'p50':>8}{'p95':>8}{'p99':>8}{'max':>8}{'DB select/s 평균':>18}{'최대':>8}")
    for name, a, b in WINDOWS:
        vals = [x for t, v in per_sec.items() if a <= t - t0 < b for x in v]
        s = [v for t, v in sel.items() if a <= t - t0 < b]
        print(f"{name:<20}{len(vals):>7}{pct(vals,.5):>8.1f}{pct(vals,.95):>8.1f}{pct(vals,.99):>8.1f}{(max(vals) if vals else float('nan')):>8.1f}"
              f"{(st.mean(s) if s else float('nan')):>18.1f}{(max(s) if s else float('nan')):>8.1f}")
    if sel:
        top = sorted(sel.items(), key=lambda kv: -kv[1])[:5]
        print("DB select/s 상위 5초(첫 요청 기준 초): " + ", ".join(f"{t - t0}s={v:.0f}" for t, v in sorted(top)))
