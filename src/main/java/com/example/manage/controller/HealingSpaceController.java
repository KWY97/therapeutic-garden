package com.example.manage.controller;

import com.example.manage.dto.HealingCourseResponse;
import com.example.manage.dto.HealingSpotDetailResponse;
import com.example.manage.dto.HealingSpotResponse;
import com.example.manage.service.HealingCourseService;
import com.example.manage.service.HealingSpotService;
import com.example.manage.service.SessionAuthentication;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/sites")
public class HealingSpaceController {

    private final HealingCourseService healingCourseService;
    private final SessionAuthentication sessionAuthentication;
    private final HealingSpotService healingSpotService;
    private final com.example.manage.service.HealingSpotImageService spotImages;

    @GetMapping("/{siteId}/spots/{spotId}")
    public ResponseEntity<HealingSpotDetailResponse> getHealingSpotDetail(
            @PathVariable Long siteId, @PathVariable Long spotId, jakarta.servlet.http.HttpSession session) {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(healingSpotService.findDetail(siteId, spotId,
                            sessionAuthentication.role(session) == SessionAuthentication.Role.ADMIN));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    /*
     * 특정 Site에 속한 HealingCourse 조회
     *
     * 예:
     * GET /api/sites/1/courses
     *
     * Entity를 그대로 반환하지 않고
     * 화면에 필요한 정보만 HealingCourseResponse DTO로 변환해서 반환한다.
     */
    @GetMapping("/{siteId}/courses")
    public List<HealingCourseResponse> getHealingCourses(
            @PathVariable Long siteId
    ) {

        return healingCourseService
                .findBySiteId(siteId)
                .stream()
                .map(HealingCourseResponse::new)
                .toList();
    }

    /*
     * 특정 Site에 속한 HealingSpot 조회
     *
     * 예:
     * GET /api/sites/1/spots
     */
    @GetMapping("/{siteId}/spots")
    public List<HealingSpotResponse> getHealingSpots(
            @PathVariable Long siteId, jakarta.servlet.http.HttpSession session,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Long spotId
    ) {

        boolean admin = sessionAuthentication.role(session) == SessionAuthentication.Role.ADMIN;
        return healingSpotService
                .findBySiteId(siteId)
                .stream()
                .filter(spot -> spotId == null || spot.getSpotId().equals(spotId))
                .map(spot -> {
                    var images = admin
                            ? spotImages.list(spot.getSpotId()) : List.<com.example.manage.dto.ImageResponse>of();
                    return new HealingSpotResponse(spot, images.stream().filter(com.example.manage.dto.ImageResponse::representative)
                            .map(com.example.manage.dto.ImageResponse::readUrl).findFirst().orElse(null), images);
                })
                .toList();
    }
}
