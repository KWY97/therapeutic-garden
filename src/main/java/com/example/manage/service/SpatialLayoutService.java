package com.example.manage.service;

import com.example.manage.domain.*;
import com.example.manage.dto.*;
import com.example.manage.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional
public class SpatialLayoutService {
    private final SiteRepository sites;
    private final SiteImageRepository images;
    private final HealingSpotRepository spots;
    private final SiteImageSpotPositionRepository positions;
    private final SiteImageService siteImages;
    private final HealingSpotImageService spotImages;

    public record Spot(Long spotId, String course, Long courseId, String courseCode, String courseName,
                       String code, String name, String readUrl,
                       BigDecimal xPercent, BigDecimal yPercent, Double latitude, Double longitude) {}
    public record Layout(Long siteId, String name, long revision, ImageResponse image, List<Spot> spots) {}

    @Transactional(readOnly = true)
    public Layout load(Long siteId) {
        Site site = sites.findById(siteId).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 Site입니다."));
        ImageResponse image = siteImages.list(siteId).stream().filter(ImageResponse::spatial).findFirst().orElse(null);
        Map<Long, SiteImageSpotPosition> saved = new HashMap<>();
        if (image != null) positions.findBySiteImageImageId(image.imageId())
                .forEach(p -> saved.put(p.getHealingSpot().getSpotId(), p));
        List<Spot> result = spots.findByHealingCourseSiteSiteId(siteId).stream()
                .sorted(Comparator.comparing((HealingSpot s) -> s.getHealingCourse().getCode())
                        .thenComparing(HealingSpot::getCode).thenComparing(HealingSpot::getSpotId))
                .map(s -> {
                    String url = spotImages.monitoringReadUrl(s.getSpotId());
                    var p = saved.get(s.getSpotId());
                    return new Spot(s.getSpotId(), s.getHealingCourse().getCode() + " · " + s.getHealingCourse().getName(),
                            s.getHealingCourse().getCourseId(), s.getHealingCourse().getCode(), s.getHealingCourse().getName(),
                            s.getCode(), s.getName(), url, p == null ? null : p.getXPercent(), p == null ? null : p.getYPercent(),
                            s.getLatitude(), s.getLongitude());
                }).toList();
        return new Layout(siteId, site.getName(), site.getSpatialRevision(), image, result);
    }

    public void save(Long siteId, SpatialLayoutForm form) {
        Site site = sites.findLockedById(siteId).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 Site입니다."));
        SiteImage image = images.findBySiteSiteIdOrderByDisplayOrderAscImageIdAsc(siteId).stream()
                .filter(SiteImage::isSpatial).findFirst().orElseThrow(SpatialLayoutService::stale);
        if (!image.getImageId().equals(form.getSiteImageId()) || form.getRevision() == null
                || form.getRevision() != site.getSpatialRevision()) throw stale();
        Map<Long, HealingSpot> owned = new HashMap<>();
        spots.findByHealingCourseSiteSiteId(siteId).forEach(s -> owned.put(s.getSpotId(), s));
        Set<Long> submitted = new HashSet<>();
        if (form.getPositions() == null) throw stale();
        for (var p : form.getPositions()) {
            if (p == null || !owned.containsKey(p.getSpotId()) || !submitted.add(p.getSpotId()))
                throw new IllegalArgumentException("이 Site의 HS만 중복 없이 배치할 수 있습니다.");
            if (p.getXPercent() != null || p.getYPercent() != null) {
                SiteImageSpotPosition.validate(p.getXPercent()); SiteImageSpotPosition.validate(p.getYPercent());
            }
        }
        if (!submitted.equals(owned.keySet())) throw stale();
        Map<Long, SiteImageSpotPosition> existing = new HashMap<>();
        positions.findBySiteImageImageId(image.getImageId()).forEach(p -> existing.put(p.getHealingSpot().getSpotId(), p));
        for (var p : form.getPositions()) {
            var previous = existing.remove(p.getSpotId());
            if (p.getXPercent() == null) { if (previous != null) positions.delete(previous); }
            else if (previous != null) previous.move(p.getXPercent(), p.getYPercent());
            else positions.save(new SiteImageSpotPosition(image, owned.get(p.getSpotId()), p.getXPercent(), p.getYPercent()));
        }
        existing.values().forEach(positions::delete);
        site.advanceSpatialRevision();
        positions.flush();
    }
    private static IllegalArgumentException stale() {
        return new IllegalArgumentException("모니터링 이미지 또는 HS 위치 정보가 변경되었습니다. 화면에 다시 진입해 HS 위치를 설정해 주세요.");
    }
}
