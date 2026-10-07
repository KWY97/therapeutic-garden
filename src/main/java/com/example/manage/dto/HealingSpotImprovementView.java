package com.example.manage.dto;

public record HealingSpotImprovementView(String spotCode, String spotName, Integer participantCount,
        int totalExperienceCount, ImprovementMetricView stress, ImprovementMetricView emotional) {}
