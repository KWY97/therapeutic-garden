package com.example.manage.controller;

import com.example.manage.domain.Member;
import com.example.manage.dto.ScheduleResponse;
import com.example.manage.dto.MemberScheduleDetailResponse;
import com.example.manage.dto.MemberProfileView;
import com.example.manage.service.MemberService;
import com.example.manage.service.ScheduleService;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/member")
public class MemberController {

    private final MemberService memberService;
    private final ScheduleService scheduleService;
    private final com.example.manage.service.MemberAnalysisModelService analysisModel;

    @GetMapping("/login")
    public String memberLogin() {
        return "member/login";
    }

    @PostMapping("/login")
    public String memberLogin(
            @RequestParam String loginId,
            @RequestParam String password,
            HttpSession session,
            Model model
    ) {
        Member member = memberService.login(loginId, password);

        if (member == null) {
            model.addAttribute("errorMessage", "아이디 또는 비밀번호가 일치하지 않습니다.");
            return "member/login";
        }

        session.removeAttribute("loginAdminId");
        session.setAttribute("loginMemberId", member.getMemberId());
        session.setAttribute("loginMemberName", member.getName());

        return "redirect:/member";
    }

    @GetMapping
    public String memberHome(
            HttpSession session,
            Model model,
            HttpServletResponse response
    ) {
        Long memberId = (Long) session.getAttribute("loginMemberId");

        List<ScheduleResponse> schedules = scheduleService.findScheduleByMemberId(memberId);
        String memberName = (String) session.getAttribute("loginMemberName");
        if ((memberName == null || memberName.isBlank()) && !schedules.isEmpty()) {
            memberName = schedules.getFirst().getMember().getName();
        }
        if (memberName == null || memberName.isBlank()) memberName = "참가자";

        model.addAttribute("schedules", schedules);
        try {
            analysisModel.populateForLoggedInMember(memberId, memberName, model);
        } catch (IllegalArgumentException exception) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "member/analysis-unavailable";
        }
        return "member/home";
    }

    @GetMapping("/logout")
    public String memberLogout(HttpSession session) {

        session.invalidate();

        return "redirect:/";
    }

    @GetMapping("/mypage")
    public String myPage(HttpSession session, Model model) {
        Long memberId = (Long) session.getAttribute("loginMemberId");
        Member member = memberService.findMember(memberId);
        if (member == null) {
            session.invalidate();
            return "redirect:/member/login";
        }
        MemberProfileView profile = new MemberProfileView(member);
        model.addAttribute("member", profile);
        model.addAttribute("testAccount", member.isTestAccount());
        model.addAttribute("formattedPhone", profile.getFormattedPhone());
        return "member/mypage";
    }

    @GetMapping("/api/schedules/{scheduleId}")
    @ResponseBody
    public ResponseEntity<MemberScheduleDetailResponse> getScheduleDetail(
            @PathVariable Long scheduleId,
            HttpSession session) {

        Long memberId = (Long) session.getAttribute("loginMemberId");

        MemberScheduleDetailResponse response = scheduleService.findMemberScheduleDetail(scheduleId, memberId);

        if (response == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(response);
    }
}
