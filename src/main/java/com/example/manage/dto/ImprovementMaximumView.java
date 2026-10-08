package com.example.manage.dto;

import java.util.Set;

/** Spot codes tied for the strongest count-backed improvement in each metric. */
public record ImprovementMaximumView(Set<String> stressSpotCodes, Set<String> emotionalSpotCodes) {

    public ImprovementMaximumView {
        stressSpotCodes = Set.copyOf(stressSpotCodes);
        emotionalSpotCodes = Set.copyOf(emotionalSpotCodes);
    }

    public static ImprovementMaximumView empty() {
        return new ImprovementMaximumView(Set.of(), Set.of());
    }
}
