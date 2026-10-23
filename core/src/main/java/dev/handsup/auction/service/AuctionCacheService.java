package dev.handsup.auction.service;

import java.time.Duration;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;

import dev.handsup.auction.dto.response.RecommendAuctionResponse;
import dev.handsup.auction.exception.AuctionErrorCode;
import dev.handsup.auction.repository.auction.AuctionQueryRepository;
import dev.handsup.common.dto.CommonMapper;
import dev.handsup.common.dto.PageResponse;
import dev.handsup.common.exception.NotFoundException;
import dev.handsup.common.util.KeyGenerator;

@Service
@RequiredArgsConstructor
public class AuctionCacheService {

    private final Cache<String, PageResponse<RecommendAuctionResponse>> caffeineCache;
    private final RedisTemplate<String, PageResponse<RecommendAuctionResponse>> redisTemplate;
    private final AuctionQueryRepository auctionQueryRepository;

    // 성능 측정용 캐시 계층 전환(none: DB만, redis: Redis만, both: Caffeine+Redis). 운영 기본값은 both.
    @Value("${perf.cache-mode:both}")
    private String cacheMode = "both"; // 단위 테스트처럼 @Value 주입이 없을 때도 운영 기본 동작을 유지

    public PageResponse<RecommendAuctionResponse> getRecommendAuctions(
        String si, String gu, String dong, int page, int size, String sort
    ) {
        if (sort == null || sort.isBlank()) {
            throw new NotFoundException(AuctionErrorCode.EMPTY_SORT_INPUT);
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.by(sort)));
        String key = KeyGenerator.generateRecommendAuctionsKey(si, gu, dong, pageable);

        boolean useCaffeine = "both".equals(cacheMode);
        boolean useRedis = !"none".equals(cacheMode);

        // 1. Caffeine 조회
        PageResponse<RecommendAuctionResponse> cached = useCaffeine ? caffeineCache.getIfPresent(key) : null;
        if (cached != null) {
            return cached;
        }

        // 2. Redis 조회
        cached = useRedis ? redisTemplate.opsForValue().get(key) : null;
        if (cached != null) {
            if (useCaffeine) {
                caffeineCache.put(key, cached); // Caffeine에도 저장
            }
            return cached;
        }

        // 3. DB 조회
        Slice<RecommendAuctionResponse> auctionSlice = auctionQueryRepository
            .sortAuctionByCriteria(si, gu, dong, pageable);

        PageResponse<RecommendAuctionResponse> response = CommonMapper.toPageResponse(auctionSlice);

        // 4. 캐시에 저장
        if (useCaffeine) {
            caffeineCache.put(key, response);
        }
        if (useRedis) {
            redisTemplate.opsForValue().set(key, response, Duration.ofMinutes(10));
        }

        return response;
    }
}
