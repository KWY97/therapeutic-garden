package com.example.manage.controller;

import com.example.manage.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.http.*;
import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminImageController {
    private final SiteImageService siteImages;
    private final HealingSpotImageService spotImages;
    private final ImageHttpResponseService imageResponses;

    @GetMapping("/sites/{parentId}/images")
    public String siteImages(@PathVariable Long parentId) {
        return "redirect:/admin/sites/" + parentId + "/edit#place-images";
    }

    @PostMapping("/sites/{parentId}/images")
    public String uploadSite(@PathVariable Long parentId,
            @RequestParam(name = "files", required = false) List<MultipartFile> files, RedirectAttributes flash) {
        return action("/admin/sites/" + parentId + "/edit#place-images", flash, () -> siteImages.upload(parentId, files));
    }

    @PostMapping("/sites/{parentId}/images/{imageId}/delete")
    public String deleteSite(@PathVariable Long parentId, @PathVariable Long imageId, RedirectAttributes flash) {
        return action("/admin/sites/" + parentId + "/edit#place-images", flash, () -> siteImages.delete(parentId, imageId));
    }

    @PostMapping("/sites/{parentId}/images/{imageId}/representative")
    public String representativeSite(@PathVariable Long parentId, @PathVariable Long imageId, RedirectAttributes flash) {
        return action("/admin/sites/" + parentId + "/edit#place-images", flash, () -> siteImages.setRepresentative(parentId, imageId));
    }

    @PostMapping("/sites/{parentId}/images/{imageId}/order")
    public String orderSite(@PathVariable Long parentId, @PathVariable Long imageId,
            @RequestParam String direction, RedirectAttributes flash) {
        return action("/admin/sites/" + parentId + "/edit#place-images", flash, () -> siteImages.move(parentId, imageId, direction));
    }

    @GetMapping("/sites/{parentId}/images/{imageId}/content")
    public ResponseEntity<byte[]> contentSite(@PathVariable Long parentId, @PathVariable Long imageId) {
        try {
            var content = siteImages.localContent(parentId, imageId);
            return imageResponses.noStore(content.bytes(), content.contentType());
        } catch (IllegalArgumentException e) { return ResponseEntity.notFound().build(); }
    }

    @GetMapping("/spots/{parentId}/images")
    public String spotImages(@PathVariable Long parentId) {
        return "redirect:/admin/spots/" + parentId + "/edit#place-images";
    }

    @PostMapping("/spots/{parentId}/images")
    public String uploadSpot(@PathVariable Long parentId,
            @RequestParam(name = "files", required = false) List<MultipartFile> files, RedirectAttributes flash) {
        return action("/admin/spots/" + parentId + "/edit#place-images", flash, () -> spotImages.upload(parentId, files));
    }

    @PostMapping("/spots/{parentId}/images/{imageId}/delete")
    public String deleteSpot(@PathVariable Long parentId, @PathVariable Long imageId, RedirectAttributes flash) {
        return action("/admin/spots/" + parentId + "/edit#place-images", flash, () -> spotImages.delete(parentId, imageId));
    }

    @PostMapping("/spots/{parentId}/images/{imageId}/representative")
    public String representativeSpot(@PathVariable Long parentId, @PathVariable Long imageId, RedirectAttributes flash) {
        return action("/admin/spots/" + parentId + "/edit#place-images", flash, () -> spotImages.setRepresentative(parentId, imageId));
    }

    @PostMapping("/spots/{parentId}/images/{imageId}/order")
    public String orderSpot(@PathVariable Long parentId, @PathVariable Long imageId,
            @RequestParam String direction, RedirectAttributes flash) {
        return action("/admin/spots/" + parentId + "/edit#place-images", flash, () -> spotImages.move(parentId, imageId, direction));
    }

    @GetMapping("/spots/{parentId}/images/{imageId}/content")
    public ResponseEntity<byte[]> contentSpot(@PathVariable Long parentId, @PathVariable Long imageId) {
        try {
            var content = spotImages.localContent(parentId, imageId);
            return imageResponses.noStore(content.bytes(), content.contentType());
        } catch (IllegalArgumentException e) { return ResponseEntity.notFound().build(); }
    }

    @GetMapping("/spots/{parentId}/images/{imageId}/thumbnail")
    public ResponseEntity<byte[]> thumbnailSpot(@PathVariable Long parentId, @PathVariable Long imageId) {
        try {
            var content = spotImages.localThumbnail(parentId, imageId);
            return imageResponses.noStore(content.bytes(), content.contentType());
        } catch (IllegalArgumentException e) { return ResponseEntity.notFound().build(); }
    }

    private String action(String path, RedirectAttributes flash, Runnable work) {
        try {
            work.run();
            flash.addFlashAttribute("successMessage", "이미지 변경 사항을 저장했습니다.");
        } catch (com.example.manage.storage.ImageStorageException e) {
            flash.addFlashAttribute("errorMessage", "이미지 저장 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        } catch (DataAccessException | org.springframework.transaction.TransactionException e) {
            flash.addFlashAttribute("errorMessage", "이미지 정보를 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        return "redirect:" + path;
    }
}
