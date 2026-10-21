package dev.handsup.auth.web;

import static dev.handsup.auth.dto.AuthMapper.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import dev.handsup.auth.annotation.NoAuth;
import dev.handsup.auth.service.JwtProvider;
import dev.handsup.common.exception.NotFoundException;
import dev.handsup.common.exception.ValidationException;

@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtProvider jwtProvider;

    private boolean isPresentAnnotation(Object handler) {
        HandlerMethod handlerMethod = (HandlerMethod) handler;
        return handlerMethod.getMethodAnnotation(NoAuth.class) != null;
    }

    @Override
    public boolean preHandle(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull Object handler
    ) {
        try {
            if (request.getMethod().equals("OPTIONS")) {
                return true;
            }

            if (isPresentAnnotation(handler)) {
                return true;
            }

            String accessToken = toAccessToken(request);
            jwtProvider.validateToken(accessToken);
            return true;
        } catch (NotFoundException | ValidationException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 토큰 없음·만료·변조는 401
            return false;
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); // 500 상태 코드 설정
            return false;
        }
    }
}
