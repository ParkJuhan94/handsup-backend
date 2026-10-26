-- 동시 입찰 테스트 전 초기화: 대상 경매의 입찰을 지우고 가격·입찰 수를 시드 상태로 되돌린다(handsup_perf 전용).
SET @a = IFNULL(@a, 1);
DELETE FROM bidding WHERE auction_id = @a;
UPDATE auction SET bidding_count = 0, current_bidding_price = init_price, auction_status = 'BIDDING', end_date = CURDATE() + INTERVAL 60 DAY WHERE auction_id = @a;
