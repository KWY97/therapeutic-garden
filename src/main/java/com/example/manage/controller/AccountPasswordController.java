package com.example.manage.controller;

import com.example.manage.service.AccountPasswordService;
import com.example.manage.service.AccountPasswordService.Result;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class AccountPasswordController {
    private final AccountPasswordService passwords;

    @GetMapping("/member/password")
    public String memberForm() {
        return "member/password";
    }

    @PostMapping("/member/password")
    public String changeMember(@RequestParam(required = false) String currentPassword,
                               @RequestParam(required = false) String newPassword,
                               @RequestParam(required = false) String confirmPassword,
                               HttpSession session, Model model) {
        Result result = passwords.changeMember((Long) session.getAttribute("loginMemberId"),
                currentPassword, newPassword, confirmPassword);
        return finish(result, session, model, "member");
    }

    @GetMapping("/admin/password")
    public String adminForm() {
        return "admin/password";
    }

    @PostMapping("/admin/password")
    public String changeAdmin(@RequestParam(required = false) String currentPassword,
                              @RequestParam(required = false) String newPassword,
                              @RequestParam(required = false) String confirmPassword,
                              HttpSession session, Model model) {
        Result result = passwords.changeAdmin((Long) session.getAttribute("loginAdminId"),
                currentPassword, newPassword, confirmPassword);
        return finish(result, session, model, "admin");
    }

    private String finish(Result result, HttpSession session, Model model, String role) {
        if (result == Result.SUCCESS || result == Result.ACCOUNT_NOT_FOUND) {
            session.invalidate();
            return "redirect:/" + role + "/login" + (result == Result.SUCCESS ? "?passwordChanged=1" : "");
        }
        model.addAttribute("errorMessage", switch (result) {
            case INCORRECT_CURRENT -> "현재 비밀번호가 올바르지 않습니다.";
            case TOO_SHORT -> "새 비밀번호는 12자 이상이어야 합니다.";
            case TOO_LONG -> "새 비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.";
            case CONFIRMATION_MISMATCH -> "새 비밀번호 확인이 일치하지 않습니다.";
            case REUSED -> "현재 비밀번호와 다른 비밀번호를 입력해 주세요.";
            default -> throw new IllegalStateException("Unexpected password result");
        });
        return role + "/password";
    }
}
