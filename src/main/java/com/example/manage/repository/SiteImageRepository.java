package com.example.manage.repository;

import com.example.manage.domain.SiteImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SiteImageRepository extends JpaRepository<SiteImage, Long> {

    List<SiteImage> findBySiteSiteIdOrderByDisplayOrderAscImageIdAsc(Long parentId);
}
