package com.example.manage.service;

import com.example.manage.dto.MemberMonitoringSpotEffectView;
import com.example.manage.repository.HealingEffectImportBatchRepository;
import com.example.manage.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;

@Service
@RequiredArgsConstructor
public class MemberAnalysisModelService {
    private final HealingEffectQueryService healingEffects;
    private final MeasurementHistoryQueryService measurementHistory;
    private final HealingEffectImportBatchRepository effectBatches;
    private final MemberRepository members;

    public void populateForLoggedInMember(Long memberId, String memberName, Model model) {
        var member = members.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("참가자 계정을 찾을 수 없습니다."));
        Long analysisMemberId = memberId;
        if (member.isTestAccount()) {
            Long referenceId = member.getAnalysisReferenceMemberId();
            var reference = referenceId == null ? null : members.findById(referenceId).orElse(null);
            if (!"test004".equals(member.getLoginId()) || reference == null
                    || reference.isTestAccount() || !Integer.valueOf(4).equals(reference.getParticipantNo()))
                throw new IllegalArgumentException("테스트 분석 참조가 유효하지 않습니다.");
            analysisMemberId = reference.getMemberId();
        }
        model.addAttribute("testAccount", member.isTestAccount());
        populate(analysisMemberId, memberName, model);
    }

    public void populate(Long memberId, String memberName, Model model) {
        var spotEffects = healingEffects.findImportedMemberImprovements(memberId);
        model.addAttribute("spotEffects", spotEffects);
        model.addAttribute("maximumImprovements", healingEffects.findMaximumImprovements(spotEffects));
        model.addAttribute("memberMonitoringSiteId", effectBatches.findFirstByOrderByIdAsc()
                .map(batch -> batch.getTargetSiteId()).orElse(null));
        model.addAttribute("memberMonitoringEffects", spotEffects.stream()
                .map(MemberMonitoringSpotEffectView::from).toList());
        model.addAttribute("memberDisplayName", memberName == null || memberName.isBlank() ? "참가자" : memberName);
        model.addAttribute("overallImprovement", healingEffects.findImportedMemberOverallImprovement(memberId).orElse(null));
        model.addAttribute("measurementHistory", measurementHistory.findImportedMember(memberId));
    }
}
