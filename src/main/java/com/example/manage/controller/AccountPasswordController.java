package com.example.manage.controller;

import com.example.manage.service.AccountPasswordService;
import com.example.manage.service.AccountPasswordService.Result;
import com.example.manage.dto.MemberProfileView;
import com.example.manage.service.MemberService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class AccountPasswordController {
    private final AccountPasswordService passwords;
    private final MemberService members;

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

    @GetMapping("/admin/members/{memberId}/password")
    public String memberResetForm(@PathVariable Long memberId, HttpSession session, Model model) {
        if (!passwords.adminExists((Long) session.getAttribute("loginAdminId"))) {
            session.invalidate();
            return "redirect:/admin/login";
        }
        MemberProfileView member = members.findProfile(memberId);
        if (member == null) return "redirect:/admin/members";
        model.addAttribute("member", member);
        return "admin/member-password-reset";
    }

    @PostMapping("/admin/members/{memberId}/password")
    public String resetMember(@PathVariable Long memberId,
                              @RequestParam(required = false) String newPassword,
                              @RequestParam(required = false) String confirmPassword,
                              HttpSession session, Model model, RedirectAttributes redirectAttributes) {
        Result result = passwords.resetMemberByAdmin((Long) session.getAttribute("loginAdminId"),
                memberId, newPassword, confirmPassword);
        if (result == Result.ADMIN_NOT_FOUND) {
            session.invalidate();
            return "redirect:/admin/login";
        }
        if (result == Result.ACCOUNT_NOT_FOUND) return "redirect:/admin/members";
        if (result == Result.SUCCESS) {
            redirectAttributes.addFlashAttribute("successMessage", "참가자 비밀번호를 변경했습니다.");
            return "redirect:/admin/members/" + memberId + "/edit";
        }
        MemberProfileView member = members.findProfile(memberId);
        if (member == null) return "redirect:/admin/members";
        model.addAttribute("member", member);
        model.addAttribute("errorMessage", switch (result) {
            case TOO_SHORT -> "새 비밀번호는 8자 이상이어야 합니다.";
            case TOO_LONG -> "새 비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.";
            case CONFIRMATION_MISMATCH -> "새 비밀번호 확인이 일치하지 않습니다.";
            case REUSED -> "기존 비밀번호와 다른 비밀번호를 입력해 주세요.";
            default -> throw new IllegalStateException("Unexpected password reset result");
        });
        return "admin/member-password-reset";
    }

    private String finish(Result result, HttpSession session, Model model, String role) {
        if (result == Result.SUCCESS || result == Result.ACCOUNT_NOT_FOUND) {
            session.invalidate();
            return "redirect:/" + role + "/login" + (result == Result.SUCCESS ? "?passwordChanged=1" : "");
        }
        model.addAttribute("errorMessage", switch (result) {
            case INCORRECT_CURRENT -> "현재 비밀번호가 올바르지 않습니다.";
            case TOO_SHORT -> "새 비밀번호는 " + (role.equals("member") ? "8" : "12") + "자 이상이어야 합니다.";
            case TOO_LONG -> "새 비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.";
            case CONFIRMATION_MISMATCH -> "새 비밀번호 확인이 일치하지 않습니다.";
            case REUSED -> "현재 비밀번호와 다른 비밀번호를 입력해 주세요.";
            default -> throw new IllegalStateException("Unexpected password result");
        });
        return role + "/password";
    }
}
