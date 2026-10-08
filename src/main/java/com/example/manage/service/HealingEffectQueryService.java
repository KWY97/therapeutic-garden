package com.example.manage.service;

import com.example.manage.dto.HealingEffectView;
import com.example.manage.dto.HealingSpotImprovementView;
import com.example.manage.dto.ImprovementMaximumView;
import com.example.manage.dto.ImprovementMetricView;
import com.example.manage.dto.MonitoringSpotEffectView;
import com.example.manage.dto.ParticipantOverallImprovementView;
import com.example.manage.domain.MemberHealingSpotEffectSummary;
import com.example.manage.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HealingEffectQueryService {
    private final HealingSpotEffectSummaryRepository overall;
    private final MemberHealingSpotEffectSummaryRepository participants;
    private final HealingSpotRepository spots;
    private final MemberRepository members;
    private final HealingEffectImportBatchRepository batches;

    /** The successful batch determines the dataset Site; no hardcoded Site or Member identity. */
    public List<HealingEffectView> findPublishedOverall() {
        return batches.findFirstByOrderByIdAsc().map(b -> findOverall(b.getTargetSiteId())).orElse(List.of());
    }

    public List<HealingEffectView> findAnonymousExample() {
        return batches.findFirstByOrderByIdAsc().map(b -> {
            var candidates = participants.findCompleteExampleMemberIds(b.getTargetSiteId());
            if (candidates.isEmpty()) return List.<HealingEffectView>of();
            var views = findMember(b.getTargetSiteId(), candidates.getFirst());
            // Do not publish a partial or ambiguous example after Spot edits/deletions.
            return views.size() == 6 && views.stream().allMatch(HealingEffectView::hasMeasurement)
                    ? views : List.<HealingEffectView>of();
        }).orElse(List.of());
    }

    /** Admin identity stays in the existing protected Member model, never in effect DTOs. */
    public List<HealingEffectView> findImportedMember(Long memberId) {
        return batches.findFirstByOrderByIdAsc().map(b -> findMember(b.getTargetSiteId(), memberId)).orElse(List.of());
    }

    public List<HealingEffectView> findOverall(Long siteId) {
        return overall.findByHealingSpotHealingCourseSiteSiteIdOrderByHealingSpotCodeAsc(siteId).stream()
                .map(s -> HealingEffectView.measured(s.getHealingSpot().getCode(), s.getHealingSpot().getName(),
                        s.getStressReductionRate(), s.getEmotionalIncreaseRate(),
                        s.getStressValidSessionCount(), s.getEmotionalValidSessionCount())).toList();
    }

    public List<HealingEffectView> findMember(Long siteId, Long memberId) {
        if (!members.existsById(memberId)) throw new IllegalArgumentException("존재하지 않는 Member입니다.");
        var measurements = participants
                .findByMemberMemberIdAndHealingSpotHealingCourseSiteSiteIdOrderByHealingSpotCodeAsc(memberId, siteId)
                .stream().collect(Collectors.toMap(s -> s.getHealingSpot().getSpotId(), Function.identity()));
        return spots.findByHealingCourseSiteSiteId(siteId).stream()
                .filter(s -> s.getCode().matches("HS[1-6]"))
                .sorted(Comparator.comparing(s -> s.getCode()))
                .map(spot -> {
                    var s = measurements.get(spot.getSpotId());
                    return s == null ? HealingEffectView.missing(spot.getCode(), spot.getName())
                            : HealingEffectView.measured(spot.getCode(), spot.getName(),
                                    s.getStressReductionRate(), s.getEmotionalIncreaseRate(),
                                    s.getStressValidSessionCount(), s.getEmotionalValidSessionCount());
                }).toList();
    }

    /** Builds the Site-wide Summary-only snapshot used by spatial Monitoring. */
    public List<MonitoringSpotEffectView> findMonitoringOverallForSite(Long siteId) {
        var siteSpots = spots.findByHealingCourseSiteSiteId(siteId).stream()
                .filter(spot -> spot.getCode().matches("HS[1-6]"))
                .sorted(Comparator.comparing(com.example.manage.domain.HealingSpot::getCode))
                .toList();

        var overallBySpot = overall.findByHealingSpotHealingCourseSiteSiteIdOrderByHealingSpotCodeAsc(siteId).stream()
                .collect(Collectors.toMap(s -> s.getHealingSpot().getSpotId(), Function.identity()));
        return siteSpots.stream().map(spot -> {
            var summary = overallBySpot.get(spot.getSpotId());
            return summary == null ? MonitoringSpotEffectView.missing(spot.getCode(), spot.getName())
                    : MonitoringSpotEffectView.overall(summary);
        }).toList();
    }

    /** Count-backed UI snapshot for the currently published dataset. */
    public List<HealingSpotImprovementView> findPublishedOverallImprovements() {
        return batches.findFirstByOrderByIdAsc()
                .map(batch -> findOverallImprovements(batch.getTargetSiteId()))
                .filter(views -> views.size() == 6)
                .orElse(List.of());
    }

    /** Uses the same stable, complete anonymous example selection as the legacy view. */
    public List<HealingSpotImprovementView> findAnonymousExampleImprovements() {
        return batches.findFirstByOrderByIdAsc().map(batch -> {
            var candidates = participants.findCompleteExampleMemberIds(batch.getTargetSiteId());
            if (candidates.isEmpty()) return List.<HealingSpotImprovementView>of();
            var views = findMemberImprovements(batch.getTargetSiteId(), candidates.getFirst());
            return views.size() == 6 ? views : List.<HealingSpotImprovementView>of();
        }).orElse(List.of());
    }

    /** Public landing example selected by participant number, never by Member primary key. */
    public List<HealingSpotImprovementView> findPublishedMemberImprovementsByParticipantNo(Integer participantNo) {
        if (participantNo == null) return List.of();
        return batches.findFirstByOrderByIdAsc()
                .flatMap(batch -> members.findByParticipantNo(participantNo)
                        .map(member -> findMemberImprovements(batch.getTargetSiteId(), member.getMemberId())))
                .filter(this::hasAllSixHealingSpots)
                .orElse(List.of());
    }

    /** Highest rate wins, then highest valid count; exact ties remain joint maxima. */
    public ImprovementMaximumView findMaximumImprovements(List<HealingSpotImprovementView> views) {
        if (views == null || views.isEmpty()) return ImprovementMaximumView.empty();
        return new ImprovementMaximumView(
                findMaximumSpotCodes(views, HealingSpotImprovementView::stress),
                findMaximumSpotCodes(views, HealingSpotImprovementView::emotional));
    }

    /** Authenticated participant UI data, including explicit missing HS1-HS6 entries. */
    public List<HealingSpotImprovementView> findImportedMemberImprovements(Long memberId) {
        return batches.findFirstByOrderByIdAsc()
                .map(batch -> findMemberImprovementsWithMissing(batch.getTargetSiteId(), memberId))
                .orElse(List.of());
    }

    public Optional<ParticipantOverallImprovementView> findImportedMemberOverallImprovement(Long memberId) {
        return batches.findFirstByOrderByIdAsc()
                .flatMap(batch -> findParticipantOverallImprovement(batch.getTargetSiteId(), memberId));
    }

    public List<HealingSpotImprovementView> findOverallImprovements(Long siteId) {
        var summaries = overall.findByHealingSpotHealingCourseSiteSiteIdOrderByHealingSpotCodeAsc(siteId);
        if (summaries.stream().anyMatch(summary -> !hasImprovementCounts(summary))) return List.of();
        return summaries.stream()
                .map(summary -> new HealingSpotImprovementView(
                        summary.getHealingSpot().getCode(), summary.getHealingSpot().getName(),
                        summary.getParticipantCount(), summary.getTotalExperienceCount(),
                        ImprovementMetricView.of(summary.getStressValidSessionCount(), summary.getStressImprovedCount()),
                        ImprovementMetricView.of(summary.getEmotionalValidSessionCount(), summary.getEmotionalImprovedCount())))
                .toList();
    }

    public List<HealingSpotImprovementView> findMemberImprovements(Long siteId, Long memberId) {
        if (!members.existsById(memberId)) throw new IllegalArgumentException("존재하지 않는 Member입니다.");
        var summaries = participants
                .findByMemberMemberIdAndHealingSpotHealingCourseSiteSiteIdOrderByHealingSpotCodeAsc(memberId, siteId);
        if (summaries.stream().anyMatch(summary -> !hasImprovementCounts(summary))) return List.of();
        return summaries.stream()
                .map(summary -> new HealingSpotImprovementView(
                        summary.getHealingSpot().getCode(), summary.getHealingSpot().getName(), null,
                        summary.getTotalExperienceCount(),
                        ImprovementMetricView.of(summary.getStressValidSessionCount(), summary.getStressImprovedCount()),
                        ImprovementMetricView.of(summary.getEmotionalValidSessionCount(), summary.getEmotionalImprovedCount())))
                .toList();
    }

    private List<HealingSpotImprovementView> findMemberImprovementsWithMissing(Long siteId, Long memberId) {
        if (!members.existsById(memberId)) throw new IllegalArgumentException("존재하지 않는 Member입니다.");
        var summaries = participants
                .findByMemberMemberIdAndHealingSpotHealingCourseSiteSiteIdOrderByHealingSpotCodeAsc(memberId, siteId);
        if (summaries.stream().anyMatch(summary -> !hasImprovementCounts(summary))) return List.of();
        var bySpot = summaries.stream().collect(Collectors.toMap(
                summary -> summary.getHealingSpot().getSpotId(), Function.identity()));
        return spots.findByHealingCourseSiteSiteId(siteId).stream()
                .filter(spot -> spot.getCode().matches("HS[1-6]"))
                .sorted(Comparator.comparing(com.example.manage.domain.HealingSpot::getCode))
                .map(spot -> {
                    var summary = bySpot.get(spot.getSpotId());
                    return summary == null
                            ? new HealingSpotImprovementView(spot.getCode(), spot.getName(), null, 0,
                                    ImprovementMetricView.of(0, 0), ImprovementMetricView.of(0, 0))
                            : new HealingSpotImprovementView(spot.getCode(), spot.getName(), null,
                                    summary.getTotalExperienceCount(),
                                    ImprovementMetricView.of(summary.getStressValidSessionCount(), summary.getStressImprovedCount()),
                                    ImprovementMetricView.of(summary.getEmotionalValidSessionCount(), summary.getEmotionalImprovedCount()));
                }).toList();
    }

    /** Participant overall is derived by summing Member x Spot source counts; it is not stored. */
    public Optional<ParticipantOverallImprovementView> findParticipantOverallImprovement(Long siteId, Long memberId) {
        if (!members.existsById(memberId)) throw new IllegalArgumentException("존재하지 않는 Member입니다.");
        var summaries = participants
                .findByMemberMemberIdAndHealingSpotHealingCourseSiteSiteIdOrderByHealingSpotCodeAsc(memberId, siteId);
        if (summaries.isEmpty() || summaries.stream().anyMatch(summary -> !hasImprovementCounts(summary)))
            return Optional.empty();
        int experiences = summaries.stream().mapToInt(MemberHealingSpotEffectSummary::getTotalExperienceCount).sum();
        int stressValid = summaries.stream().mapToInt(MemberHealingSpotEffectSummary::getStressValidSessionCount).sum();
        int stressImproved = summaries.stream().mapToInt(MemberHealingSpotEffectSummary::getStressImprovedCount).sum();
        int emotionalValid = summaries.stream().mapToInt(MemberHealingSpotEffectSummary::getEmotionalValidSessionCount).sum();
        int emotionalImproved = summaries.stream().mapToInt(MemberHealingSpotEffectSummary::getEmotionalImprovedCount).sum();
        return Optional.of(new ParticipantOverallImprovementView(experiences,
                ImprovementMetricView.of(stressValid, stressImproved),
                ImprovementMetricView.of(emotionalValid, emotionalImproved)));
    }

    private static boolean hasImprovementCounts(com.example.manage.domain.HealingSpotEffectSummary summary) {
        return summary.getParticipantCount() != null && summary.getTotalExperienceCount() != null
                && summary.getStressImprovedCount() != null && summary.getEmotionalImprovedCount() != null;
    }

    private static boolean hasImprovementCounts(MemberHealingSpotEffectSummary summary) {
        return summary.getTotalExperienceCount() != null
                && summary.getStressImprovedCount() != null && summary.getEmotionalImprovedCount() != null;
    }

    private boolean hasAllSixHealingSpots(List<HealingSpotImprovementView> views) {
        return views.size() == 6 && views.stream().map(HealingSpotImprovementView::spotCode)
                .collect(Collectors.toSet()).equals(Set.of("HS1", "HS2", "HS3", "HS4", "HS5", "HS6"));
    }

    private Set<String> findMaximumSpotCodes(List<HealingSpotImprovementView> views,
            Function<HealingSpotImprovementView, ImprovementMetricView> metricExtractor) {
        var available = views.stream().filter(view -> metricExtractor.apply(view).improvementRate() != null).toList();
        if (available.isEmpty()) return Set.of();
        var maximumRate = available.stream().map(metricExtractor)
                .map(ImprovementMetricView::improvementRate).max(java.math.BigDecimal::compareTo).orElseThrow();
        int maximumValidCount = available.stream().map(metricExtractor)
                .filter(metric -> metric.improvementRate().compareTo(maximumRate) == 0)
                .mapToInt(ImprovementMetricView::validCount).max().orElseThrow();
        return available.stream()
                .filter(view -> {
                    var metric = metricExtractor.apply(view);
                    return metric.improvementRate().compareTo(maximumRate) == 0
                            && metric.validCount() == maximumValidCount;
                })
                .map(HealingSpotImprovementView::spotCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
