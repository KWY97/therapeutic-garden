package com.example.manage.service;

import com.example.manage.domain.HealingCourse;
import com.example.manage.domain.HealingSpot;
import com.example.manage.dto.HealingSpotDetailResponse;
import com.example.manage.repository.HealingCourseRepository;
import com.example.manage.repository.HealingSpotRepository;
import com.example.manage.repository.ScheduleSpotRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class HealingSpotService {
    private final HealingSpotRepository healingSpotRepository;
    private final com.example.manage.repository.SiteRepository siteRepository;
    private final com.example.manage.repository.SiteImageSpotPositionRepository positions;
    private final HealingSpotImageService healingSpotImageService;
    private final HealingCourseRepository healingCourseRepository;
    private final ScheduleSpotRepository scheduleSpotRepository;
    private final com.example.manage.repository.HealingSpotEffectSummaryRepository effectSummaries;
    private final com.example.manage.repository.MemberHealingSpotEffectSummaryRepository memberEffectSummaries;

    public HealingSpot createHealingSpot(HealingCourse course, String code, String name,
            Double latitude, Double longitude) {
        return createHealingSpot(course == null ? null : course.getCourseId(), code, name, latitude, longitude);
    }

    public HealingSpot createHealingSpot(Long courseId, String code, String name,
            Double latitude, Double longitude) {
        siteRepository.lockForMembershipChange();
        HealingCourse course = requireCourse(courseId);
        if (healingSpotRepository.existsByHealingCourseSiteSiteIdAndCode(course.getSite().getSiteId(), code)) {
            throw new IllegalArgumentException("선택한 사이트에 이미 등록된 HS 코드입니다.");
        }
        return healingSpotRepository.save(new HealingSpot(course, code, name, latitude, longitude));
    }

    @Transactional(readOnly = true)
    public List<HealingSpot> findBySiteId(Long siteId) {
        return healingSpotRepository.findByHealingCourseSiteSiteId(siteId);
    }

    @Transactional(readOnly = true)
    public List<HealingSpot> findByCourseId(Long courseId) {
        requireCourse(courseId);
        return healingSpotRepository.findByHealingCourseCourseId(courseId);
    }

    @Transactional(readOnly = true)
    public HealingSpotDetailResponse findDetail(Long siteId, Long spotId, boolean includeImages) {
        HealingSpot spot = findHealingSpot(spotId);
        if (!spot.getHealingCourse().getSite().getSiteId().equals(siteId)) {
            throw new IllegalArgumentException("선택한 사이트에 속한 HealingSpot이 아닙니다.");
        }
        return HealingSpotDetailResponse.from(spot,
                includeImages ? healingSpotImageService.list(spotId) : List.of());
    }

    @Transactional(readOnly = true)
    public HealingSpot findHealingSpot(Long spotId) {
        return healingSpotRepository.findById(spotId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 HealingSpot입니다."));
    }

    public void updateHealingSpot(Long spotId, Long courseId, String code, String name,
            Double latitude, Double longitude) {
        siteRepository.lockForMembershipChange();
        HealingSpot spot = findHealingSpot(spotId);
        HealingCourse course = requireCourse(courseId);
        if (healingSpotRepository.existsByHealingCourseSiteSiteIdAndCodeAndSpotIdNot(
                course.getSite().getSiteId(), code, spotId)) {
            throw new IllegalArgumentException("선택한 사이트에 이미 등록된 HS 코드입니다.");
        }
        if (!spot.getHealingCourse().getSite().getSiteId().equals(course.getSite().getSiteId()))
            positions.deleteByHealingSpotSpotId(spotId);
        spot.update(course, code, name, latitude, longitude);
    }

    public void deleteHealingSpot(Long spotId) {
        siteRepository.lockForMembershipChange();
        HealingSpot spot = healingSpotRepository.findLockedById(spotId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 HealingSpot입니다."));
        if (scheduleSpotRepository.existsByHealingSpotSpotId(spotId)) {
            throw new IllegalArgumentException("일정에 사용 중인 HealingSpot은 삭제할 수 없습니다.");
        }
        healingSpotImageService.deleteAll(spotId);
        positions.deleteByHealingSpotSpotId(spotId);
        memberEffectSummaries.deleteByHealingSpotSpotId(spotId);
        effectSummaries.deleteByHealingSpotSpotId(spotId);
        memberEffectSummaries.flush();
        effectSummaries.flush();
        healingSpotRepository.delete(spot);
        healingSpotRepository.flush();
    }

    private HealingCourse requireCourse(Long courseId) {
        if (courseId == null)
            throw new IllegalArgumentException("코스를 선택해 주세요.");
        return healingCourseRepository.findById(courseId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 HealingCourse입니다."));
    }
}
