package com.example.manage.service;

import com.example.manage.dto.MemberMonitoringSpotEffectView;
import com.example.manage.repository.HealingEffectImportBatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;

@Service
@RequiredArgsConstructor
public class MemberAnalysisModelService {
    private final HealingEffectQueryService healingEffects;
    private final MeasurementHistoryQueryService measurementHistory;
    private final HealingEffectImportBatchRepository effectBatches;

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
