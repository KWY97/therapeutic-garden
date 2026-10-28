package com.example.manage.controller;

import com.example.manage.domain.HealingCourse;
import com.example.manage.domain.Site;
import com.example.manage.dto.HealingCourseForm;
import com.example.manage.dto.SiteResponse;
import com.example.manage.service.HealingCourseService;
import com.example.manage.service.SiteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminCourseController {
    private final HealingCourseService healingCourseService;
    private final SiteService siteService;

    @Value("${kakao.maps.javascript-key}")
    private String kakaoMapsJavaScriptKey;

    @ModelAttribute("sites")
    public List<SiteResponse> scheduleSites() {
        return siteService.findAllSites().stream().map(SiteResponse::new).toList();
    }

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
}
