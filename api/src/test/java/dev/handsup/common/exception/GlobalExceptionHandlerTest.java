package dev.handsup.common.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.handsup.bidding.exception.BiddingErrorCode;
import dev.handsup.common.redisson.LockAcquisitionException;

@DisplayName("[GlobalExceptionHandler 테스트]")
class GlobalExceptionHandlerTest {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("[락 대기 시간을 넘기면 429와 락 에러 코드로 응답한다]")
	void lockAcquisitionException_returnsTooManyRequests() throws Exception {
		mockMvc.perform(get("/lock"))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("rl_001"));
	}

	@Test
	@DisplayName("[일반 검증 예외는 기존대로 400으로 응답한다]")
	void validationException_returnsBadRequest() throws Exception {
		mockMvc.perform(get("/validation"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(BiddingErrorCode.BIDDING_PRICE_NOT_HIGH_ENOUGH.getCode()));
	}

	@RestController
	static class ThrowingController {

		@GetMapping("/lock")
		String lock() {
			throw new LockAcquisitionException();
		}

		@GetMapping("/validation")
		String validation() {
			throw new ValidationException(BiddingErrorCode.BIDDING_PRICE_NOT_HIGH_ENOUGH);
		}
	}
}
