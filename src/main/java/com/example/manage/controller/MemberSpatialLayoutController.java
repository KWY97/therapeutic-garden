package com.example.manage.controller;

import com.example.manage.dto.ImageResponse;
import com.example.manage.repository.HealingEffectImportBatchRepository;
import com.example.manage.service.SpatialLayoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/member/api/spatial-layout")
public class MemberSpatialLayoutController {
    private final HealingEffectImportBatchRepository effectBatches;
    private final SpatialLayoutService layouts;

    @GetMapping
    public ResponseEntity<SpatialLayoutService.Layout> data() {
        var siteId = effectBatches.findFirstByOrderByIdAsc()
                .map(batch -> batch.getTargetSiteId()).orElse(null);
        if (siteId == null)
            return ResponseEntity.notFound().build();
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(memberLayout(layouts.load(siteId), "/member/api"));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    public static SpatialLayoutService.Layout memberLayout(SpatialLayoutService.Layout layout, String apiBase) {
        ImageResponse image = layout.image();
        if (image != null && (apiBase.startsWith("/admin/") || isAdminPath(image.readUrl()))) {
            image = new ImageResponse(image.imageId(), image.originalFileName(), image.contentType(),
                    image.displayOrder(), image.representative(), apiBase + "/monitoring/site-image", image.spatial());
        }
        var spots = layout.spots().stream().map(spot -> {
            String readUrl = apiBase.startsWith("/admin/") || isAdminPath(spot.readUrl())
                    ? apiBase + "/monitoring/spots/" + spot.spotId() + "/thumbnail"
                    : spot.readUrl();
            return new SpatialLayoutService.Spot(spot.spotId(), spot.course(), spot.courseId(), spot.courseCode(),
                    spot.courseName(), spot.code(), spot.name(), readUrl, spot.xPercent(), spot.yPercent(),
                    spot.latitude(), spot.longitude());
        }).toList();
        return new SpatialLayoutService.Layout(layout.siteId(), layout.name(), layout.revision(), image, spots);
    }

    private static boolean isAdminPath(String readUrl) {
        return readUrl != null && readUrl.startsWith("/admin/");
    }
}
