package com.example.manage.controller;

import com.example.manage.domain.Site;
import org.springframework.beans.factory.annotation.Value;
import com.example.manage.dto.SiteForm;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.multipart.MultipartFile;
import com.example.manage.dto.ImageResponse;
import com.example.manage.storage.ImageStorageException;
import com.example.manage.service.AdminPlaceFormService;
import com.example.manage.service.SiteImageService;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import com.example.manage.domain.Admin;
import com.example.manage.domain.Member;
import com.example.manage.domain.Schedule;
import com.example.manage.dto.ScheduleForm;
import com.example.manage.dto.ScheduleResponse;
import com.example.manage.service.SiteService;
import com.example.manage.dto.SiteResponse;
import com.example.manage.service.AdminService;
import com.example.manage.service.MemberService;
import com.example.manage.service.LoginAttemptLimiter;
import com.example.manage.service.ScheduleService;
import com.example.manage.service.SessionAuthentication;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminController {

    private final AdminService adminService;
    private final LoginAttemptLimiter loginAttempts;
    private final SessionAuthentication sessionAuthentication;
    private final MemberService memberService;
    private final ScheduleService scheduleService;
    private final WeatherLookupResponse weatherResponses;
    private final SiteService siteService;
    private final AdminPlaceFormService placeForms;
    private final SiteImageService siteImages;

    @Value("${kakao.maps.javascript-key}")
    private String kakaoMapsJavaScriptKey;

    @ModelAttribute("sites")
    public List<SiteResponse> scheduleSites() {
        return siteService.findAllSites().stream().map(SiteResponse::new).toList();
    }


    /* ================================
       사이트 관리
    ================================= */

    @GetMapping("/sites")
    public String siteList(Model model) {
        model.addAttribute("siteList", siteService.findAllSites());
        return "admin/site-list";
    }

    private String renderSiteForm(Model model, Long siteId) {
        model.addAttribute("kakaoMapsJavaScriptKey", kakaoMapsJavaScriptKey);
        if (siteId != null) model.addAttribute("siteId", siteId);
        imageModel(model, siteId, false);
        return siteId == null ? "admin/site-form" : "admin/site-edit";
    }

    @GetMapping("/sites/new")
    public String siteForm(Model model) {
        model.addAttribute("siteForm", new SiteForm());
        return renderSiteForm(model, null);
    }

    @PostMapping("/sites/new")
    public String createSite(@Valid @ModelAttribute SiteForm siteForm, BindingResult bindingResult, Model model,
                             @RequestParam(name = "files", required = false) List<MultipartFile> files) {
        if (!bindingResult.hasErrors()) {
            try {
                Site created = placeForms.createSite(siteForm, files);
                return "redirect:/admin/sites/" + created.getSiteId();
            } catch (AdminPlaceFormService.ImageEditException exception) {
                bindingResult.reject("image", exception.getMessage());
            } catch (ImageStorageException exception) {
                bindingResult.reject("image", "이미지 저장 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
            } catch (IllegalArgumentException exception) {
                bindingResult.rejectValue("name", "invalid", exception.getMessage());
            } catch (DataAccessException | TransactionException exception) {
                bindingResult.reject("conflict", "저장하지 못했습니다. 사이트명과 입력값을 확인하고 다시 시도해 주세요.");
            }
        }
        model.addAttribute("imageSelectionNotice", "저장하지 못했습니다. 기존 이미지 편집은 저장 전 상태로 돌아갑니다. 입력값을 확인하고 새 이미지는 다시 선택해 주세요.");
        return renderSiteForm(model, null);
    }

    @GetMapping("/sites/{siteId}")
    public String siteDetail(@PathVariable Long siteId, Model model, RedirectAttributes redirectAttributes) {
        Site site = siteService.findSite(siteId);
        if (site == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "존재하지 않는 사이트입니다.");
            return "redirect:/admin/sites";
        }
        model.addAttribute("site", site);
        imageModel(model, siteId, true);
        return "admin/site-detail";
    }

    @GetMapping("/sites/{siteId}/edit")
    public String editSiteForm(@PathVariable Long siteId, Model model, RedirectAttributes redirectAttributes) {
        Site site = siteService.findSite(siteId);
        if (site == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "존재하지 않는 사이트입니다.");
            return "redirect:/admin/sites";
        }
        SiteForm form = new SiteForm();
        form.setName(site.getName());
        form.setAddress(site.getAddress());
        form.setLatitude(site.getLatitude());
        form.setLongitude(site.getLongitude());
        form.setMapLevel(site.getMapLevel());
        model.addAttribute("siteId", siteId);
        model.addAttribute("siteForm", form);
        return renderSiteForm(model, siteId);
    }

    @PostMapping("/sites/{siteId}/edit")
    public String updateSite(@PathVariable Long siteId, @Valid @ModelAttribute SiteForm siteForm,
                             BindingResult bindingResult, Model model, RedirectAttributes redirectAttributes,
                             @RequestParam(name = "files", required = false) List<MultipartFile> files) {
        if (siteService.findSite(siteId) == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "존재하지 않는 사이트입니다.");
            return "redirect:/admin/sites";
        }
        if (!bindingResult.hasErrors()) {
            try {
                placeForms.updateSite(siteId, siteForm, files);
                return "redirect:/admin/sites/" + siteId;
            } catch (AdminPlaceFormService.ImageEditException exception) {
                bindingResult.reject("image", exception.getMessage());
            } catch (ImageStorageException exception) {
                bindingResult.reject("image", "이미지 저장 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
            } catch (IllegalArgumentException exception) {
                bindingResult.rejectValue("name", "invalid", exception.getMessage());
            } catch (DataAccessException | TransactionException exception) {
                bindingResult.reject("conflict", "저장하지 못했습니다. 사이트명과 입력값을 확인하고 다시 시도해 주세요.");
            }
        }
        model.addAttribute("imageSelectionNotice", "저장하지 못했습니다. 기존 이미지 편집은 저장 전 상태로 돌아갑니다. 입력값을 확인하고 새 이미지는 다시 선택해 주세요.");
        return renderSiteForm(model, siteId);
    }

    private void imageModel(Model model, Long id, boolean gallery) {
        model.addAttribute("siteImageEditor", true);
        model.addAttribute("imageTitle", "사이트 이미지");
        model.addAttribute("imageBasePath", id == null ? null : "/admin/sites/" + id + "/images");
        List<ImageResponse> images = List.of();
        if (id != null) {
            try {
                images = siteImages.list(id);
            } catch (IllegalArgumentException exception) {
                // Storage problems should not prevent reading or editing the parent's basic information.
                model.addAttribute("imageError", "이미지를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
            }
        }
        if (gallery) images = images.stream()
                .sorted(java.util.Comparator.comparing(ImageResponse::representative).reversed()).toList();
        model.addAttribute("images", images);
    }

    @PostMapping("/sites/{siteId}/delete")
    public String deleteSite(@PathVariable Long siteId, RedirectAttributes redirectAttributes) {
        try {
            siteService.deleteSite(siteId);
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:/admin/sites";
        } catch (DataIntegrityViolationException exception) {
            // 존재 여부 확인 직후 다른 요청에서 코스를 등록한 경우에도 FK로 삭제를 차단한다.
            redirectAttributes.addFlashAttribute("errorMessage", "연결된 데이터가 존재하여 사이트를 삭제할 수 없습니다.");
            return "redirect:/admin/sites";
        }
        redirectAttributes.addFlashAttribute("successMessage", "사이트를 삭제했습니다.");
        return "redirect:/admin/sites";
    }

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


    /* ================================
       일정 등록 화면
    ================================= */

    @GetMapping("/schedules/new")
    public String scheduleForm(
            Model model
    ) {

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
            model.addAttribute("members", memberService.findAllMembers());
            return "admin/schedule-form";
        } catch (IllegalArgumentException exception) {
            bindingResult.rejectValue("firstSpotId", "invalid", exception.getMessage());
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
            model.addAttribute("members", memberService.findAllMembers());
            model.addAttribute("scheduleId", scheduleId);
            model.addAttribute("from", from);
            return "admin/schedule-edit";
        } catch (IllegalArgumentException exception) {
            bindingResult.rejectValue("firstSpotId", "invalid", exception.getMessage());
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
