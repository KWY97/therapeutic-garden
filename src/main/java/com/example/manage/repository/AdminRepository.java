package com.example.manage.repository;

import com.example.manage.domain.Admin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminRepository extends JpaRepository<Admin, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from Admin a where a.adminId = :id")
    Optional<Admin> findLockedById(@org.springframework.data.repository.query.Param("id") Long id);

    // 로그인 시 사용
    Optional<Admin> findByLoginId(String loginId);
}
