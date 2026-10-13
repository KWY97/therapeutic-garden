package com.example.manage.controller;

import com.example.manage.service.ImageHttpResponseService;
import com.example.manage.service.MemberMonitoringImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/member/api/monitoring")
public class MemberMonitoringImageController {
    private final MemberMonitoringImageService monitoringImages;
    private final ImageHttpResponseService imageResponses;

    @GetMapping("/site-image")
    public ResponseEntity<byte[]> siteImage() {
        try {
            var content = monitoringImages.siteImage();
            return imageResponses.privateNoStore(content.bytes(), content.contentType());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/spots/{spotId}/thumbnail")
    public ResponseEntity<byte[]> spotThumbnail(@PathVariable Long spotId) {
        try {
            var content = monitoringImages.spotThumbnail(spotId);
            return imageResponses.privateNoStore(content.bytes(), content.contentType());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
    }
}
