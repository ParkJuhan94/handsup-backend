package dev.handsup.review.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import dev.handsup.auction.domain.Auction;
import dev.handsup.auction.repository.auction.AuctionRepository;
import dev.handsup.bidding.domain.Bidding;
import dev.handsup.bidding.domain.TradingStatus;
import dev.handsup.bidding.repository.BiddingRepository;
import dev.handsup.common.exception.NotFoundException;
import dev.handsup.fixture.AuctionFixture;
import dev.handsup.fixture.BiddingFixture;
import dev.handsup.fixture.ReviewFixture;
import dev.handsup.fixture.ReviewLabelFixture;
import dev.handsup.fixture.UserFixture;
import dev.handsup.fixture.UserReviewLabelFixture;
import dev.handsup.review.domain.Review;
import dev.handsup.review.domain.ReviewLabel;
import dev.handsup.review.domain.ReviewLabelValue;
import dev.handsup.review.domain.UserReviewLabel;
import dev.handsup.review.dto.request.RegisterReviewRequest;
import dev.handsup.review.dto.response.ReviewDetailResponse;
import dev.handsup.review.repository.ReviewInterReviewLabelRepository;
import dev.handsup.review.repository.ReviewLabelRepository;
import dev.handsup.review.repository.ReviewRepository;
import dev.handsup.user.domain.User;
import dev.handsup.user.repository.UserReviewLabelRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("[ReviewService 테스트]")
class ReviewServiceTest {

	private final Auction auction = AuctionFixture.auction();
	private final User writer = UserFixture.user1();
	private final Review review = ReviewFixture.review();
	private final ReviewLabel reviewLabelManner = ReviewLabelFixture.reviewLabel(
		1L, ReviewLabelValue.MANNER.getLabel()
	);
	private final ReviewLabel reviewLabelCheap = ReviewLabelFixture.reviewLabel(
		2L, ReviewLabelValue.CHEAP_PRICE.getLabel()
	);
	private final List<Long> reviewLabelIds = List.of(1L, 2L);
	private final UserReviewLabel userReviewLabelManner = UserReviewLabelFixture.userReviewLabel(
		1L, reviewLabelManner
	);
	private final UserReviewLabel userReviewLabelCheap = UserReviewLabelFixture.userReviewLabel(
		2L, reviewLabelCheap
	);
	@Mock
	private ReviewRepository reviewRepository;
	@Mock
	private ReviewLabelRepository reviewLabelRepository;
	@Mock
	private ReviewInterReviewLabelRepository reviewInterReviewLabelRepository;
	@Mock
	private UserReviewLabelRepository userReviewLabelRepository;
	@Mock
	private AuctionRepository auctionRepository;
	@Mock
	private BiddingRepository biddingRepository;
	@InjectMocks
	private ReviewService reviewService;

	@Test
	@MockitoSettings(strictness = Strictness.LENIENT)
	@DisplayName("[리뷰 등록이 성공적으로 이루어진다]")
	void registerReview_Success() {
		// given
		int expectedEvaluationScore = review.getEvaluationScore();
		String expectedContent = review.getContent();
		Long expectedAuctionId = auction.getId();
		Long expectedWriterId = writer.getId();

		RegisterReviewRequest request = RegisterReviewRequest.of(
			review.getEvaluationScore(),
			review.getContent(),
			reviewLabelIds
		);
		Long auctionId = auction.getId();
		given(auctionRepository.findById(auctionId)).willReturn(Optional.of(auction));
		given(reviewRepository.save(any(Review.class))).willReturn(review);
		ReflectionTestUtils.setField(review, "createdAt", LocalDateTime.now());

		given(reviewLabelRepository.findById(reviewLabelManner.getId())).willReturn(Optional.of(reviewLabelManner));
		given(reviewLabelRepository.findById(reviewLabelCheap.getId())).willReturn(Optional.of(reviewLabelCheap));
		given(reviewInterReviewLabelRepository.save(any())).willReturn(null);
		given(userReviewLabelRepository.save(any(UserReviewLabel.class)))
			.willReturn(userReviewLabelManner, userReviewLabelCheap);

		given(userReviewLabelRepository.findById(userReviewLabelManner.getId())).willReturn(
			Optional.of(userReviewLabelManner));
		given(userReviewLabelRepository.findById(userReviewLabelCheap.getId())).willReturn(
			Optional.of(userReviewLabelCheap));

		Bidding bidding = BiddingFixture.bidding(auction, writer);
		ReflectionTestUtils.setField(bidding, "updatedAt", LocalDateTime.now());
		given(biddingRepository.findAllByAuctionAndBidder(auction, writer)).willReturn(
			List.of(bidding));

		// when
		ReviewDetailResponse response = reviewService.registerReview(request, auctionId, writer);

		// then
		assertThat(response).isNotNull();
		assertThat(response.evaluationScore()).isEqualTo(expectedEvaluationScore);
		assertThat(response.content()).isEqualTo(expectedContent);
		assertThat(response.auctionId()).isEqualTo(expectedAuctionId);
		assertThat(response.writerId()).isEqualTo(expectedWriterId);
	}

	@Test
	@MockitoSettings(strictness = Strictness.LENIENT)
	@DisplayName("[같은 경매에 여러 번 입찰했다면 거래가 완료된 입찰로 리뷰를 등록한다]")
	void registerReview_multipleBiddings_prefersCompleted() {
		// given
		Bidding waiting = biddingWith(1L, 95000, TradingStatus.WAITING);
		Bidding completed = biddingWith(2L, 92000, TradingStatus.COMPLETED);
		stubReviewDependencies(List.of(waiting, completed));

		// when
		ReviewDetailResponse response = reviewService.registerReview(requestOf(), auction.getId(), writer);

		// then
		assertThat(response.winningPrice()).isEqualTo(92000);
	}

	@Test
	@MockitoSettings(strictness = Strictness.LENIENT)
	@DisplayName("[완료된 입찰이 없으면 가장 높은 입찰가의 입찰로 리뷰를 등록한다]")
	void registerReview_multipleBiddings_fallsBackToHighestPrice() {
		// given
		Bidding low = biddingWith(1L, 80000, TradingStatus.WAITING);
		Bidding high = biddingWith(2L, 95000, TradingStatus.WAITING);
		stubReviewDependencies(List.of(low, high));

		// when
		ReviewDetailResponse response = reviewService.registerReview(requestOf(), auction.getId(), writer);

		// then
		assertThat(response.winningPrice()).isEqualTo(95000);
	}

	@Test
	@MockitoSettings(strictness = Strictness.LENIENT)
	@DisplayName("[입찰이 없으면 리뷰를 등록할 수 없다]")
	void registerReview_noBidding_throws() {
		// given
		stubReviewDependencies(List.of());

		// when & then
		assertThatThrownBy(() -> reviewService.registerReview(requestOf(), auction.getId(), writer))
			.isInstanceOf(NotFoundException.class);
	}

	private RegisterReviewRequest requestOf() {
		return RegisterReviewRequest.of(review.getEvaluationScore(), review.getContent(), reviewLabelIds);
	}

	private Bidding biddingWith(Long id, int price, TradingStatus status) {
		Bidding bidding = new Bidding(id, price, auction, writer, status);
		ReflectionTestUtils.setField(bidding, "updatedAt", LocalDateTime.now());
		return bidding;
	}

	private void stubReviewDependencies(List<Bidding> biddings) {
		given(auctionRepository.findById(auction.getId())).willReturn(Optional.of(auction));
		given(reviewRepository.save(any(Review.class))).willReturn(review);
		ReflectionTestUtils.setField(review, "createdAt", LocalDateTime.now());
		given(reviewLabelRepository.findById(reviewLabelManner.getId())).willReturn(Optional.of(reviewLabelManner));
		given(reviewLabelRepository.findById(reviewLabelCheap.getId())).willReturn(Optional.of(reviewLabelCheap));
		given(userReviewLabelRepository.save(any(UserReviewLabel.class)))
			.willReturn(userReviewLabelManner, userReviewLabelCheap);
		given(biddingRepository.findAllByAuctionAndBidder(auction, writer)).willReturn(biddings);
	}
}
