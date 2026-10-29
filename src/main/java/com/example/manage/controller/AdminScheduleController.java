package com.example.manage.controller;

import com.example.manage.domain.Member;
import com.example.manage.domain.Schedule;
import com.example.manage.dto.ScheduleForm;
import com.example.manage.dto.ScheduleResponse;
import com.example.manage.dto.SiteResponse;
import com.example.manage.service.MemberService;
import com.example.manage.service.ScheduleService;
import com.example.manage.service.SiteService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminScheduleController {
    private final MemberService memberService;
    private final ScheduleService scheduleService;
    private final SiteService siteService;
    private final WeatherLookupResponse weatherResponses;

    private void addScheduleSites(Model model) {
        model.addAttribute("sites", siteService.findAllSites().stream().map(SiteResponse::new).toList());
    }

    /* ================================
       일정 등록 화면
    ================================= */

    @GetMapping("/schedules/new")
    public String scheduleForm(
            Model model
    ) {

        addScheduleSites(model);

        List<Member> members =
                memberService.findAllMembers();

        model.addAttribute(
                "members",
                members
        );

        model.addAttribute(
                "scheduleForm",
                new ScheduleForm()
        );

        return "admin/schedule-form";
    }

    /* ================================
       일정 등록
    ================================= */

    @PostMapping("/schedules/new")
    public String createSchedule(
            @Valid ScheduleForm form,
            BindingResult bindingResult,
            Model model
    ) {

        if (bindingResult.hasErrors()) {

            addScheduleSites(model);

            List<Member> members =
                    memberService.findAllMembers();

            model.addAttribute(
                    "members",
                    members
            );

            return "admin/schedule-form";
        }

        try {
            scheduleService.createSchedule(
                    form.getMemberId(),
                    form.getScheduleDate(),
                    form.getSiteId(),
                    form.getCourseId(),
                    form.getFirstSpotId(),
                    form.getFirstStartTime(),
                    form.getSecondSpotId(),
                    form.getSecondStartTime(),
                    form.getWeather(),
                    form.getTemperature(),
                    form.getHumidity()
            );
        } catch (ScheduleService.DuplicateScheduleException exception) {
            bindingResult.reject("duplicateSchedule", exception.getMessage());
            addScheduleSites(model);
            model.addAttribute("members", memberService.findAllMembers());
            return "admin/schedule-form";
        } catch (IllegalArgumentException exception) {
            bindingResult.rejectValue("firstSpotId", "invalid", exception.getMessage());
            addScheduleSites(model);
            model.addAttribute("members", memberService.findAllMembers());
            return "admin/schedule-form";
        }

        return "redirect:/admin";
    }

    /* ================================
       일정 목록
    ================================= */

    @GetMapping("/schedules")
    public String scheduleList(
            @RequestParam(
                    defaultValue = "date"
            ) String sort,
            Model model
    ) {

        /*
         * 정렬 기준
         *
         * date  = 일정 날짜순
         * group = 그룹순
         *
         * sort 파라미터가 없으면
         * 기본값은 date이다.
         */
        List<ScheduleResponse> schedules =
                scheduleService.findAllSchedules(sort);

        model.addAttribute(
                "schedules",
                schedules
        );

        /*
         * 현재 선택한 정렬 기준을
         * HTML select에 다시 전달한다.
         */
        model.addAttribute(
                "sort",
                sort
        );

        return "admin/schedule-list";
    }

    /* ================================
       일정 상세
    ================================= */

    @GetMapping("/schedules/{scheduleId}")
    public String scheduleDetail(
            @PathVariable Long scheduleId,
            @RequestParam(required = false) String from,
            Model model
    ) {

        ScheduleResponse schedule =
                scheduleService.findScheduleResponse(scheduleId);

        if (schedule == null) {
            return "redirect:/admin/schedules";
        }

        var scheduleSpots = schedule.getSpots();

        model.addAttribute(
                "schedule",
                schedule
        );

        model.addAttribute(
                "scheduleSpots",
                scheduleSpots
        );

        /*
         * 상세 페이지에 어디에서 들어왔는지 전달
         *
         * 일정 목록에서 들어온 경우:
         * from = null
         *
         * 달력에서 들어온 경우:
         * from = "calendar"
         */
        model.addAttribute(
                "from",
                from
        );

        return "admin/schedule-detail";
    }

    /* ================================
       일정 수정 화면
    ================================= */

    @GetMapping("/schedules/{scheduleId}/edit")
    public String editScheduleForm(
            @PathVariable Long scheduleId,
            @RequestParam(required = false) String from,
            Model model
    ) {

        ScheduleResponse schedule =
                scheduleService.findScheduleResponse(scheduleId);

        if (schedule == null) {
            return "redirect:/admin/schedules";
        }

        var scheduleSpots = schedule.getSpots();

        ScheduleForm form =
                new ScheduleForm();

        form.setMemberId(
                schedule.getMember().getMemberId()
        );

        form.setScheduleDate(
                schedule.getScheduleDate()
        );

        form.setSiteId(schedule.getSiteId());
        form.setCourseId(schedule.getCourseId());

        form.setWeather(
                schedule.getWeather()
        );

        form.setTemperature(
                schedule.getTemperature()
        );

        form.setHumidity(
                schedule.getHumidity()
        );

        /*
         * 첫 번째 스팟
         */
        if (scheduleSpots.size() >= 1) {

            var firstSpot =
                    scheduleSpots.get(0);

            form.setFirstSpotId(
                    firstSpot.getSpotId()
            );

            form.setFirstStartTime(
                    firstSpot.getStartTime()
            );
        }

        /*
         * 두 번째 스팟
         */
        if (scheduleSpots.size() >= 2) {

            var secondSpot =
                    scheduleSpots.get(1);

            form.setSecondSpotId(
                    secondSpot.getSpotId()
            );

            form.setSecondStartTime(
                    secondSpot.getStartTime()
            );
        }

        addScheduleSites(model);

        List<Member> members =
                memberService.findAllMembers();

        model.addAttribute(
                "scheduleId",
                scheduleId
        );

        model.addAttribute(
                "scheduleForm",
                form
        );

        model.addAttribute(
                "members",
                members
        );

        model.addAttribute(
                "from",
                from
        );

        return "admin/schedule-edit";
    }

    /* ================================
       일정 수정
    ================================= */

    @PostMapping("/schedules/{scheduleId}/edit")
    public String updateSchedule(
            @PathVariable Long scheduleId,
            @RequestParam(required = false) String from,
            @Valid ScheduleForm form,
            BindingResult bindingResult,
            Model model
    ) {

        if (bindingResult.hasErrors()) {

            addScheduleSites(model);

            List<Member> members =
                    memberService.findAllMembers();

            model.addAttribute(
                    "scheduleId",
                    scheduleId
            );

            model.addAttribute(
                    "members",
                    members
            );

            model.addAttribute(
                    "from",
                    from
            );

            return "admin/schedule-edit";
        }

        try {
            scheduleService.updateSchedule(
                    scheduleId,
                    form.getMemberId(),
                    form.getScheduleDate(),
                    form.getSiteId(),
                    form.getCourseId(),
                    form.getFirstSpotId(),
                    form.getFirstStartTime(),
                    form.getSecondSpotId(),
                    form.getSecondStartTime(),
                    form.getWeather(),
                    form.getTemperature(),
                    form.getHumidity()
            );
        } catch (ScheduleService.DuplicateScheduleException exception) {
            bindingResult.reject("duplicateSchedule", exception.getMessage());
            addScheduleSites(model);
            model.addAttribute("members", memberService.findAllMembers());
            model.addAttribute("scheduleId", scheduleId);
            model.addAttribute("from", from);
            return "admin/schedule-edit";
        } catch (IllegalArgumentException exception) {
            bindingResult.rejectValue("firstSpotId", "invalid", exception.getMessage());
            addScheduleSites(model);
            model.addAttribute("members", memberService.findAllMembers());
            model.addAttribute("scheduleId", scheduleId);
            model.addAttribute("from", from);
            return "admin/schedule-edit";
        }

        /*
         * 달력에서 들어와서 수정한 경우
         * 다시 상세 페이지에서도
         * from=calendar를 유지한다.
         */
        if ("calendar".equals(from)) {

            return "redirect:/admin/schedules/"
                    + scheduleId
                    + "?from=calendar";
        }

        return "redirect:/admin/schedules/"
                + scheduleId;
    }

    /* ================================
       일정 삭제
    ================================= */

    @PostMapping("/schedules/{scheduleId}/delete")
    public String deleteSchedule(
            @PathVariable Long scheduleId
    ) {

        scheduleService.deleteSchedule(
                scheduleId
        );

        return "redirect:/admin/schedules";
    }

    /* ================================
       날씨 조회 API
    ================================= */

    @GetMapping("/weather")
    @ResponseBody
    public ResponseEntity<?> getWeather(
            @RequestParam LocalDate date,
            @RequestParam LocalTime time
    ) {
        return weatherResponses.getWeather(date, time);
    }

    /* ================================
       관리자 일정 달력
    ================================= */

    @GetMapping("/schedules/calendar")
    public String scheduleCalendar(
            Model model
    ) {

        List<Schedule> schedules =
                scheduleService.findAllSchedules();

        model.addAttribute(
                "schedules",
                schedules
        );

        return "admin/schedule-calendar";
    }
}
