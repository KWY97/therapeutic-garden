package com.example.manage.service;

import com.example.manage.domain.HealingCourse;
import com.example.manage.domain.Site;
import com.example.manage.repository.HealingCourseRepository;
import com.example.manage.repository.HealingSpotRepository;
import com.example.manage.repository.SiteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class HealingCourseService {

    private final HealingCourseRepository healingCourseRepository;
    private final SiteRepository siteRepository;
    private final com.example.manage.repository.SiteImageSpotPositionRepository positions;
    private final HealingSpotRepository healingSpotRepository;

    public HealingCourse createHealingCourse(
            Site site,
            String code,
            String name,
            Double centerLatitude,
            Double centerLongitude,
            Double radius
    ) {
        return createHealingCourse(site == null ? null : site.getSiteId(), code, name,
                centerLatitude, centerLongitude, radius);
    }

    public HealingCourse createHealingCourse(Long siteId, String code, String name,
            Double centerLatitude, Double centerLongitude, Double radius) {
        Site site = requireSite(siteId);
        if (healingCourseRepository.existsBySiteSiteIdAndCode(siteId, code)) {
            throw new IllegalArgumentException("선택한 사이트에 이미 등록된 HC 코드입니다.");
        }

        HealingCourse healingCourse = new HealingCourse(
                site,
                code,
                name,
                centerLatitude,
                centerLongitude,
                radius
        );

        return healingCourseRepository.save(healingCourse);
    }

    @Transactional(readOnly = true)
    public List<HealingCourse> findAllHealingCourses() {
        return healingCourseRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<HealingCourse> findBySiteId(Long siteId) {
        return healingCourseRepository.findBySiteSiteId(siteId);
    }

    @Transactional(readOnly = true)
    public HealingCourse findHealingCourse(Long courseId) {
        return healingCourseRepository.findById(courseId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 HealingCourse입니다."));
    }

    public void updateHealingCourse(Long courseId, Long siteId, String code, String name,
            Double centerLatitude, Double centerLongitude, Double radius) {
        siteRepository.lockForMembershipChange();
        HealingCourse course = findHealingCourse(courseId);
        Site site = requireSite(siteId);
        if (healingCourseRepository.existsBySiteSiteIdAndCodeAndCourseIdNot(siteId, code, courseId)) {
            throw new IllegalArgumentException("선택한 사이트에 이미 등록된 HC 코드입니다.");
        }
        if (!course.getSite().getSiteId().equals(siteId))
            healingSpotRepository.findByHealingCourseCourseId(courseId)
                    .forEach(spot -> positions.deleteByHealingSpotSpotId(spot.getSpotId()));
        course.update(site, code, name, centerLatitude, centerLongitude, radius);
    }

    public void deleteHealingCourse(Long courseId) {
        siteRepository.lockForMembershipChange();
        HealingCourse course = findHealingCourse(courseId);
        if (healingSpotRepository.existsByHealingCourseCourseId(courseId)) {
            throw new IllegalArgumentException("등록된 HealingSpot이 존재하는 HealingCourse는 삭제할 수 없습니다.");
        }
        healingCourseRepository.delete(course);
    }

    private Site requireSite(Long siteId) {
        if (siteId == null) {
            throw new IllegalArgumentException("사이트를 선택해 주세요.");
        }
        return siteRepository.findById(siteId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사이트입니다."));
    }
}
