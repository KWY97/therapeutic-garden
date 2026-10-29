package com.example.manage.controller;

import com.example.manage.domain.Site;
import com.example.manage.dto.ImageResponse;
import com.example.manage.dto.SiteForm;
import com.example.manage.service.AdminPlaceFormService;
import com.example.manage.service.SiteImageService;
import com.example.manage.service.SiteService;
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
public class AdminSiteController {
    private final SiteService siteService;
    private final AdminPlaceFormService placeForms;
    private final SiteImageService siteImages;

    @Value("${kakao.maps.javascript-key}")
    private String kakaoMapsJavaScriptKey;

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
        if (siteId != null)
            model.addAttribute("siteId", siteId);
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
        if (gallery)
            images = images.stream()
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
}
