package com.example.manage.controller;

import com.example.manage.dto.HealingSpotDetailResponse;
import com.example.manage.repository.HealingEffectImportBatchRepository;
import com.example.manage.service.ImageHttpResponseService;
import com.example.manage.service.MemberMonitoringImageService;
import com.example.manage.service.MemberService;
import com.example.manage.service.SpatialLayoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/members/{memberId}/data/api")
public class AdminMemberAnalysisApiController {
    private final MemberService members;
    private final HealingEffectImportBatchRepository effectBatches;
    private final SpatialLayoutService layouts;
    private final MemberMonitoringImageService monitoringImages;
    private final ImageHttpResponseService imageResponses;

    private String base(Long memberId) {
        return "/admin/members/" + memberId + "/data/api";
    }

    private void requireMember(Long memberId) {
        if (members.findMember(memberId) == null)
            throw new IllegalArgumentException("존재하지 않는 참가자입니다.");
    }

    @GetMapping("/spatial-layout")
    public ResponseEntity<SpatialLayoutService.Layout> layout(@PathVariable Long memberId) {
        try {
            requireMember(memberId);
            Long siteId = effectBatches.findFirstByOrderByIdAsc()
                    .map(batch -> batch.getTargetSiteId()).orElseThrow();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(MemberSpatialLayoutController.memberLayout(layouts.load(siteId), base(memberId)));
        } catch (IllegalArgumentException | java.util.NoSuchElementException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/monitoring/site-image")
    public ResponseEntity<byte[]> siteImage(@PathVariable Long memberId) {
        try {
            requireMember(memberId);
            var content = monitoringImages.siteImage();
            return imageResponses.privateNoStore(content.bytes(), content.contentType());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/monitoring/spots/{spotId}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable Long memberId, @PathVariable Long spotId) {
        try {
            requireMember(memberId);
            var content = monitoringImages.spotThumbnail(spotId);
            return imageResponses.privateNoStore(content.bytes(), content.contentType());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/monitoring/spots/{spotId}")
    public ResponseEntity<HealingSpotDetailResponse> spot(@PathVariable Long memberId, @PathVariable Long spotId) {
        try {
            requireMember(memberId);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(monitoringImages.spotDetail(spotId, base(memberId) + "/monitoring"));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/monitoring/spots/{spotId}/images/{imageId}/content")
    public ResponseEntity<byte[]> image(@PathVariable Long memberId, @PathVariable Long spotId,
            @PathVariable Long imageId) {
        try {
            requireMember(memberId);
            var content = monitoringImages.spotImage(spotId, imageId);
            return imageResponses.privateNoStore(content.bytes(), content.contentType());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
    }
}
