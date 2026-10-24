// 동시 입찰 정합성 테스트(POST /api/auctions/{id}/bids). 처리량이 아니라 "정확히 몇 건이 저장되는가"가 결과다.
// 사용: perf/run.sh bid <same|ascending> <auctionId>   (내부적으로 k6 run -e MODE=... -e AUCTION_ID=... 호출)
//   same      : 모든 요청이 같은 가격(10000). 최초 1건만 성공해야 한다
//   ascending : 요청마다 서로 다른 가격(10000 + 1000*i). 성공한 입찰들이 가격 순서대로 쌓이고 최고가가 최종값이어야 한다
// 결과 검증은 DB 쪽에서 한다(perf/bid_verify.sql). 여기서는 HTTP 결과 분포만 센다.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://localhost:8083';
const MODE = __ENV.MODE || 'same';
const AUCTION_ID = __ENV.AUCTION_ID || '1';
const USERS = parseInt(__ENV.USERS || '100');
const RESULT_DIR = __ENV.RESULT_DIR || 'perf/results';
const ok = new Counter('bid_200');
const rejected = new Counter('bid_4xx');
const serverError = new Counter('bid_5xx');
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export const options = {
  scenarios: {
    race: { executor: 'shared-iterations', vus: USERS, iterations: USERS, maxDuration: '60s' },
  },
};

export function setup() {
  const tokens = [];
  for (let i = 1; i <= USERS; i++) {
    const email = `bidder${i}@handsup.dev`;
    const body = { email, password: 'pass1234!', nickname: `bidder${i}`, si: '서울특별시', gu: '구1', dong: '동1', profileImageUrl: 'https://img.example/p.jpg', productCategoryIds: [1] };
    http.post(`${BASE}/api/users`, JSON.stringify(body), { headers: JSON_HEADERS }); // 이미 있으면 실패해도 무방
    const login = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email, password: 'pass1234!' }), { headers: JSON_HEADERS });
    if (login.status !== 200) throw new Error(`login failed for ${email}: ${login.status} ${login.body}`);
    tokens.push(login.json('accessToken'));
  }
  return { tokens };
}

export default function (data) {
  const i = exec.vu.idInTest - 1; // 1..USERS -> 0..USERS-1
  const price = MODE === 'same' ? 10000 : 10000 + 1000 * (i + 1);
  const res = http.post(`${BASE}/api/auctions/${AUCTION_ID}/bids`, JSON.stringify({ biddingPrice: price }), {
    headers: { ...JSON_HEADERS, Authorization: `Bearer ${data.tokens[i]}` },
  });
  if (res.status === 200) ok.add(1); else if (res.status < 500) rejected.add(1); else serverError.add(1);
  check(res, { 'no 5xx': (r) => r.status < 500 });
}

export function handleSummary(data) {
  const c = (n) => (data.metrics[n] ? data.metrics[n].values.count : 0);
  const out = { mode: MODE, auctionId: AUCTION_ID, users: USERS, http200: c('bid_200'), http4xx: c('bid_4xx'), http5xx: c('bid_5xx') };
  return {
    [`${RESULT_DIR}/bid-${MODE}-${AUCTION_ID}.json`]: JSON.stringify(out, null, 2),
    stdout: `\n[bid-${MODE}] 200=${out.http200} 4xx=${out.http4xx} 5xx=${out.http5xx} (DB 저장 결과는 perf/bid_verify.sql로 확인)\n`,
  };
}
