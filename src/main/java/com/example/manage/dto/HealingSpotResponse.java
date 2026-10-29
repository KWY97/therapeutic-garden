package com.example.manage.dto;

import com.example.manage.domain.HealingSpot;
import lombok.Getter;

@Getter
public class HealingSpotResponse {

    private final Long spotId;
    private final String code;
    private final String name;
    private final Double latitude;
    private final Double longitude;

    private final Long courseId;
    private final String courseCode;
    private final String courseName;

    private final String representativeImageUrl;
    private final java.util.List<ImageResponse> images;

    public HealingSpotResponse(HealingSpot healingSpot) {
        this(healingSpot, null);
    }

    public HealingSpotResponse(HealingSpot healingSpot, String representativeImageUrl) {
        this(healingSpot, representativeImageUrl, java.util.List.of());
    }

    public HealingSpotResponse(HealingSpot healingSpot, String representativeImageUrl,
            java.util.List<ImageResponse> images) {
        this.images = images;
        this.representativeImageUrl = representativeImageUrl;

        this.spotId = healingSpot.getSpotId();
        this.code = healingSpot.getCode();
        this.name = healingSpot.getName();
        this.latitude = healingSpot.getLatitude();
        this.longitude = healingSpot.getLongitude();

        this.courseId = healingSpot.getHealingCourse().getCourseId();

        this.courseCode = healingSpot.getHealingCourse().getCode();

        this.courseName = healingSpot.getHealingCourse().getName();
    }
}
