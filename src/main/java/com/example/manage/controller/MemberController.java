package com.example.manage.controller;

import com.example.manage.domain.Member;
import com.example.manage.dto.ScheduleResponse;
import com.example.manage.dto.MemberScheduleDetailResponse;
import com.example.manage.service.MemberService;
import com.example.manage.service.ScheduleService;
import jakarta.servlet.http.HttpSession;
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
    private final com.example.manage.service.HealingEffectQueryService healingEffects;
    private final com.example.manage.service.MeasurementHistoryQueryService measurementHistory;

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
            Model model
    ) {
        Long memberId = (Long) session.getAttribute("loginMemberId");

        List<ScheduleResponse> schedules = scheduleService.findScheduleByMemberId(memberId);
        String memberName = (String) session.getAttribute("loginMemberName");
        if ((memberName == null || memberName.isBlank()) && !schedules.isEmpty()) {
            memberName = schedules.getFirst().getMember().getName();
        }
        if (memberName == null || memberName.isBlank()) memberName = "참가자";

        var spotEffects = healingEffects.findImportedMemberImprovements(memberId);
        model.addAttribute("schedules", schedules);
        model.addAttribute("spotEffects", spotEffects);
        model.addAttribute("maximumImprovements", healingEffects.findMaximumImprovements(spotEffects));
        model.addAttribute("memberDisplayName", memberName);
        model.addAttribute("overallImprovement", healingEffects.findImportedMemberOverallImprovement(memberId).orElse(null));
        model.addAttribute("measurementHistory", measurementHistory.findImportedMember(memberId));
        return "member/home";
    }

    @GetMapping("/logout")
    public String memberLogout(HttpSession session) {

        session.invalidate();

        return "redirect:/";
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
