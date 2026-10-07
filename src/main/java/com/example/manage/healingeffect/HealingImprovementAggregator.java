package com.example.manage.healingeffect;

import com.example.manage.domain.HealingMeasurementRecord;
import java.util.*;

/** Pure count aggregation. Rates are deliberately derived outside the stored source counts. */
public final class HealingImprovementAggregator {
    public record MetricCounts(int validCount, int improvedCount) {
        public MetricCounts {
            if (validCount < 0 || improvedCount < 0 || improvedCount > validCount)
                throw new IllegalArgumentException("invalid improvement counts");
        }
    }
    public record MemberSpot(long memberId, long spotId, int totalExperienceCount,
            MetricCounts stress, MetricCounts emotional) {}
    public record OverallSpot(long spotId, int participantCount, int totalExperienceCount,
            MetricCounts stress, MetricCounts emotional) {}
    public record ParticipantOverall(long memberId, int totalExperienceCount,
            MetricCounts stress, MetricCounts emotional) {}
    public record Result(List<MemberSpot> memberSpots, List<OverallSpot> overallSpots,
            List<ParticipantOverall> participantOverall) {
        public Result {
            memberSpots = List.copyOf(memberSpots);
            overallSpots = List.copyOf(overallSpots);
            participantOverall = List.copyOf(participantOverall);
        }
    }

    private record MemberSpotKey(long memberId, long spotId) {}
    private static final class Counts {
        int experiences;
        int stressValid;
        int stressImproved;
        int emotionalValid;
        int emotionalImproved;

        void add(HealingMeasurementRecord row) {
            experiences++;
            if (row.stressValid()) stressValid++;
            if (row.stressImproved()) stressImproved++;
            if (row.emotionalValid()) emotionalValid++;
            if (row.emotionalImproved()) emotionalImproved++;
        }

        void add(Counts other) {
            experiences += other.experiences;
            stressValid += other.stressValid;
            stressImproved += other.stressImproved;
            emotionalValid += other.emotionalValid;
            emotionalImproved += other.emotionalImproved;
        }

        MetricCounts stress() { return new MetricCounts(stressValid, stressImproved); }
        MetricCounts emotional() { return new MetricCounts(emotionalValid, emotionalImproved); }
    }

    public Result aggregate(Collection<HealingMeasurementRecord> records) {
        var byMemberSpot = new LinkedHashMap<MemberSpotKey, Counts>();
        for (var row : records) {
            if (row.memberId() == null || row.spotId() == null)
                throw new IllegalArgumentException("memberId and spotId are required");
            byMemberSpot.computeIfAbsent(new MemberSpotKey(row.memberId(), row.spotId()), key -> new Counts()).add(row);
        }

        var memberSpots = byMemberSpot.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingLong(MemberSpotKey::memberId)
                        .thenComparingLong(MemberSpotKey::spotId)))
                .map(entry -> new MemberSpot(entry.getKey().memberId(), entry.getKey().spotId(),
                        entry.getValue().experiences, entry.getValue().stress(), entry.getValue().emotional()))
                .toList();

        var bySpot = new TreeMap<Long, Counts>();
        var participantsBySpot = new HashMap<Long, Set<Long>>();
        var byMember = new TreeMap<Long, Counts>();
        byMemberSpot.forEach((key, counts) -> {
            bySpot.computeIfAbsent(key.spotId(), ignored -> new Counts()).add(counts);
            participantsBySpot.computeIfAbsent(key.spotId(), ignored -> new HashSet<>()).add(key.memberId());
            byMember.computeIfAbsent(key.memberId(), ignored -> new Counts()).add(counts);
        });

        var overallSpots = bySpot.entrySet().stream()
                .map(entry -> new OverallSpot(entry.getKey(), participantsBySpot.get(entry.getKey()).size(),
                        entry.getValue().experiences, entry.getValue().stress(), entry.getValue().emotional()))
                .toList();
        var participantOverall = byMember.entrySet().stream()
                .map(entry -> new ParticipantOverall(entry.getKey(), entry.getValue().experiences,
                        entry.getValue().stress(), entry.getValue().emotional()))
                .toList();
        return new Result(memberSpots, overallSpots, participantOverall);
    }
}
