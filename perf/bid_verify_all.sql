-- 전체 경매의 입찰 정합성 검증. 불일치 건수가 0이어야 한다.
--   count_mismatch : 저장된 입찰 수 != auction.bidding_count
--   price_mismatch : auction.current_bidding_price != 저장된 최고가(입찰이 없으면 init_price)
--   order_violations : 경매별로 저장 순서(bidding_id)대로 가격이 1,000원 이상 오르지 않은 입찰 수(락/검증이 깨졌다는 신호)
SELECT
  (SELECT COUNT(*) FROM auction a LEFT JOIN (SELECT auction_id, COUNT(*) c FROM bidding GROUP BY auction_id) b ON a.auction_id = b.auction_id
    WHERE a.bidding_count <> IFNULL(b.c, 0)) AS count_mismatch,
  (SELECT COUNT(*) FROM auction a LEFT JOIN (SELECT auction_id, MAX(bidding_price) m FROM bidding GROUP BY auction_id) b ON a.auction_id = b.auction_id
    WHERE a.current_bidding_price <> IFNULL(b.m, a.init_price)) AS price_mismatch,
  (SELECT COUNT(*) FROM (
      SELECT bidding_price - LAG(bidding_price) OVER (PARTITION BY auction_id ORDER BY bidding_id) AS diff FROM bidding) t
    WHERE diff IS NOT NULL AND diff < 1000) AS order_violations,
  (SELECT COUNT(*) FROM bidding) AS total_bids;
