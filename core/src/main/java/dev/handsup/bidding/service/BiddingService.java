package dev.handsup.bidding.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.handsup.auction.domain.Auction;
import dev.handsup.auction.service.AuctionService;
import dev.handsup.bidding.domain.Bidding;
import dev.handsup.bidding.dto.BiddingMapper;
import dev.handsup.bidding.dto.request.RegisterBiddingRequest;
import dev.handsup.bidding.dto.response.BiddingResponse;
import dev.handsup.bidding.event.BiddingEvent.BiddingEventType;
import dev.handsup.bidding.exception.BiddingErrorCode;
import dev.handsup.bidding.repository.BiddingQueryRepository;
import dev.handsup.bidding.repository.BiddingRepository;
import dev.handsup.common.dto.CommonMapper;
import dev.handsup.common.dto.PageResponse;
import dev.handsup.common.exception.NotFoundException;
import dev.handsup.common.exception.ValidationException;
import dev.handsup.common.redisson.DistributeLock;
import dev.handsup.notification.domain.NotificationType;
import dev.handsup.notification.service.FCMService;
import dev.handsup.user.domain.User;

@Slf4j
@Service
@RequiredArgsConstructor
public class BiddingService {

    private final BiddingRepository biddingRepository;
    private final BiddingQueryRepository biddingQueryRepository;
    private final AuctionService auctionService;
    private final FCMService fcmService;
    private final ApplicationEventPublisher eventPublisher;

    // 트랜잭션은 DistributeLockAop -> AopForTransaction(REQUIRES_NEW)이 전담한다.
    // 여기에 @Transactional을 함께 붙이면 이중 트랜잭션(커넥션 2개)이 걸려
    // 부하 상황에서 락을 쥔 스레드가 커넥션 대기로 지연되고 leaseTime 만료로 이어진다.
    // 인기 경매에 입찰이 몰리면 락 대기열이 길어져 Tomcat 스레드가 오래 묶이므로, 대기를 1초로 줄여 빨리 실패시킨다
    @DistributeLock(key = "'auction_' + #auctionId", waitTime = 1L) // auctionId 값을 추출하여 락 키로 사용
    public BiddingResponse registerBidding(RegisterBiddingRequest request, Long auctionId, User bidder) {
        Auction auction = auctionService.getAuctionById(auctionId);
        int biddingPrice = request.biddingPrice();

        validateBiddingPrice(biddingPrice, auction); // 경매 입찰 최고가보다 입찰가 높은지 확인
        auction.updateCurrentBiddingPrice(biddingPrice); // 경매 입찰 최고가 갱신
        auction.increaseBiddingCount();

        Bidding savedBidding = biddingRepository.save(BiddingMapper.toBidding(biddingPrice, auction, bidder));
        eventPublisher.publishEvent(BiddingMapper.toBiddingEvent(savedBidding, BiddingEventType.REGISTERED));

        return BiddingMapper.toBiddingResponse(savedBidding);
    }

    @Transactional(readOnly = true)
    public PageResponse<BiddingResponse> getBidsOfAuction(Long auctionId, Pageable pageable) {
        Slice<BiddingResponse> biddingResponsePage = biddingRepository
            .findByAuctionIdOrderByBiddingPriceDesc(auctionId, pageable)
            .map(BiddingMapper::toBiddingResponse);
        return CommonMapper.toPageResponse(biddingResponsePage);
    }

    @Transactional
    public BiddingResponse completeTrading(Long biddingId, User user) {
        Bidding bidding = findBiddingById(biddingId);
        Auction auction = bidding.getAuction();

        auction.validateIfSeller(user);
        bidding.updateTradingStatusComplete();
        auction.updateAuctionStatusCompleted();
        auction.updateBuyer(bidding.getBidder());
        auction.updateBuyPrice(bidding.getBiddingPrice());

        fcmService.sendMessage(
            auction.getSeller().getEmail(),
            auction.getSeller().getNickname(),
            bidding.getBidder().getEmail(),
            NotificationType.COMPLETED_TRADING,
            auction.getId()
        );

        return BiddingMapper.toBiddingResponse(bidding);
    }

    @Transactional
    public BiddingResponse cancelTrading(Long biddingId, User user) {
        Bidding bidding = findBiddingById(biddingId);
        Auction auction = bidding.getAuction();

        auction.validateIfSeller(user);
        bidding.updateTradingStatusCanceled();

        Bidding nextBidding = biddingQueryRepository.findWaitingBiddingLatest(bidding.getAuction())
            .orElseThrow(() -> new NotFoundException(BiddingErrorCode.NOT_FOUND_NEXT_BIDDING));
        nextBidding.updateTradingStatusPreparing();    // 다음 입찰 준비중 상태로 변경

        // 현재 입찰자 거래 취소 알림
        fcmService.sendMessage(
            auction.getSeller().getEmail(),
            auction.getSeller().getNickname(),
            bidding.getBidder().getEmail(),
            NotificationType.CANCELED_TRADING,
            auction.getId()
        );

        // 다음 입찰자 낙찰 알림
        fcmService.sendMessage(
            auction.getSeller().getEmail(),
            auction.getSeller().getNickname(),
            bidding.getBidder().getEmail(),
            NotificationType.PURCHASE_WINNING,
            auction.getId()
        );

        return BiddingMapper.toBiddingResponse(bidding);
    }

    // 락 안에서 실행되므로 입찰 이력을 다시 집계하지 않고, 락 안에서 갱신되는 경매의 현재가·입찰 수로 검증한다.
    // 현재가는 낮아지지 않으므로(취소된 입찰도 포함) 기존의 MAX(입찰가) 조회와 같은 결과다.
    public void validateBiddingPrice(int biddingPrice, Auction auction) {
        if (auction.getBiddingCount() == 0) {
            // 입찰 내역이 없는 경우, 최소 입찰가부터 입찰 가능
            if (biddingPrice < auction.getInitPrice()) {
                throw new ValidationException(BiddingErrorCode.BIDDING_PRICE_LESS_THAN_INIT_PRICE);
            }
        } else {
            // 최고 입찰가보다 1000원 이상일 때만 입찰 가능
            if (biddingPrice < (auction.getCurrentBiddingPrice() + 1000)) {
                throw new ValidationException(BiddingErrorCode.BIDDING_PRICE_NOT_HIGH_ENOUGH);
            }
        }
    }

    private Bidding findBiddingById(Long biddingId) {
        return biddingRepository.findById(biddingId)
            .orElseThrow(() -> new NotFoundException(BiddingErrorCode.NOT_FOUND_BIDDING));
    }
}
