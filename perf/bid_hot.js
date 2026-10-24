// 인기 경매에 입찰이 몰리는 상황의 처리량 측정(POST /api/auctions/{id}/bids). 고정 도착률로 부하를 주고, 정합성은 bid_verify_all.sql로 따로 확인한다.
// 요청의 HOT_SHARE(기본 80%)가 인기 경매(HOT_ID)로, 나머지는 2~POOL번 경매로 간다. 가격은 반복 번호로 정해 실행마다 같다.
// 사용: perf/run.sh bid-hot [RATE]   (시드 상태에서 현재가가 110000이므로 PRICE_BASE=120000부터 올린다)
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8083';
const RATE = parseInt(__ENV.RATE || '100');
const DURATION = __ENV.DURATION || '30s';
const USERS = parseInt(__ENV.USERS || '100');
const HOT_ID = parseInt(__ENV.HOT_ID || '1');
const HOT_SHARE = parseFloat(__ENV.HOT_SHARE || '0.8');
const POOL = parseInt(__ENV.POOL || '200');
const PRICE_BASE = parseInt(__ENV.PRICE_BASE || '120000');
const LABEL = __ENV.LABEL || 'bid-hot';
const RESULT_DIR = __ENV.RESULT_DIR || 'perf/results';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

const ok = new Counter('bid_200');
const rejected = new Counter('bid_4xx');
const lockTimeout = new Counter('bid_429');
const serverError = new Counter('bid_5xx');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    hot: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION, preAllocatedVUs: 200, maxVUs: 600 },
  },
};

export function setup() {
  const tokens = [];
  for (let i = 1; i <= USERS; i++) {
    const email = `bidder${i}@handsup.dev`;
    const body = { email, password: 'pass1234!', nickname: `bidder${i}`, si: '서울특별시', gu: '구1', dong: '동1', profileImageUrl: 'https://img.example/p.jpg', productCategoryIds: [1] };
    http.post(`${BASE}/api/users`, JSON.stringify(body), { headers: JSON_HEADERS });
    const login = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email, password: 'pass1234!' }), { headers: JSON_HEADERS });
    if (login.status !== 200) throw new Error(`login failed for ${email}: ${login.status}`);
    tokens.push(login.json('accessToken'));
  }
  return { tokens };
}

function hash(i) { return (Math.imul(i + 1, 2654435761) >>> 0) / 4294967296; }

export default function (data) {
  const n = exec.scenario.iterationInTest;
  const hot = hash(n) < HOT_SHARE;
  const auctionId = hot ? HOT_ID : 2 + Math.floor(hash(n * 7 + 3) * (POOL - 1));
  const price = PRICE_BASE + 1000 * (n + 1);
  const res = http.post(`${BASE}/api/auctions/${auctionId}/bids`, JSON.stringify({ biddingPrice: price }), {
    headers: { ...JSON_HEADERS, Authorization: `Bearer ${data.tokens[n % USERS]}` },
    tags: { target: hot ? 'hot' : 'other' },
  });
  if (res.status === 200) ok.add(1); else if (res.status === 429) lockTimeout.add(1); else if (res.status < 500) rejected.add(1); else serverError.add(1);
}

export function handleSummary(data) {
  const c = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  const d = data.metrics.http_req_duration.values;
  const out = {
    label: LABEL, config: { rate: RATE, duration: DURATION, hotShare: HOT_SHARE, hotId: HOT_ID, pool: POOL, priceBase: PRICE_BASE },
    http200: c('bid_200'), http4xx: c('bid_4xx'), http429: c('bid_429'), http5xx: c('bid_5xx'),
    acceptedPerSec: c('bid_200') / parseInt(DURATION),
    latencyMs: { avg: d.avg, p50: d.med, p90: d['p(90)'], p95: d['p(95)'], p99: d['p(99)'], max: d.max },
    droppedIterations: data.metrics.dropped_iterations ? data.metrics.dropped_iterations.values.count : 0,
  };
  return {
    [`${RESULT_DIR}/${LABEL}.json`]: JSON.stringify(out, null, 2),
    stdout: `\n[${LABEL}] 200=${out.http200} 4xx=${out.http4xx} 429=${out.http429} 5xx=${out.http5xx} accepted/s=${out.acceptedPerSec.toFixed(1)} p50=${d.med.toFixed(1)}ms p95=${d['p(95)'].toFixed(1)}ms p99=${d['p(99)'].toFixed(1)}ms dropped=${out.droppedIterations}\n`,
  };
}
