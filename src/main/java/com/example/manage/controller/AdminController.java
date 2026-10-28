package com.example.manage.controller;

import com.example.manage.domain.Admin;
import com.example.manage.service.AdminService;
import com.example.manage.service.LoginAttemptLimiter;
import com.example.manage.service.SessionAuthentication;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;


@Controller
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminController {

    private final AdminService adminService;
    private final LoginAttemptLimiter loginAttempts;
    private final SessionAuthentication sessionAuthentication;

    @GetMapping("/login")
    public String adminLogin() {
        return "admin/login";
    }


    @PostMapping("/login")
    public String adminLogin(
            @RequestParam String loginId,
            @RequestParam String password,
            HttpServletRequest request,
            Model model
    ) {

        if (!loginAttempts.allow(LoginAttemptLimiter.Role.ADMIN, loginId, request.getRemoteAddr())) {
            model.addAttribute("errorMessage", "로그인 시도가 잠시 제한되었습니다. 잠시 후 다시 시도해 주세요.");
            return "admin/login";
        }

        Admin admin =
                adminService.login(loginId, password);

        if (admin == null) {

            model.addAttribute(
                    "errorMessage",
                    "아이디 또는 비밀번호가 올바르지 않습니다."
            );

            return "admin/login";
        }

        HttpSession session = request.getSession(false);
        if (session == null) {
            session = request.getSession();
        } else {
            request.changeSessionId();
        }
        sessionAuthentication.authenticateAdmin(session, admin);
        loginAttempts.clearOnSuccess(LoginAttemptLimiter.Role.ADMIN, loginId, request.getRemoteAddr());

        return "redirect:/admin/monitoring";
    }


    @GetMapping
    public String adminHome(
            HttpSession session
    ) {
        return "admin/home";
    }


    @GetMapping("/logout")
    public String adminLogout(
            HttpSession session
    ) {

        session.invalidate();

        return "redirect:/";
    }
}
