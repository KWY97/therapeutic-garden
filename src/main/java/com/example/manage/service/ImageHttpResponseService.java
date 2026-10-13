package com.example.manage.service;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class ImageHttpResponseService {

    public ResponseEntity<byte[]> noStore(byte[] bytes, String contentType) {
        return image(bytes, contentType, CacheControl.noStore());
    }

    public ResponseEntity<byte[]> privateNoStore(byte[] bytes, String contentType) {
        return image(bytes, contentType, CacheControl.noStore().cachePrivate());
    }

    private ResponseEntity<byte[]> image(byte[] bytes, String contentType, CacheControl cacheControl) {
        return ResponseEntity.ok().cacheControl(cacheControl)
                .contentType(MediaType.parseMediaType(contentType))
                .header("X-Content-Type-Options", "nosniff")
                .body(bytes);
    }
}
