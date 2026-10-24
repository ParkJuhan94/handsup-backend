package dev.handsup.bidding.service;

import static dev.handsup.bidding.exception.BiddingErrorCode.BIDDING_PRICE_LESS_THAN_INIT_PRICE;
import static dev.handsup.bidding.exception.BiddingErrorCode.BIDDING_PRICE_NOT_HIGH_ENOUGH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import dev.handsup.auction.domain.Auction;
import dev.handsup.auction.domain.auction_field.AuctionStatus;
import dev.handsup.auction.service.AuctionService;
import dev.handsup.bidding.domain.Bidding;
import dev.handsup.bidding.domain.TradingStatus;
import dev.handsup.bidding.dto.request.RegisterBiddingRequest;
import dev.handsup.bidding.dto.response.BiddingResponse;
import dev.handsup.bidding.repository.BiddingQueryRepository;
import dev.handsup.bidding.repository.BiddingRepository;
import dev.handsup.common.dto.PageResponse;
import dev.handsup.common.exception.ValidationException;
import dev.handsup.fixture.AuctionFixture;
import dev.handsup.fixture.BiddingFixture;
import dev.handsup.fixture.UserFixture;
import dev.handsup.notification.service.FCMService;
import dev.handsup.user.domain.User;

@ExtendWith(MockitoExtension.class)
@DisplayName("[BiddingService 테스트]")
class BiddingServiceTest {

    private final Auction auction = AuctionFixture.auction();    // 최소 입찰가 10000원
    private final User user = UserFixture.user1();

    @Mock
    private BiddingRepository biddingRepository;
    @Mock
    private BiddingQueryRepository biddingQueryRepository;
    @Mock
    private FCMService fcmService;
    @Mock
    private AuctionService auctionService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private BiddingService biddingService;

    @Test
    @DisplayName("[입찰가가 최소 입찰가보다 낮으면 예외를 발생시킨다]")
    void validateBiddingPrice_LessThanInitPrice_ThrowsException() {
        // given: 입찰 내역이 없는 경매(biddingCount = 0)

        // when & then
        int initPrice = auction.getInitPrice();
        assertThatThrownBy(() -> biddingService.validateBiddingPrice(initPrice - 1, auction))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining(BIDDING_PRICE_LESS_THAN_INIT_PRICE.getMessage());
    }

    @Test
    @DisplayName("[입찰가가 최고 입찰가보다 1000원 이상 높지 않으면 예외를 발생시킨다]")
    void validateBiddingPrice_NotHighEnough_ThrowsException() {
        // given
        int maxBiddingPrice = 15000;
        placeBid(auction, maxBiddingPrice);

        // when & then
        assertThatThrownBy(() -> biddingService.validateBiddingPrice(maxBiddingPrice + 999, auction))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining(BIDDING_PRICE_NOT_HIGH_ENOUGH.getMessage());
    }

    @Test
    @DisplayName("[첫 입찰은 최소 입찰가와 같아도 통과한다]")
    void validateBiddingPrice_FirstBidEqualToInitPrice_Passes() {
        // when & then
        assertThatCode(() -> biddingService.validateBiddingPrice(auction.getInitPrice(), auction))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[입찰가가 최고 입찰가보다 정확히 1000원 높으면 통과한다]")
    void validateBiddingPrice_ExactlyThousandHigher_Passes() {
        // given
        int maxBiddingPrice = 15000;
        placeBid(auction, maxBiddingPrice);

        // when & then
        assertThatCode(() -> biddingService.validateBiddingPrice(maxBiddingPrice + 1000, auction))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[입찰 내역이 있으면 최소 입찰가 이상이어도 최고 입찰가 + 1000원 미만이면 예외를 발생시킨다]")
    void validateBiddingPrice_AboveInitPriceButBelowMax_ThrowsException() {
        // given: 최소 입찰가(10000)보다는 높지만 최고 입찰가(30000)에는 못 미침
        placeBid(auction, 30000);

        // when & then
        assertThatThrownBy(() -> biddingService.validateBiddingPrice(20000, auction))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining(BIDDING_PRICE_NOT_HIGH_ENOUGH.getMessage());
    }

    @Test
    @DisplayName("[입찰 등록이 성공적으로 이루어진다]")
    void registerBidding_Success() {
        // given
        RegisterBiddingRequest request = RegisterBiddingRequest.from(20000);

        given(auctionService.getAuctionById(auction.getId())).willReturn(auction);

        Bidding bidding = Bidding.of(
            request.biddingPrice(),
            auction,
            user
        );
        ReflectionTestUtils.setField(bidding, "createdAt", LocalDateTime.now());
        given(biddingRepository.save(any(Bidding.class))).willReturn(bidding);

        // when
        BiddingResponse response = biddingService.registerBidding(request, auction.getId(), user);

        // then
        assertThat(response).isNotNull();
        verify(biddingRepository).save(any(Bidding.class));
    }

    @Test
    @DisplayName("[경매 ID에 대한 입찰 목록을 페이지 형태로 조회한다]")
    void getBidsOfAuction_Success() {
        // given
        Long auctionId = 1L;
        Pageable pageRequest = PageRequest.of(0, 10);

        Bidding bidding1 = Bidding.of(40000, auction, user);
        Bidding bidding2 = Bidding.of(30000, auction, user);
        Bidding bidding3 = Bidding.of(20000, auction, user);
        List<Bidding> mockBiddingList = List.of(
            bidding1,
            bidding2,
            bidding3
        );
        Slice<Bidding> mockSlice = new SliceImpl<>(mockBiddingList, pageRequest, true);
        ReflectionTestUtils.setField(bidding1, "createdAt", LocalDateTime.now());
        ReflectionTestUtils.setField(bidding2, "createdAt", LocalDateTime.now());
        ReflectionTestUtils.setField(bidding3, "createdAt", LocalDateTime.now());

        given(biddingRepository.findByAuctionIdOrderByBiddingPriceDesc(auctionId, pageRequest))
            .willReturn(mockSlice);

        // when
        PageResponse<BiddingResponse> response =
            biddingService.getBidsOfAuction(auctionId, pageRequest);

        // then
        assertThat(response).isNotNull();
        assertThat(response.content()).hasSize(3);
        assertThat(response.content().get(0).biddingPrice()).isEqualTo(40000);
        assertThat(response.content().get(1).biddingPrice()).isEqualTo(30000);
        assertThat(response.content().get(2).biddingPrice()).isEqualTo(20000);

        verify(biddingRepository).findByAuctionIdOrderByBiddingPriceDesc(auctionId, pageRequest);
    }

    @DisplayName("[판매자는 진행 중인 거래를 완료 상태로 변경할 수 있다.]")
    @Test
    void completeTrading() {
        //given
        User bidder = UserFixture.user2();
        Bidding bidding = BiddingFixture.bidding(auction, bidder, TradingStatus.PROGRESSING);
        ReflectionTestUtils.setField(bidding, "createdAt", LocalDateTime.now());
        ReflectionTestUtils.setField(auction, "status", AuctionStatus.TRADING);

        given(biddingRepository.findById(1L)).willReturn(Optional.of(bidding));

        //when
        BiddingResponse response = biddingService.completeTrading(bidding.getId(), user);

        //then
        assertThat(response.tradingStatus()).isEqualTo(TradingStatus.COMPLETED.getLabel());
        assertThat(bidding.getAuction().getBuyer()).isEqualTo(bidder);
    }

    @DisplayName("[판매자는 진행 중인 거래를 취소 상태로 변경할 수 있다.]")
    @Test
    void cancelTrading() {
        //given
        User bidder = UserFixture.user2();
        Bidding bidding1 = BiddingFixture.bidding(auction, bidder, TradingStatus.PROGRESSING, 40000);
        ReflectionTestUtils.setField(bidding1, "createdAt", LocalDateTime.now());

        Bidding bidding2 = BiddingFixture.bidding(auction, bidder, TradingStatus.WAITING,
            bidding1.getBiddingPrice() + 1000);
        given(biddingRepository.findById(1L)).willReturn(Optional.of(bidding1));
        given(biddingQueryRepository.findWaitingBiddingLatest(auction)).willReturn(Optional.of(bidding2));

        //when
        BiddingResponse response = biddingService.cancelTrading(bidding1.getId(), user);

        //then
        assertThat(response.tradingStatus()).isEqualTo(TradingStatus.CANCELED.getLabel());
        assertThat(bidding2.getTradingStatus()).isEqualTo(TradingStatus.PREPARING);
    }

    // 락 안에서 실제 입찰이 반영된 것과 같은 상태(현재가 갱신, 입찰 수 증가)를 만든다
    private void placeBid(Auction auction, int price) {
        auction.updateCurrentBiddingPrice(price);
        auction.increaseBiddingCount();
    }
}
