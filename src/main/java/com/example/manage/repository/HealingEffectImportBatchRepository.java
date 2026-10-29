package com.example.manage.repository;

import com.example.manage.domain.HealingEffectImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HealingEffectImportBatchRepository extends JpaRepository<HealingEffectImportBatch, Long> {

    Optional<HealingEffectImportBatch> findFirstByOrderByIdAsc();

    Optional<HealingEffectImportBatch> findBySourceSha256(String sourceSha256);
}
