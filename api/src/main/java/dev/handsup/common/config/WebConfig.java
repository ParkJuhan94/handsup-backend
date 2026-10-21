package dev.handsup.common.config;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import dev.handsup.auth.web.JwtAuthorizationArgumentResolver;
import dev.handsup.auth.web.JwtInterceptor;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final JwtAuthorizationArgumentResolver jwtAuthorizationArgumentResolver;
    private final JwtInterceptor jwtInterceptor;

    // 쉼표로 여러 출처를 줄 수 있다. 값은 application.yml(로컬)·application-prod.yml(운영)의 cors.allowed-origins에 있다.
    // 필요하면 CORS_ALLOWED_ORIGINS 환경변수로도 덮어쓸 수 있다.
    @Value("${cors.allowed-origins:http://localhost:8080,https://localhost:8080}")
    private String[] allowedOrigins;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
        argumentResolvers.add(jwtAuthorizationArgumentResolver);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
            .addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedOrigins(allowedOrigins)
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true); // 쿠키/인증 헤더 사용 허용
    }
}
