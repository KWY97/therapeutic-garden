package com.example.manage.controller;

import com.example.manage.domain.Site;
import com.example.manage.domain.HealingSpot;
import com.example.manage.dto.HealingSpotForm;
import com.example.manage.service.HealingSpotService;
import com.example.manage.domain.HealingCourse;
import com.example.manage.dto.HealingCourseForm;
import com.example.manage.service.HealingCourseService;
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
import com.example.manage.service.HealingSpotImageService;
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
    private final HealingSpotImageService spotImages;

    private final HealingCourseService healingCourseService;
    private final HealingSpotService healingSpotService;

    @Value("${kakao.maps.javascript-key}")
    private String kakaoMapsJavaScriptKey;

    private String courseForm(Model model, Long courseId) {
        model.addAttribute("courseId", courseId);
        model.addAttribute("courseSites", siteService.findAllSites());
        model.addAttribute("kakaoMapsJavaScriptKey", kakaoMapsJavaScriptKey);
        return "admin/course-form";
    }

    @GetMapping("/courses")
    public String courseList(@RequestParam(required = false) Long siteId, Model model,
                             RedirectAttributes redirectAttributes) {
        model.addAttribute("courseSites", siteService.findAllSites());
        if (siteId != null) {
            Site site = siteService.findSite(siteId);
            if (site == null) {
                redirectAttributes.addFlashAttribute("errorMessage", "존재하지 않는 사이트입니다.");
                return "redirect:/admin/courses";
            }
            model.addAttribute("selectedSite", site);
            model.addAttribute("courses", healingCourseService.findBySiteId(siteId));
        }
        return "admin/course-list";
    }

    @GetMapping("/courses/new")
    public String newCourse(@RequestParam(required = false) Long siteId, Model model,
                            RedirectAttributes redirectAttributes) {
        if (siteId != null && siteService.findSite(siteId) == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "존재하지 않는 사이트입니다.");
            return "redirect:/admin/courses";
        }
        HealingCourseForm form = new HealingCourseForm();
        form.setSiteId(siteId);
        model.addAttribute("healingCourseForm", form);
        return courseForm(model, null);
    }

    @GetMapping("/courses/{courseId}")
    public String courseDetail(@PathVariable Long courseId, Model model, RedirectAttributes redirectAttributes) {
        try {
            model.addAttribute("course", healingCourseService.findHealingCourse(courseId));
            return "admin/course-detail";
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:/admin/courses";
        }
    }

    @GetMapping("/courses/{courseId}/edit")
    public String editCourse(@PathVariable Long courseId, Model model, RedirectAttributes redirectAttributes) {
        try {
            HealingCourse course = healingCourseService.findHealingCourse(courseId);
            HealingCourseForm form = new HealingCourseForm();
            form.setSiteId(course.getSite().getSiteId());
            form.setCode(course.getCode());
            form.setName(course.getName());
            form.setCenterLatitude(course.getCenterLatitude());
            form.setCenterLongitude(course.getCenterLongitude());
            form.setRadius(course.getRadius());
            model.addAttribute("healingCourseForm", form);
            return courseForm(model, courseId);
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:/admin/courses";
        }
    }

    @PostMapping("/courses/new")
    public String createCourse(@Valid @ModelAttribute HealingCourseForm form, BindingResult result, Model model) {
        return saveCourse(null, form, result, model);
    }

    @PostMapping("/courses/{courseId}/edit")
    public String updateCourse(@PathVariable Long courseId, @Valid @ModelAttribute HealingCourseForm form,
                               BindingResult result, Model model) {
        return saveCourse(courseId, form, result, model);
    }

    private String saveCourse(Long courseId, HealingCourseForm form, BindingResult result, Model model) {
        if (!result.hasErrors()) {
            try {
                if (courseId == null) {
                    healingCourseService.createHealingCourse(form.getSiteId(), form.getCode(), form.getName(),
                            form.getCenterLatitude(), form.getCenterLongitude(), form.getRadius());
                } else {
                    healingCourseService.updateHealingCourse(courseId, form.getSiteId(), form.getCode(), form.getName(),
                            form.getCenterLatitude(), form.getCenterLongitude(), form.getRadius());
                }
                return "redirect:/admin/courses?siteId=" + form.getSiteId();
            } catch (IllegalArgumentException exception) {
                result.reject("invalid", exception.getMessage());
            } catch (DataIntegrityViolationException exception) {
                result.reject("conflict", "저장하지 못했습니다. 연결된 사이트와 입력값을 확인하고 다시 시도해 주세요.");
            }
        }
        return courseForm(model, courseId);
    }

    @PostMapping("/courses/{courseId}/delete")
    public String deleteCourse(@PathVariable Long courseId, RedirectAttributes redirectAttributes) {
        Long siteId = null;
        try {
            siteId = healingCourseService.findHealingCourse(courseId).getSite().getSiteId();
            healingCourseService.deleteHealingCourse(courseId);
            redirectAttributes.addFlashAttribute("successMessage", "HealingCourse를 삭제했습니다.");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        } catch (DataIntegrityViolationException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", "연결된 데이터가 존재하여 HealingCourse를 삭제할 수 없습니다.");
        }
        return "redirect:/admin/courses" + (siteId == null ? "" : "?siteId=" + siteId);
    }

    private String spotForm(Model model, Long spotId) {
        model.addAttribute("spotId", spotId);
        model.addAttribute("spotCourses", healingCourseService.findAllHealingCourses());
        model.addAttribute("kakaoMapsJavaScriptKey", kakaoMapsJavaScriptKey);
        imageModel(model, false, spotId, false);
        return "admin/spot-form";
    }

    private HealingCourse spotCourse(Long courseId, Long siteId) {
        HealingCourse course = healingCourseService.findHealingCourse(courseId);
        if (siteId != null && !siteId.equals(course.getSite().getSiteId())) {
            throw new IllegalArgumentException("선택한 사이트에 속한 코스가 아닙니다.");
        }
        return course;
    }

    @GetMapping("/spots")
    public String spotList(@RequestParam(required = false) Long courseId,
                           @RequestParam(required = false) Long siteId,
                           Model model, RedirectAttributes redirectAttributes) {
        if (courseId == null) return "redirect:/admin/courses";
        try {
            model.addAttribute("selectedCourse", spotCourse(courseId, siteId));
            model.addAttribute("spots", healingSpotService.findByCourseId(courseId));
            return "admin/spot-list";
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:/admin/courses";
        }
    }

    @GetMapping("/spots/new")
    public String newSpot(@RequestParam(required = false) Long courseId,
                          @RequestParam(required = false) Long siteId,
                          Model model, RedirectAttributes redirectAttributes) {
        try {
            if (courseId != null) spotCourse(courseId, siteId);
            HealingSpotForm form = new HealingSpotForm();
            form.setCourseId(courseId);
            model.addAttribute("healingSpotForm", form);
            return spotForm(model, null);
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:/admin/courses";
        }
    }

    @GetMapping("/spots/{spotId}")
    public String spotDetail(@PathVariable Long spotId, Model model, RedirectAttributes redirectAttributes) {
        try {
            model.addAttribute("spot", healingSpotService.findHealingSpot(spotId));
            imageModel(model, false, spotId, true);
            return "admin/spot-detail";
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:/admin/courses";
        }
    }

    @GetMapping("/spots/{spotId}/edit")
    public String editSpot(@PathVariable Long spotId, Model model, RedirectAttributes redirectAttributes) {
        try {
            HealingSpot spot = healingSpotService.findHealingSpot(spotId);
            HealingSpotForm form = new HealingSpotForm();
            form.setCourseId(spot.getHealingCourse().getCourseId());
            form.setCode(spot.getCode());
            form.setName(spot.getName());
            form.setLatitude(spot.getLatitude());
            form.setLongitude(spot.getLongitude());
            model.addAttribute("healingSpotForm", form);
            return spotForm(model, spotId);
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:/admin/courses";
        }
    }

    @PostMapping("/spots/new")
    public String createSpot(@Valid @ModelAttribute HealingSpotForm form, BindingResult result, Model model,
                             @RequestParam(name = "files", required = false) List<MultipartFile> files) {
        return saveSpot(null, form, result, model, files);
    }

    @PostMapping("/spots/{spotId}/edit")
    public String updateSpot(@PathVariable Long spotId, @Valid @ModelAttribute HealingSpotForm form,
                             BindingResult result, Model model,
                             @RequestParam(name = "files", required = false) List<MultipartFile> files) {
        return saveSpot(spotId, form, result, model, files);
    }

    private String saveSpot(Long spotId, HealingSpotForm form, BindingResult result, Model model,
                            List<MultipartFile> files) {
        if (!result.hasErrors()) {
            try {
                if (spotId == null) {
                    HealingSpot created = placeForms.createSpot(form, files);
                    return "redirect:/admin/spots/" + created.getSpotId();
                } else {
                    placeForms.updateSpot(spotId, form, files);
                }
                return "redirect:/admin/spots/" + spotId;
            } catch (ImageStorageException exception) {
                result.reject("image", "이미지 저장 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
            } catch (IllegalArgumentException exception) {
                result.reject("invalid", exception.getMessage());
            } catch (DataAccessException | TransactionException exception) {
                result.reject("conflict", "저장하지 못했습니다. 연결된 코스와 입력값을 확인하고 다시 시도해 주세요.");
            }
        }
        model.addAttribute("imageSelectionNotice", "저장하지 못했습니다. 기존 이미지 편집은 저장 전 상태로 돌아갑니다. 입력값을 확인하고 새 이미지는 다시 선택해 주세요.");
        return spotForm(model, spotId);
    }

    @PostMapping("/spots/{spotId}/delete")
    public String deleteSpot(@PathVariable Long spotId, RedirectAttributes redirectAttributes) {
        Long courseId = null;
        try {
            courseId = healingSpotService.findHealingSpot(spotId).getHealingCourse().getCourseId();
            healingSpotService.deleteHealingSpot(spotId);
            redirectAttributes.addFlashAttribute("successMessage", "HealingSpot을 삭제했습니다.");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        } catch (DataIntegrityViolationException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", "연결된 데이터가 존재하여 HealingSpot을 삭제할 수 없습니다.");
        }
        return courseId == null ? "redirect:/admin/courses" : "redirect:/admin/spots?courseId=" + courseId;
    }

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
        imageModel(model, true, siteId, false);
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
        imageModel(model, true, siteId, true);
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

    private void imageModel(Model model, boolean site, Long id, boolean gallery) {
        model.addAttribute("siteImageEditor", site);
        model.addAttribute("imageTitle", site ? "사이트 이미지" : "HS 이미지");
        model.addAttribute("imageBasePath", id == null ? null : "/admin/" + (site ? "sites/" : "spots/") + id + "/images");
        List<ImageResponse> images = List.of();
        if (id != null) {
            try {
                images = site ? siteImages.list(id) : spotImages.list(id);
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
