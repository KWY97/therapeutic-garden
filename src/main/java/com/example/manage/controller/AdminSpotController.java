package com.example.manage.controller;

import com.example.manage.domain.HealingCourse;
import com.example.manage.domain.HealingSpot;
import com.example.manage.dto.HealingSpotForm;
import com.example.manage.dto.ImageResponse;
import com.example.manage.service.AdminPlaceFormService;
import com.example.manage.service.HealingCourseService;
import com.example.manage.service.HealingSpotImageService;
import com.example.manage.service.HealingSpotService;
import com.example.manage.storage.ImageStorageException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminSpotController {
    private final HealingCourseService healingCourseService;
    private final HealingSpotService healingSpotService;
    private final AdminPlaceFormService placeForms;
    private final HealingSpotImageService spotImages;

    @Value("${kakao.maps.javascript-key}")
    private String kakaoMapsJavaScriptKey;

    private String spotForm(Model model, Long spotId) {
        model.addAttribute("spotId", spotId);
        model.addAttribute("spotCourses", healingCourseService.findAllHealingCourses());
        model.addAttribute("kakaoMapsJavaScriptKey", kakaoMapsJavaScriptKey);
        imageModel(model, spotId, false);
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
        if (courseId == null)
            return "redirect:/admin/courses";
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
            if (courseId != null)
                spotCourse(courseId, siteId);
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
            imageModel(model, spotId, true);
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

    private void imageModel(Model model, Long id, boolean gallery) {
        model.addAttribute("siteImageEditor", false);
        model.addAttribute("imageTitle", "HS 이미지");
        model.addAttribute("imageBasePath", id == null ? null : "/admin/spots/" + id + "/images");
        List<ImageResponse> images = List.of();
        if (id != null) {
            try {
                images = spotImages.list(id);
            } catch (IllegalArgumentException exception) {
                // Storage problems should not prevent reading or editing the parent's basic information.
                model.addAttribute("imageError", "이미지를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
            }
        }
        if (gallery)
            images = images.stream()
                .sorted(java.util.Comparator.comparing(ImageResponse::representative).reversed()).toList();
        model.addAttribute("images", images);
    }
}
