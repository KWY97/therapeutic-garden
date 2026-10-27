package com.example.manage.config;

import com.example.manage.interceptor.AdminLoginInterceptor;
import com.example.manage.interceptor.MemberLoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import lombok.RequiredArgsConstructor;
import com.example.manage.service.SessionAuthentication;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    private final SessionAuthentication authentication;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminLoginInterceptor(authentication))
                .addPathPatterns("/admin/**")
                .excludePathPatterns(
                        "/admin/login",
                        "/admin/logout"
                );

        registry.addInterceptor(new MemberLoginInterceptor(authentication))
                .addPathPatterns("/member/**")
                .excludePathPatterns(
                        "/member/login",
                        "/member/logout"
                );
    }
}
