-- 추천 API 성능 측정용 대량 시드 (handsup_perf DB 전용, 앱을 한 번 띄워 스키마가 만들어진 뒤 실행).
-- 경매 N건 = 상품 N건 = 대표 이미지 포함 이미지 3N건. 지역은 5개 구 x 4개 동 = 20곳으로 고르게 분포.
-- 사용: perf/run.sh seed (SEED_N 환경변수로 건수 조정, 기본 5000). 재실행하면 기존 데이터를 비우고 다시 넣는다.
SET @n = IFNULL(@n, 5000);
SET SESSION cte_max_recursion_depth = 1000000;

TRUNCATE TABLE product_image; TRUNCATE TABLE auction; TRUNCATE TABLE product; TRUNCATE TABLE `user`; TRUNCATE TABLE product_category;

INSERT INTO `user` (user_id, created_at, updated_at, dong, gu, si, email, nickname, password, read_notification_count, report_count, score)
VALUES (1, NOW(), NOW(), '역삼동', '강남구', '서울특별시', 'perf-seller@handsup.dev', 'perf-seller', 'x', 0, 0, 100);

INSERT INTO product_category (product_category_id, `value`)
WITH RECURSIVE c(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM c WHERE i < 13) SELECT i, CONCAT('category-', i) FROM c;

INSERT INTO product (product_id, created_at, updated_at, description, purchase_time, status, product_category_id)
WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM s WHERE i < @n)
SELECT i, NOW(), NOW(), CONCAT('perf product ', i), 'UNDER_ONE_MONTH', 'CLEAN', 1 + (i MOD 13) FROM s;

INSERT INTO product_image (created_at, updated_at, image_url, is_main, product_id)
WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM s WHERE i < @n), k(j) AS (SELECT 0 UNION ALL SELECT 1 UNION ALL SELECT 2)
SELECT NOW(), NOW(), CONCAT('https://img.example/', i, '-', j, '.jpg'), j = 0, i FROM s CROSS JOIN k;

-- 상태는 90%가 BIDDING(추천 대상), 나머지는 종료 상태. 북마크·입찰 수는 정렬 기준이 갈리도록 무작위.
INSERT INTO auction (auction_id, created_at, updated_at, bidding_count, bookmark_count, buy_price, current_bidding_price,
                     end_date, init_price, auction_status, title, trade_method, dong, gu, si, buyer_id, product_id, seller_id)
WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM s WHERE i < @n)
SELECT i, NOW() - INTERVAL FLOOR(RAND() * 60 * 24 * 60) MINUTE, NOW(), FLOOR(RAND() * 50), FLOOR(RAND() * 200), NULL,
       10000 + FLOOR(RAND() * 90) * 1000, CURDATE() + INTERVAL (1 + FLOOR(RAND() * 30)) DAY, 10000,
       IF((i DIV 20) MOD 10 = 0, 'COMPLETED', 'BIDDING'), CONCAT('perf auction ', i), 'DIRECT',
       CONCAT('동', 1 + (i MOD 20) MOD 4), CONCAT('구', 1 + (i MOD 20) DIV 4), '서울특별시', NULL, i, 1
FROM s;

SELECT (SELECT COUNT(*) FROM auction) auctions, (SELECT COUNT(*) FROM product_image) images;
