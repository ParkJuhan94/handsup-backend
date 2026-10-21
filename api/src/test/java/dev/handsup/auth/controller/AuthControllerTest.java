package dev.handsup.auth.controller;

import static org.hamcrest.Matchers.*;
import static org.springframework.http.HttpHeaders.*;
import static org.springframework.http.MediaType.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.result.MockMvcResultHandlers;
import org.springframework.transaction.annotation.Transactional;

import dev.handsup.auth.dto.request.LoginRequest;
import dev.handsup.auth.dto.response.LoginDetailResponse;
import dev.handsup.common.support.ApiTestSupport;

@Tag("integration")
@DisplayName("[Auth 통합 테스트]")
class AuthControllerTest extends ApiTestSupport {

    private LoginRequest loginRequest;

    @BeforeEach
    void setUp() {
        loginRequest = LoginRequest.of(user.getEmail(), user.getPassword());
    }

    @Test
    @Transactional
    @DisplayName("[로그인 API를 호출하면 토큰이 응답된다]")
    void loginTest() throws Exception {
        // when
        ResultActions actions = mockMvc.perform(
            post("/api/auth/login")
                .contentType(APPLICATION_JSON)
                .content(toJson(loginRequest))
        );

        // then
        actions.andExpect(status().isOk())
            .andDo(MockMvcResultHandlers.print())
            .andExpect(jsonPath("$.accessToken").exists())
            .andExpect(cookie().exists("refreshToken"))
            .andExpect(cookie().value("refreshToken", not(emptyOrNullString())))
            .andExpect(cookie().secure("refreshToken", true))
            .andExpect(cookie().httpOnly("refreshToken", true))
            .andExpect(cookie().sameSite("refreshToken", "Lax"));
    }

    @Test
    @Transactional
    @DisplayName("[토큰 재발급 API를 호출하면 새로운 엑세스 토큰이 응답된다]")
    void reIssueAccessTokenTest() throws Exception {
        // given
        LoginDetailResponse loginDetailResponse = authService.login(loginRequest);
        String refreshToken = loginDetailResponse.refreshToken();

        // when
        ResultActions actions = mockMvc.perform(
            post("/api/auth/token")
                .contentType(APPLICATION_JSON)
                .cookie(new Cookie("refreshToken", refreshToken))
        );

        // then
        actions.andExpect(status().isOk())
            .andDo(MockMvcResultHandlers.print())
            .andExpect(content().string(not(emptyOrNullString())));
    }

    @Test
    @Transactional
    @DisplayName("[로그아웃 API를 호출하면 200 OK 응답이 반환된다]")
    void logoutTest() throws Exception {
        // given
        LoginDetailResponse loginDetailResponse = authService.login(loginRequest);
        String accessToken = loginDetailResponse.accessToken();

        // when
        ResultActions actions = mockMvc.perform(
            post("/api/auth/logout")
                .header(AUTHORIZATION, "Bearer " + accessToken)
        );

        // then
        actions.andExpect(status().isOk())
            .andDo(MockMvcResultHandlers.print());
    }

    @Test
    @Transactional
    @DisplayName("[재발급하면 refresh 토큰이 교체되고, 이전 토큰으로는 다시 재발급할 수 없다]")
    void reIssueRotatesRefreshTokenTest() throws Exception {
        // given
        String oldRefreshToken = authService.login(loginRequest).refreshToken();

        // when: 첫 재발급은 성공하고 새 refresh 쿠키가 내려온다
        ResultActions first = mockMvc.perform(
            post("/api/auth/token").cookie(new Cookie("refreshToken", oldRefreshToken))
        );
        first.andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").exists())
            .andExpect(cookie().value("refreshToken", not(oldRefreshToken)));

        // then: 이미 사용한 토큰으로 다시 요청하면 실패한다
        mockMvc.perform(post("/api/auth/token").cookie(new Cookie("refreshToken", oldRefreshToken)))
            .andExpect(status().is4xxClientError());
    }

    @Test
    @Transactional
    @DisplayName("[서명이 틀린 refresh 토큰으로는 재발급할 수 없다]")
    void reIssueWithForgedRefreshTokenTest() throws Exception {
        // given
        String refreshToken = authService.login(loginRequest).refreshToken();
        String forged = refreshToken.substring(0, refreshToken.length() - 4) + "abcd";

        // when & then
        mockMvc.perform(post("/api/auth/token").cookie(new Cookie("refreshToken", forged)))
            .andExpect(status().is4xxClientError());
    }

    @Test
    @Transactional
    @DisplayName("[로그아웃하면 refresh 쿠키가 만료되고 그 토큰으로 재발급할 수 없다]")
    void logoutBlocksReIssueTest() throws Exception {
        // given
        LoginDetailResponse login = authService.login(loginRequest);

        // when
        mockMvc.perform(
                post("/api/auth/logout").header(AUTHORIZATION, "Bearer " + login.accessToken())
            )
            .andExpect(status().isOk())
            .andExpect(cookie().maxAge("refreshToken", 0));

        // then
        mockMvc.perform(post("/api/auth/token").cookie(new Cookie("refreshToken", login.refreshToken())))
            .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("[만료되거나 변조된 access 토큰은 401로 응답한다]")
    void invalidAccessTokenReturns401Test() throws Exception {
        mockMvc.perform(post("/api/auth/logout").header(AUTHORIZATION, "Bearer invalid.token.value"))
            .andExpect(status().isUnauthorized());
    }
}
