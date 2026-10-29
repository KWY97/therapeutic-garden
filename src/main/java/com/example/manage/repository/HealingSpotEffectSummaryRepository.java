package com.example.manage.repository;

import com.example.manage.domain.HealingSpotEffectSummary;
import org.springframework.data.jpa.repository.*;

import java.util.*;

public interface HealingSpotEffectSummaryRepository extends JpaRepository<HealingSpotEffectSummary, Long> {

    @EntityGraph(attributePaths = "healingSpot")
    List<HealingSpotEffectSummary> findByHealingSpotHealingCourseSiteSiteIdOrderByHealingSpotCodeAsc(Long siteId);

    @EntityGraph(attributePaths = "healingSpot")
    Optional<HealingSpotEffectSummary> findByHealingSpotSpotId(Long spotId);

    void deleteByHealingSpotSpotId(Long spotId);
}
