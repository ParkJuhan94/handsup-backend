package dev.handsup.bidding.repository;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lombok.extern.slf4j.Slf4j;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import dev.handsup.auction.domain.Auction;
import dev.handsup.auction.domain.product.product_category.ProductCategory;
import dev.handsup.auction.repository.auction.AuctionRepository;
import dev.handsup.auction.repository.product.ProductCategoryRepository;
import dev.handsup.bidding.dto.request.RegisterBiddingRequest;
import dev.handsup.bidding.service.BiddingService;
import dev.handsup.config.TestAuditingConfig;
import dev.handsup.config.TestCacheConfig;
import dev.handsup.config.TestRedisConfig;
import dev.handsup.fixture.AuctionFixture;
import dev.handsup.fixture.UserFixture;
import dev.handsup.support.TestContainerSupport;
import dev.handsup.user.domain.User;
import dev.handsup.user.repository.UserRepository;

@DisplayName("[BiddingConcurrency 테스트]")
@Slf4j
@SpringBootTest
@Import({TestAuditingConfig.class, TestCacheConfig.class, TestRedisConfig.class})
class BiddingConcurrencyTest extends TestContainerSupport {

    private Auction auction;
    private User user;

    @Autowired
    private BiddingService biddingService;

    @Autowired
    private ProductCategoryRepository productCategoryRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BiddingRepository biddingRepository;

    @BeforeEach
    void setUp() {
        ProductCategory productCategory = productCategoryRepository.save(
            ProductCategory.from("디지털 기기"));
        Auction newAuction = AuctionFixture.auction(productCategory);
        ReflectionTestUtils.setField(newAuction, "endDate", LocalDate.now().plusDays(1)); // 픽스처 마감일은 이미 지났다
        auction = auctionRepository.save(newAuction);
        user = userRepository.save(UserFixture.user1());
    }

    @DisplayName("[동시에 100개 요청 시, 입찰 금액이 모두 같다면 하나의 입찰만 저장된다.]")
    @Test
    void concurrency_test() throws InterruptedException {
        RegisterBiddingRequest request = RegisterBiddingRequest.from(auction.getInitPrice() + 1000);
        int threadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    biddingService.registerBidding(request, auction.getId(), user);
                } catch (Exception e) {
                    log.info("{concurrency test error = {}", e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        Assertions.assertThat(biddingRepository.findAll()).hasSize(1);
    }
}
