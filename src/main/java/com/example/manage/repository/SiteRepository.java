package com.example.manage.repository;

import com.example.manage.domain.Site;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteRepository extends JpaRepository<Site, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from Site p where p.siteId = :id")
    java.util.Optional<Site> findLockedById(@org.springframework.data.repository.query.Param("id") Long id);

    // Membership changes serialize with spatial saves, including cross-Site moves.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from Site s order by s.siteId")
    java.util.List<Site> lockForMembershipChange();

    boolean existsByName(String name);

    boolean existsByNameAndSiteIdNot(String name, Long siteId);
}
