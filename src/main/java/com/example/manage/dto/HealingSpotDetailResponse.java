package com.example.manage.dto;

import com.example.manage.domain.HealingSpot;

import java.util.List;

public record HealingSpotDetailResponse(
        Long spotId, String code, String name, Double latitude, Double longitude,
        Long courseId, String courseCode, String courseName,
        Long siteId, String siteName, String siteAddress,
        String representativeImageUrl, List<ImageResponse> images) {

    public static HealingSpotDetailResponse from(HealingSpot spot, List<ImageResponse> images) {
        var course = spot.getHealingCourse();
        var site = course.getSite();
        return new HealingSpotDetailResponse(
                spot.getSpotId(), spot.getCode(), spot.getName(), spot.getLatitude(), spot.getLongitude(),
                course.getCourseId(), course.getCode(), course.getName(),
                site.getSiteId(), site.getName(), site.getAddress(),
                images.stream().filter(ImageResponse::representative).map(ImageResponse::readUrl)
                        .findFirst().orElse(null), images);
    }
}
