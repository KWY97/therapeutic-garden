package com.example.manage.service;

import com.example.manage.dto.MeasurementHistoryView;
import com.example.manage.repository.*;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeasurementHistoryQueryService {

    private final HealingMeasurementRepository measurements;
    private final HealingSpotRepository spots;
    private final HealingEffectImportBatchRepository batches;
    private final PersonalEegMeasurementRepository personalEeg;

    public List<MeasurementHistoryView> findImportedMember(Long memberId) {
        var active = personalEeg.findActiveMember(memberId);
        if (active.isPresent())
            return views(active.get().siteId(), active.get().rows());
        return batches.findFirstByOrderByIdAsc()
                .map(b -> views(b.getTargetSiteId(), measurements.findMemberForSite(memberId, b.getTargetSiteId())))
                .orElse(List.of());
    }

    public List<MeasurementHistoryView> findMemberForSite(Long memberId, Long siteId) {
        var active = personalEeg.findActiveMember(memberId);
        var measurementsForSite = active.isPresent() && active.get().siteId() == siteId
                ? active.get().rows() : measurements.findMemberForSite(memberId, siteId);
        return views(siteId, measurementsForSite);
    }

    private List<MeasurementHistoryView> views(Long siteId, List<com.example.manage.domain.HealingMeasurementRecord> source) {
        var rows = source.stream().collect(Collectors.groupingBy(r -> r.spotId()));
        return spots.findByHealingCourseSiteSiteId(siteId).stream().filter(s -> s.getCode().matches("HS[1-6]"))
                .sorted(Comparator.comparing(s -> s.getCode()))
                .map(s -> new MeasurementHistoryView(s.getCode(), s.getName(), rows.getOrDefault(s.getSpotId(), List.of()).stream()
                        .map(MeasurementHistoryView.Record::from).toList())).toList();
    }
}
