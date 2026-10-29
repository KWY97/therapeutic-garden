package com.example.manage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_effect_import_sha", columnNames = "source_sha256"))
public class HealingEffectImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String sourceSha256;

    @Column(nullable = false)
    private String sourceFilename;

    /** UTC. Recorded in the same transaction as both summary tables. */
    @Column(nullable = false)
    private LocalDateTime importedAt;

    @Column(nullable = false)
    private Integer overallSummaryCount;

    @Column(nullable = false)
    private Integer participantSummaryCount;

    @Column(nullable = false)
    private Long targetSiteId;

    public HealingEffectImportBatch(String sourceSha256, String sourceFilename, LocalDateTime importedAt,
            Integer overallSummaryCount, Integer participantSummaryCount, Long targetSiteId) {
        this.sourceSha256 = sourceSha256;
        this.sourceFilename = sourceFilename;
        this.importedAt = importedAt;
        this.overallSummaryCount = overallSummaryCount;
        this.participantSummaryCount = participantSummaryCount;
        this.targetSiteId = targetSiteId;
    }
}
