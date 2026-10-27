package com.example.manage.controller;

import com.example.manage.domain.Member;
import com.example.manage.dto.ScheduleResponse;
import com.example.manage.dto.MemberScheduleDetailResponse;
import com.example.manage.dto.MemberProfileView;
import com.example.manage.dto.MemberScheduleForm;
import com.example.manage.dto.SiteResponse;
import com.example.manage.dto.WeatherResult;
import com.example.manage.service.MemberService;
import com.example.manage.service.ScheduleService;
import com.example.manage.service.SiteService;
import com.example.manage.service.WeatherService;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.time.LocalTime;

@Controller
@RequiredArgsConstructor
@RequestMapping("/member")
public class MemberController {

    private final MemberService memberService;
    private final ScheduleService scheduleService;
    private final SiteService siteService;
    private final WeatherService weatherService;
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

    @GetMapping("/schedules")
    public String scheduleList(HttpSession session, Model model) {
        Long memberId = (Long) session.getAttribute("loginMemberId");
        model.addAttribute("schedules", scheduleService.findScheduleByMemberId(memberId));
        model.addAttribute("memberMode", true);
        return "admin/schedule-list";
    }

    private String renderScheduleForm(Model model) {
        model.addAttribute("memberMode", true);
        model.addAttribute("sites", siteService.findAllSites().stream().map(SiteResponse::new).toList());
        return "admin/schedule-form";
    }

    @GetMapping("/schedules/new")
    public String newSchedule(HttpSession session, Model model) {
        Long memberId = (Long) session.getAttribute("loginMemberId");
        if (memberService.findMember(memberId) == null) {
            session.invalidate();
            return "redirect:/member/login";
        }
        model.addAttribute("scheduleForm", new MemberScheduleForm());
        return renderScheduleForm(model);
    }

    @PostMapping("/schedules/new")
    public String createSchedule(@Valid @ModelAttribute("scheduleForm") MemberScheduleForm form,
                                 BindingResult bindingResult, HttpSession session, Model model) {
        Long memberId = (Long) session.getAttribute("loginMemberId");
        if (memberService.findMember(memberId) == null) {
            session.invalidate();
            return "redirect:/member/login";
        }
        if (bindingResult.hasErrors()) return renderScheduleForm(model);

        try {
            scheduleService.createSchedule(memberId, form.getScheduleDate(), form.getSiteId(),
                    form.getCourseId(), form.getFirstSpotId(), form.getFirstStartTime(),
                    form.getSecondSpotId(), form.getSecondStartTime(), form.getWeather(),
                    form.getTemperature(), form.getHumidity());
        } catch (ScheduleService.DuplicateScheduleException exception) {
            bindingResult.reject("duplicateSchedule", exception.getMessage());
            return renderScheduleForm(model);
        } catch (IllegalArgumentException exception) {
            bindingResult.reject("invalidSchedule", exception.getMessage());
            return renderScheduleForm(model);
        }

        return "redirect:/member/schedules";
    }

    @GetMapping("/weather")
    @ResponseBody
    public ResponseEntity<?> getWeather(@RequestParam LocalDate date, @RequestParam LocalTime time) {
        try {
            WeatherResult result = weatherService.getWeather(date, time);
            if (result == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "message", "선택한 날짜의 날씨 데이터가 제공되지 않습니다. 다른 날짜를 선택해 주세요."
                ));
            }
            return ResponseEntity.ok(result);
        } catch (WeatherService.WeatherDateOutOfRangeException exception) {
            return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
        }
    }

    @GetMapping("/schedules/{scheduleId}")
    public String scheduleDetail(@PathVariable Long scheduleId, HttpSession session, Model model) {
        Long memberId = (Long) session.getAttribute("loginMemberId");
        ScheduleResponse schedule = scheduleService.findMemberScheduleResponse(scheduleId, memberId);
        if (schedule == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);

        model.addAttribute("schedule", schedule);
        model.addAttribute("scheduleSpots", schedule.getSpots());
        model.addAttribute("memberMode", true);
        return "admin/schedule-detail";
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
