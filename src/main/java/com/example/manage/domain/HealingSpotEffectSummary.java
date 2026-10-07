package com.example.manage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Entity
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_spot_effect",
        columnNames = {"spot_id"}))
public class HealingSpotEffectSummary {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "spot_id", nullable = false)
    private HealingSpot healingSpot;

    @Column(nullable = false)
    private Integer stressParticipantCount;

    @Column(nullable = false)
    private Integer stressValidSessionCount;

    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal stressReductionRate;

    @Column(nullable = false)
    private Integer emotionalParticipantCount;

    @Column(nullable = false)
    private Integer emotionalValidSessionCount;

    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal emotionalIncreaseRate;

    /** Nullable during the safe phase-1 backfill of pre-existing summary rows. */
    private Integer participantCount;

    /** One expected Spot observation in a Course measurement, including invalid measurements. */
    private Integer totalExperienceCount;

    private Integer stressImprovedCount;

    private Integer emotionalImprovedCount;

    public HealingSpotEffectSummary(HealingSpot healingSpot,
            Integer stressParticipantCount, Integer stressValidSessionCount, BigDecimal stressReductionRate,
            Integer emotionalParticipantCount, Integer emotionalValidSessionCount, BigDecimal emotionalIncreaseRate) {

        this.healingSpot = healingSpot;
        this.stressParticipantCount = stressParticipantCount;
        this.stressValidSessionCount = stressValidSessionCount;
        this.stressReductionRate = stressReductionRate;
        this.emotionalParticipantCount = emotionalParticipantCount;
        this.emotionalValidSessionCount = emotionalValidSessionCount;
        this.emotionalIncreaseRate = emotionalIncreaseRate;
    }

    public void updateImprovementCounts(Integer participantCount, Integer totalExperienceCount,
            Integer stressValidCount, Integer stressImprovedCount,
            Integer emotionalValidCount, Integer emotionalImprovedCount) {
        requireCounts(totalExperienceCount, stressValidCount, stressImprovedCount,
                emotionalValidCount, emotionalImprovedCount);
        if (participantCount == null || participantCount < 0 || participantCount > totalExperienceCount)
            throw new IllegalArgumentException("invalid participant count");
        this.participantCount = participantCount;
        this.totalExperienceCount = totalExperienceCount;
        this.stressValidSessionCount = stressValidCount;
        this.stressImprovedCount = stressImprovedCount;
        this.emotionalValidSessionCount = emotionalValidCount;
        this.emotionalImprovedCount = emotionalImprovedCount;
    }

    private static void requireCounts(Integer total, Integer stressValid, Integer stressImproved,
            Integer emotionalValid, Integer emotionalImproved) {
        if (total == null || stressValid == null || stressImproved == null
                || emotionalValid == null || emotionalImproved == null
                || total < 0 || stressValid < 0 || stressImproved < 0
                || emotionalValid < 0 || emotionalImproved < 0
                || stressValid > total || emotionalValid > total
                || stressImproved > stressValid || emotionalImproved > emotionalValid)
            throw new IllegalArgumentException("invalid improvement counts");
    }
}
