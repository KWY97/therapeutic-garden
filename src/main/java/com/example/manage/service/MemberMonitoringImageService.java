package com.example.manage.service;

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
        var content = siteImages.localContent(siteId, image.imageId());
        return new ImageContent(content.bytes(), content.contentType());
    }

    public ImageContent spotThumbnail(Long spotId) {
        Long siteId = datasetSiteId();
        boolean allowed = spots.findByHealingCourseSiteSiteId(siteId).stream()
                .anyMatch(spot -> spot.getSpotId().equals(spotId) && spot.getCode().matches("HS[1-6]"));
        if (!allowed) throw new IllegalArgumentException("조회할 수 없는 HealingSpot입니다.");

        ImageResponse image = spotImages.list(spotId).stream()
                .filter(ImageResponse::representative)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("대표 이미지가 없습니다."));
        var content = spotImages.localThumbnail(spotId, image.imageId());
        return new ImageContent(content.bytes(), content.contentType());
    }

    private Long datasetSiteId() {
        return effectBatches.findFirstByOrderByIdAsc()
                .map(batch -> batch.getTargetSiteId())
                .orElseThrow(() -> new IllegalArgumentException("데이터셋 Site가 없습니다."));
    }

    public record ImageContent(byte[] bytes, String contentType) {}
}
