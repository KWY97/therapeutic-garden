package com.example.manage.repository;

import com.example.manage.domain.HealingCourse;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HealingCourseRepository
        extends JpaRepository<HealingCourse, Long> {

    /*
     * 특정 Site에 속한 HealingCourse들을 조회한다.
     *
     * 메서드 이름 해석:
     *
     * findBySiteSiteId
     *
     * findBy
     *   → 조건에 맞는 데이터를 조회한다.
     *
     * Site
     *   → HealingCourse 엔티티 안의
     *     private Site site;
     *     이 필드를 의미한다.
     *
     * SiteId
     *   → 위에서 찾은 Site 객체 안의
     *     private Long siteId;
     *     필드를 의미한다.
     *
     * 즉,
     *
     * HealingCourse.site.siteId == 전달받은 siteId
     *
     * 인 HealingCourse들을 모두 조회한다.
     *
     * 예:
     *
     * findBySiteSiteId(1L)
     *
     * → site_id가 1인 Site에 속한
     *   모든 HealingCourse 조회
     *
     * DB 느낌:
     *
     * SELECT *
     * FROM healing_course
     * WHERE site_id = 1;
     */
    List<HealingCourse> findBySiteSiteId(Long siteId);

    @Override
    @EntityGraph(attributePaths = "site")
    Optional<HealingCourse> findById(Long courseId);

    @Override
    @EntityGraph(attributePaths = "site")
    List<HealingCourse> findAll();

    boolean existsBySiteSiteId(Long siteId);
    boolean existsBySiteSiteIdAndCode(Long siteId, String code);
    boolean existsBySiteSiteIdAndCodeAndCourseIdNot(Long siteId, String code, Long courseId);
}
