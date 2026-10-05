package com.example.manage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Entity
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_member_spot_effect",
        columnNames = {"member_id", "spot_id"}))
public class MemberHealingSpotEffectSummary {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "spot_id", nullable = false)
    private HealingSpot healingSpot;

    @Column(nullable = false)
    private Integer stressValidSessionCount;

    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal stressReductionRate;

    @Column(nullable = false)
    private Integer emotionalValidSessionCount;

    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal emotionalIncreaseRate;

    /** Nullable during the safe phase-1 backfill of pre-existing summary rows. */
    private Integer totalExperienceCount;

    private Integer stressImprovedCount;

    private Integer emotionalImprovedCount;

    public MemberHealingSpotEffectSummary(Member member, HealingSpot healingSpot,
            Integer stressValidSessionCount, BigDecimal stressReductionRate,
            Integer emotionalValidSessionCount, BigDecimal emotionalIncreaseRate) {
        this.member = member;
        this.healingSpot = healingSpot;

        this.stressValidSessionCount = stressValidSessionCount;
        this.stressReductionRate = stressReductionRate;

        this.emotionalValidSessionCount = emotionalValidSessionCount;
        this.emotionalIncreaseRate = emotionalIncreaseRate;
    }

    public void updateImprovementCounts(Integer totalExperienceCount,
            Integer stressValidCount, Integer stressImprovedCount,
            Integer emotionalValidCount, Integer emotionalImprovedCount) {
        if (totalExperienceCount == null || stressValidCount == null || stressImprovedCount == null
                || emotionalValidCount == null || emotionalImprovedCount == null
                || totalExperienceCount < 0 || stressValidCount < 0 || stressImprovedCount < 0
                || emotionalValidCount < 0 || emotionalImprovedCount < 0
                || stressValidCount > totalExperienceCount || emotionalValidCount > totalExperienceCount
                || stressImprovedCount > stressValidCount || emotionalImprovedCount > emotionalValidCount)
            throw new IllegalArgumentException("invalid improvement counts");
        this.totalExperienceCount = totalExperienceCount;
        this.stressValidSessionCount = stressValidCount;
        this.stressImprovedCount = stressImprovedCount;
        this.emotionalValidSessionCount = emotionalValidCount;
        this.emotionalImprovedCount = emotionalImprovedCount;
    }
}
