package com.example.manage.service;

import com.example.manage.domain.HealingSpot;
import com.example.manage.dto.HealingSpotDetailResponse;
import com.example.manage.dto.ImageResponse;
import com.example.manage.repository.HealingEffectImportBatchRepository;
import com.example.manage.repository.HealingSpotRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberMonitoringImageService {
    private final HealingEffectImportBatchRepository effectBatches;
    private final HealingSpotRepository spots;
    private final SiteImageService siteImages;
    private final HealingSpotImageService spotImages;

    public ImageContent siteImage() {
        Long siteId = datasetSiteId();
        ImageResponse image = siteImages.list(siteId).stream()
                .filter(ImageResponse::spatial)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("공간 이미지가 없습니다."));
        var content = siteImages.authenticatedContent(siteId, image.imageId());
        return new ImageContent(content.bytes(), content.contentType());
    }

    public ImageContent spotThumbnail(Long spotId) {
        allowedSpot(datasetSiteId(), spotId);

        ImageResponse image = spotImages.list(spotId).stream()
                .filter(ImageResponse::representative)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("대표 이미지가 없습니다."));
        var content = spotImages.authenticatedThumbnail(spotId, image.imageId());
        return new ImageContent(content.bytes(), content.contentType());
    }

    public HealingSpotDetailResponse spotDetail(Long spotId) {
        return spotDetail(spotId, "/member/api/monitoring");
    }

    public HealingSpotDetailResponse spotDetail(Long spotId, String apiBase) {
        HealingSpot spot = allowedSpot(datasetSiteId(), spotId);
        var images = spotImages.list(spotId).stream().map(image -> {
            String readUrl = apiBase.startsWith("/admin/") || isAdminPath(image.readUrl())
                    ? apiBase + "/spots/" + spotId + "/images/" + image.imageId() + "/content" : image.readUrl();
            return new ImageResponse(image.imageId(), image.originalFileName(), image.contentType(),
                    image.displayOrder(), image.representative(), readUrl, image.spatial());
        }).toList();
        return HealingSpotDetailResponse.from(spot, images);
    }

    public ImageContent spotImage(Long spotId, Long imageId) {
        allowedSpot(datasetSiteId(), spotId);
        var content = spotImages.authenticatedContent(spotId, imageId);
        return new ImageContent(content.bytes(), content.contentType());
    }

    private HealingSpot allowedSpot(Long siteId, Long spotId) {
        return spots.findByHealingCourseSiteSiteId(siteId).stream()
                .filter(spot -> spot.getSpotId().equals(spotId) && spot.getCode().matches("HS[1-6]"))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("조회할 수 없는 HealingSpot입니다."));
    }

    private boolean isAdminPath(String readUrl) {
        return readUrl != null && readUrl.startsWith("/admin/");
    }

    private Long datasetSiteId() {
        return effectBatches.findFirstByOrderByIdAsc()
                .map(batch -> batch.getTargetSiteId())
                .orElseThrow(() -> new IllegalArgumentException("데이터셋 Site가 없습니다."));
    }

    public record ImageContent(byte[] bytes, String contentType) {}
}
