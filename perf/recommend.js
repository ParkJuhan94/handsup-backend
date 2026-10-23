// 경매 추천 API(GET /api/auctions/recommend) 부하 테스트.
// Before/After가 같은 부하를 받도록 고정 도착률(constant-arrival-rate)을 쓰고, 요청 순서도 반복 번호로 결정해 매번 같다.
// 사용: k6 run -e LABEL=cache-both -e RATE=200 perf/recommend.js   (보통은 perf/run.sh test 로 호출)
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://localhost:8083';
const LABEL = __ENV.LABEL || 'unlabeled';
const RATE = parseInt(__ENV.RATE || '200');
const DURATION = __ENV.DURATION || '60s';
const WARMUP = __ENV.WARMUP || '15s';
const RESULT_DIR = __ENV.RESULT_DIR || 'perf/results';

// 키 공간: 정렬 4 x 지역 26(전체 1 + 구 5 + 동 20) x 페이지 3 = 312개. 앞쪽 키일수록 자주 불리는 편중 분포.
const SORTS = ['BOOKMARK', 'END_DATE', 'BIDDING', 'CREATED'];
const LOCS = [{}];
for (let g = 1; g <= 5; g++) {
  LOCS.push({ si: '서울특별시', gu: `구${g}` });
  for (let d = 1; d <= 4; d++) LOCS.push({ si: '서울특별시', gu: `구${g}`, dong: `동${d}` });
}
const KEYS = [];
for (let p = 0; p < 3; p++) for (const loc of LOCS) for (const sort of SORTS) KEYS.push({ sort, page: p, ...loc });

function pick(i) {
  // 반복 번호를 해시해 0~1 값으로 바꾸고 제곱해 앞쪽 키에 쏠리게 한다(결정적, 실행마다 동일).
  const h = Math.imul(i + 1, 2654435761) >>> 0;
  const u = (h % 100000) / 100000;
  return KEYS[Math.floor(u * u * KEYS.length)];
}

function qs(k) {
  const parts = [`sort=${k.sort}`, `page=${k.page}`, 'size=10'];
  for (const f of ['si', 'gu', 'dong']) if (k[f]) parts.push(`${f}=${encodeURIComponent(k[f])}`);
  return parts.join('&');
}

const arrival = (startTime, duration, phase) => ({
  executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration, startTime,
  preAllocatedVUs: 100, maxVUs: 400, tags: { phase },
});

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    warmup: arrival('0s', WARMUP, 'warmup'),
    measure: arrival(`${parseInt(WARMUP) + 5}s`, DURATION, 'measure'),
  },
  thresholds: {
    'http_req_failed{phase:measure}': ['rate<0.01'],
    'http_req_duration{phase:measure}': ['p(95)<2000'],
  },
};

export default function () {
  const res = http.get(`${BASE}/api/auctions/recommend?${qs(pick(exec.scenario.iterationInTest))}`);
  check(res, { 'status 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  const m = data.metrics;
  const d = m['http_req_duration{phase:measure}'].values;
  const out = {
    label: LABEL,
    config: { rate: RATE, duration: DURATION, warmup: WARMUP, keys: KEYS.length, base: BASE, gitHead: __ENV.GIT_HEAD || '', cacheMode: __ENV.CACHE_MODE || '' },
    latencyMs: { avg: d.avg, p50: d.med, p90: d['p(90)'], p95: d['p(95)'], p99: d['p(99)'], max: d.max },
    requests: m['http_reqs{phase:measure}'] ? m['http_reqs{phase:measure}'].values.count : null,
    rps: m['http_reqs{phase:measure}'] ? m['http_reqs{phase:measure}'].values.count / parseInt(DURATION) : null,
    failedRate: m['http_req_failed{phase:measure}'].values.rate,
    droppedIterations: m.dropped_iterations ? m.dropped_iterations.values.count : 0,
  };
  return {
    [`${RESULT_DIR}/${LABEL}.json`]: JSON.stringify(out, null, 2),
    stdout: `\n[${LABEL}] p50=${d.med.toFixed(1)}ms p95=${d['p(95)'].toFixed(1)}ms p99=${d['p(99)'].toFixed(1)}ms rps=${out.rps === null ? 'n/a' : out.rps.toFixed(1)} failed=${(out.failedRate * 100).toFixed(2)}% dropped=${out.droppedIterations}\n`,
  };
}
