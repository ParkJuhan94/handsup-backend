package dev.handsup.auth.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.handsup.auth.domain.Auth;

public interface AuthRepository extends JpaRepository<Auth, Long> {
	Optional<Auth> findByRefreshToken(String refreshToken);

	Optional<Auth> findByUserId(Long userId);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Auth a SET a.refreshToken = :refreshToken where a.id = :id")
	void updateRefreshToken(@Param("id") Long id, @Param("refreshToken") String refreshToken);

	// 현재 저장된 refresh 토큰이 expected일 때만 교체한다. 같은 토큰으로 동시에 재발급이 와도 한 요청만 성공한다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Auth a SET a.refreshToken = :newToken where a.id = :id and a.refreshToken = :expected")
	int rotateRefreshToken(
		@Param("id") Long id,
		@Param("expected") String expected,
		@Param("newToken") String newToken
	);
}
