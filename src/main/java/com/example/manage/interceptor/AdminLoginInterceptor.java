package com.example.manage.interceptor;

import com.example.manage.service.SessionAuthentication;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

public class AdminLoginInterceptor implements HandlerInterceptor {
    private final SessionAuthentication authentication;

    public AdminLoginInterceptor(SessionAuthentication authentication) {
        this.authentication = authentication;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 기존 세션이 있으면 가져오고 없으면 null 반환
        HttpSession session = request.getSession(false);

        if (authentication.role(session) != SessionAuthentication.Role.ADMIN) {
            if (handler instanceof HandlerMethod method && (AnnotatedElementUtils.hasAnnotation(method.getBeanType(), RestController.class)
                    || AnnotatedElementUtils.hasAnnotation(method.getMethod(), ResponseBody.class)
                    || ResponseEntity.class.isAssignableFrom(method.getMethod().getReturnType()))) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            } else {
                response.sendRedirect("/admin/login");
            }
            return false;
        }

        return true;
    }
}
