package dev.handsup.auth.service;

import static dev.handsup.auth.exception.AuthErrorCode.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.handsup.auth.domain.Auth;
import dev.handsup.auth.domain.BlacklistToken;
import dev.handsup.auth.domain.EncryptHelper;
import dev.handsup.auth.dto.request.LoginRequest;
import dev.handsup.auth.dto.response.LoginDetailResponse;
import dev.handsup.auth.exception.AuthErrorCode;
import dev.handsup.auth.repository.AuthRepository;
import dev.handsup.auth.repository.BlacklistTokenRepository;
import dev.handsup.common.exception.NotFoundException;
import dev.handsup.common.exception.ValidationException;
import dev.handsup.fixture.UserFixture;
import dev.handsup.user.domain.User;
import dev.handsup.user.service.UserService;

@DisplayName("[AuthService 테스트]")
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	private final User user = UserFixture.user1();
	private final LoginRequest loginRequest = LoginRequest.of(
		user.getEmail(), user.getPassword()
	);
	@InjectMocks
	private AuthService authService;
	@Mock
	private AuthRepository authRepository;
	@Mock
	private UserService userService;
	@Mock
	private JwtProvider jwtProvider;
	@Mock
	private EncryptHelper encryptHelper;
	@Mock
	private BlacklistTokenRepository blacklistTokenRepository;

	@Test
	@DisplayName("[로그인 성공 시 토큰을 발급한다]")
	void loginSuccessTest() {
		// given
		when(userService.getUserByEmail(loginRequest.email())).thenReturn(user);
		when(userService.getUserById(1L)).thenReturn(user);
		when(encryptHelper.isMatch(anyString(), anyString())).thenReturn(true);
		Auth anyAuth = new Auth();
		when(authRepository.findByUserId(user.getId())).thenReturn(Optional.of(anyAuth));
		when(jwtProvider.createAccessToken(anyLong())).thenReturn("access-token");
		when(jwtProvider.createRefreshToken(anyLong())).thenReturn("refresh-token");

		// when
		LoginDetailResponse loginDetailResponse = authService.login(loginRequest);

		// then
		assertEquals("access-token", loginDetailResponse.accessToken());
		assertEquals("refresh-token", loginDetailResponse.refreshToken());
	}

	@Test
	@DisplayName("[로그인 실패 시 예외를 던진다]")
	void loginFailTest() {
		// given
		when(userService.getUserByEmail(loginRequest.email())).thenReturn(user);
		when(userService.getUserById(1L)).thenReturn(user);
		when(encryptHelper.isMatch(anyString(), anyString())).thenReturn(false);

		// when & then
		assertThatThrownBy(() -> authService.login(loginRequest))
			.isInstanceOf(NotFoundException.class)
			.hasMessageContaining(AuthErrorCode.FAILED_LOGIN_BY_ANYTHING.getMessage());
	}

	@Test
	@DisplayName("[로그아웃 성공 시 블랙리스트에 토큰을 추가한다]")
	void logoutSuccessTest() {
		// given
		Long userId = 1L;
		Auth auth = Auth.of(userId, "refresh-token");
		when(authRepository.findByUserId(userId)).thenReturn(Optional.of(auth));

		// when
		authService.logout(user);

		// then
		verify(blacklistTokenRepository).save(any(BlacklistToken.class));
	}

	@Test
	@DisplayName("[로그아웃 실패 시 NotFoundException을 던진다]")
	void logoutFailTest() {
		// given
		Long userId = 1L;
		when(authRepository.findByUserId(userId)).thenReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> authService.logout(user))
			.isInstanceOf(NotFoundException.class)
			.hasMessageContaining(NOT_FOUND_USER_ID.getMessage());
	}

	@Test
	@DisplayName("[이미 블랙리스트에 있는 토큰이면 로그아웃을 다시 호출해도 중복 저장하지 않는다]")
	void logoutTwiceTest() {
		// given
		Auth auth = Auth.of(1L, "refresh-token");
		when(authRepository.findByUserId(1L)).thenReturn(Optional.of(auth));
		when(blacklistTokenRepository.existsByRefreshToken("refresh-token")).thenReturn(true);

		// when
		authService.logout(user);

		// then
		verify(blacklistTokenRepository, never()).save(any(BlacklistToken.class));
	}

	@Test
	@DisplayName("[재발급하면 서명을 검증하고 refresh 토큰을 교체한 뒤 이전 토큰을 블랙리스트에 올린다]")
	void reIssueTokensSuccessTest() {
		// given
		Auth auth = Auth.of(1L, "old-refresh");
		when(blacklistTokenRepository.existsByRefreshToken("old-refresh")).thenReturn(false);
		when(authRepository.findByRefreshToken("old-refresh")).thenReturn(Optional.of(auth));
		when(userService.getUserById(auth.getUserId())).thenReturn(user);
		when(jwtProvider.createRefreshToken(user.getId())).thenReturn("new-refresh");
		when(jwtProvider.createAccessToken(user.getId())).thenReturn("new-access");
		when(authRepository.rotateRefreshToken(auth.getId(), "old-refresh", "new-refresh")).thenReturn(1);

		// when
		LoginDetailResponse response = authService.reIssueTokens("old-refresh");

		// then
		assertEquals("new-access", response.accessToken());
		assertEquals("new-refresh", response.refreshToken());
		verify(jwtProvider).validateToken("old-refresh");
		verify(blacklistTokenRepository).save(any(BlacklistToken.class));
	}

	@Test
	@DisplayName("[서명이 틀리거나 만료된 토큰이면 DB를 조회하기 전에 실패한다]")
	void reIssueTokensInvalidTokenTest() {
		// given
		doThrow(new ValidationException(TOKEN_EXPIRED)).when(jwtProvider).validateToken("expired");

		// when & then
		assertThatThrownBy(() -> authService.reIssueTokens("expired"))
			.isInstanceOf(ValidationException.class)
			.hasMessageContaining(TOKEN_EXPIRED.getMessage());
		verifyNoInteractions(authRepository);
	}

	@Test
	@DisplayName("[블랙리스트에 있는 토큰으로는 재발급할 수 없다]")
	void reIssueTokensBlacklistedTest() {
		// given
		when(blacklistTokenRepository.existsByRefreshToken("used")).thenReturn(true);

		// when & then
		assertThatThrownBy(() -> authService.reIssueTokens("used"))
			.isInstanceOf(ValidationException.class)
			.hasMessageContaining(BLACKLISTED_TOKEN.getMessage());
		verify(authRepository, never()).rotateRefreshToken(anyLong(), anyString(), anyString());
	}

	@Test
	@DisplayName("[동시에 들어온 요청이 먼저 토큰을 교체했다면 재발급에 실패한다]")
	void reIssueTokensRaceTest() {
		// given
		Auth auth = Auth.of(1L, "old-refresh");
		when(authRepository.findByRefreshToken("old-refresh")).thenReturn(Optional.of(auth));
		when(userService.getUserById(auth.getUserId())).thenReturn(user);
		when(jwtProvider.createRefreshToken(user.getId())).thenReturn("new-refresh");
		when(authRepository.rotateRefreshToken(auth.getId(), "old-refresh", "new-refresh")).thenReturn(0);

		// when & then
		assertThatThrownBy(() -> authService.reIssueTokens("old-refresh"))
			.isInstanceOf(ValidationException.class)
			.hasMessageContaining(BLACKLISTED_TOKEN.getMessage());
		verify(blacklistTokenRepository, never()).save(any(BlacklistToken.class));
	}
}
