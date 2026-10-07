package com.example.manage.dto;

public record ParticipantOverallImprovementView(int totalExperienceCount,
        ImprovementMetricView stress, ImprovementMetricView emotional) {}
