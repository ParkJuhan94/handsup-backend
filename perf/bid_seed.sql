-- 입찰 부하 테스트용 시드(handsup_perf 전용): 경매 1~5000번에 입찰을 100건씩, 총 50만 건을 넣고 경매의 현재가·입찰 수를 맞춘다.
-- 입찰 이력이 쌓인 상태에서 락 안의 MAX(bidding_price) 쿼리 비용(인덱스 유무)이 드러나게 하려는 조건이다.
-- 재실행하면 bidding을 비우고 다시 넣으므로, 측정 사이의 초기화 용도로도 쓴다. 입찰자는 시드 판매자(user 1)로 둔다.
SET SESSION cte_max_recursion_depth = 100000;
TRUNCATE TABLE bidding;

INSERT INTO bidding (created_at, updated_at, bidding_price, trading_status, auction_id, bidder_id)
WITH RECURSIVE a(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM a WHERE i < 5000),
               j(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM j WHERE n < 100)
SELECT NOW(), NOW(), 10000 + 1000 * n, 'WAITING', i, 1 FROM a CROSS JOIN j;

-- 경매 쪽 비정규화 값(현재가, 입찰 수)을 입찰 이력과 일치시킨다. 최고가 = 10000 + 1000*100 = 110000
UPDATE auction SET bidding_count = 100, current_bidding_price = 110000, auction_status = 'BIDDING' WHERE auction_id BETWEEN 1 AND 5000;
SELECT COUNT(*) AS bids, (SELECT COUNT(*) FROM auction WHERE bidding_count = 100) AS auctions FROM bidding;
