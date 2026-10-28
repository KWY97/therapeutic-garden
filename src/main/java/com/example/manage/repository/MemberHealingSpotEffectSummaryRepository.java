package com.example.manage.repository;

import com.example.manage.domain.MemberHealingSpotEffectSummary;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface MemberHealingSpotEffectSummaryRepository extends JpaRepository<MemberHealingSpotEffectSummary, Long> {
    @Query("""
            select s.member.memberId from MemberHealingSpotEffectSummary s
            where s.healingSpot.healingCourse.site.siteId = :siteId
              and s.member.testAccount = false
              and s.healingSpot.code in ('HS1','HS2','HS3','HS4','HS5','HS6')
            group by s.member.memberId, s.member.participantNo
            having count(s) = 6 and count(distinct s.healingSpot.code) = 6
            order by s.member.participantNo, s.member.memberId
            """)
    List<Long> findCompleteExampleMemberIds(@org.springframework.data.repository.query.Param("siteId") Long siteId);
    @EntityGraph(attributePaths = "healingSpot")
    List<MemberHealingSpotEffectSummary> findByMemberMemberIdOrderByHealingSpotCodeAsc(Long memberId);
    @EntityGraph(attributePaths = "healingSpot")
    List<MemberHealingSpotEffectSummary> findByMemberMemberIdAndHealingSpotHealingCourseSiteSiteIdOrderByHealingSpotCodeAsc(
            Long memberId, Long siteId);
    @EntityGraph(attributePaths = "healingSpot")
    Optional<MemberHealingSpotEffectSummary> findByMemberMemberIdAndHealingSpotSpotId(Long memberId, Long spotId);
    void deleteByMemberMemberId(Long memberId);
    void deleteByHealingSpotSpotId(Long spotId);
}
