package dev.handsup.auth.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import dev.handsup.user.domain.User;
import dev.handsup.user.service.UserService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final UserService userService;
	private final JwtProvider jwtProvider;
	private final AuthRepository authRepository;
	private final EncryptHelper encryptHelper;
	private final BlacklistTokenRepository blacklistTokenRepository;

	private Auth getAuthByRefreshToken(String refreshToken) {
		return authRepository.findByRefreshToken(refreshToken)
			.orElseThrow(() -> new NotFoundException(AuthErrorCode.NOT_FOUND_REFRESH_TOKEN));
	}

	private LoginDetailResponse saveAuth(Long userId) {
		String refreshToken = jwtProvider.createRefreshToken(userId);
		String accessToken = jwtProvider.createAccessToken(userId);
		Optional<Auth> auth = authRepository.findByUserId(userId);

		auth.ifPresentOrElse(
			existingAuth -> authRepository.updateRefreshToken(existingAuth.getId(), refreshToken),
			() -> {
				Auth newAuth = Auth.builder()
					.userId(userId)
					.refreshToken(refreshToken)
					.build();

				authRepository.save(newAuth);
			}
		);

		return LoginDetailResponse.of(refreshToken, accessToken);
	}

	@Transactional
	public LoginDetailResponse login(LoginRequest request) {
		Long userId = userService.getUserByEmail(request.email()).getId();
		LoginDetailResponse response = saveAuth(userId);
		String plainPassword = request.password();
		String hashedPassword = userService.getUserById(userId).getPassword();

		if (encryptHelper.isMatch(plainPassword, hashedPassword)) {
			return response;
		}
		throw new NotFoundException(AuthErrorCode.FAILED_LOGIN_BY_ANYTHING);
	}

	@Transactional
	public void logout(User user) {
		Auth auth = authRepository.findByUserId(user.getId())
			.orElseThrow(() -> new NotFoundException(AuthErrorCode.NOT_FOUND_USER_ID));
		String refreshToken = auth.getRefreshToken();

		if (!blacklistTokenRepository.existsByRefreshToken(refreshToken)) {
			blacklistTokenRepository.save(BlacklistToken.from(refreshToken));
		}
	}

	// refresh 토큰을 회전한다: 서명·만료 검증 -> 블랙리스트 확인 -> 새 토큰으로 교체 -> 이전 토큰을 블랙리스트에 등록
	@Transactional
	public LoginDetailResponse reIssueTokens(String refreshToken) {
		jwtProvider.validateToken(refreshToken);

		if (blacklistTokenRepository.existsByRefreshToken(refreshToken)) {
			throw new ValidationException(AuthErrorCode.BLACKLISTED_TOKEN);
		}

		Auth auth = getAuthByRefreshToken(refreshToken);
		Long userId = userService.getUserById(auth.getUserId()).getId();
		String newRefreshToken = jwtProvider.createRefreshToken(userId);

		int rotated = authRepository.rotateRefreshToken(auth.getId(), refreshToken, newRefreshToken);
		if (rotated == 0) {
			// 동시에 들어온 다른 요청이 먼저 이 토큰을 교체했다
			throw new ValidationException(AuthErrorCode.BLACKLISTED_TOKEN);
		}
		blacklistTokenRepository.save(BlacklistToken.from(refreshToken));

		return LoginDetailResponse.of(newRefreshToken, jwtProvider.createAccessToken(userId));
	}

}
