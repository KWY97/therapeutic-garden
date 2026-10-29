package com.example.manage.repository;

import com.example.manage.domain.HealingSpotImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HealingSpotImageRepository extends JpaRepository<HealingSpotImage, Long> {

    List<HealingSpotImage> findByHealingSpotSpotIdOrderByDisplayOrderAscImageIdAsc(Long parentId);
}
