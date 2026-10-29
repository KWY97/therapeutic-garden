package com.example.manage.repository;

import com.example.manage.domain.SiteImageSpotPosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SiteImageSpotPositionRepository extends JpaRepository<SiteImageSpotPosition, Long> {

    List<SiteImageSpotPosition> findBySiteImageImageId(Long imageId);

    void deleteBySiteImageImageId(Long imageId);

    void deleteByHealingSpotSpotId(Long spotId);
}
