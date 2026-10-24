-- 동시 입찰 정합성 검증. 사용: perf/run.sh bid-verify <auctionId>
-- 불변식: (1) 저장된 입찰 수 = auction.bidding_count  (2) auction.current_bidding_price = 저장된 최고가
--         (3) 입찰은 저장 순서(bidding_id)대로 가격이 1,000원 이상씩 올라야 한다(위반 건수 0)
SET @a = IFNULL(@a, 1);
SELECT a.auction_id,
       a.bidding_count                                   AS auction_bidding_count,
       (SELECT COUNT(*) FROM bidding b WHERE b.auction_id = a.auction_id)           AS saved_bids,
       a.current_bidding_price                           AS auction_current_price,
       (SELECT MAX(bidding_price) FROM bidding b WHERE b.auction_id = a.auction_id) AS saved_max_price,
       (SELECT COUNT(*) FROM (
          SELECT bidding_price - LAG(bidding_price) OVER (ORDER BY bidding_id) AS diff
          FROM bidding WHERE auction_id = a.auction_id) t WHERE diff IS NOT NULL AND diff < 1000) AS order_violations,
       (SELECT COUNT(DISTINCT bidder_id) FROM bidding b WHERE b.auction_id = a.auction_id) AS distinct_bidders
FROM auction a WHERE a.auction_id = @a;
