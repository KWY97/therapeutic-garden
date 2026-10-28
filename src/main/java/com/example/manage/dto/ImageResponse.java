package com.example.manage.dto;

public record ImageResponse(Long imageId, String originalFileName, String contentType,
        int displayOrder, boolean representative, String readUrl, boolean spatial) {
    public ImageResponse(Long imageId, String originalFileName, String contentType,
            int displayOrder, boolean representative, String readUrl) {
        this(imageId, originalFileName, contentType, displayOrder, representative, readUrl, false);
    }
}
