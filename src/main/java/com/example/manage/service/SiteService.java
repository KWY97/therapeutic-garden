package com.example.manage.service;

import com.example.manage.domain.Site;
import com.example.manage.repository.HealingCourseRepository;
import com.example.manage.repository.SiteRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class SiteService {

    private final SiteRepository siteRepository;
    private final SiteImageService siteImageService;
    private final HealingCourseRepository healingCourseRepository;

    // 실제 Site 등록
    public Site createSite(
            String name,
            String address,
            Double latitude,
            Double longitude,
            Integer mapLevel
    ) {
        if (siteRepository.existsByName(name)) {
            throw new IllegalArgumentException("이미 등록된 사이트명입니다.");
        }

        Site site = new Site(
                name,
                address,
                latitude,
                longitude,
                mapLevel
        );

        return siteRepository.save(site);
    }

    @Transactional(readOnly = true)
    public List<Site> findAllSites() {
        return siteRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Site findSite(Long siteId) {
        return siteRepository.findById(siteId).orElse(null);
    }

    public void updateSite(Long siteId, String name, String address,
            Double latitude, Double longitude, Integer mapLevel) {
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사이트입니다."));
        if (siteRepository.existsByNameAndSiteIdNot(name, siteId)) {
            throw new IllegalArgumentException("이미 등록된 사이트명입니다.");
        }
        site.update(name, address, latitude, longitude, mapLevel);
    }

    public void deleteSite(Long siteId) {
        Site site = siteRepository.findLockedById(siteId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사이트입니다."));
        if (healingCourseRepository.existsBySiteSiteId(siteId)) {
            throw new IllegalArgumentException("등록된 HealingCourse가 존재하는 사이트는 삭제할 수 없습니다.");
        }
        siteImageService.deleteAll(siteId);
        siteRepository.delete(site);
        siteRepository.flush();
    }
}
