package com.example.manage.storage;

public class DisabledImageStorage implements ImageStorage {
    private ImageStorageException unavailable() {
        return new ImageStorageException("이미지 저장소가 설정되지 않았습니다. storage.mode와 Bucket 연결 설정을 확인해 주세요.");
    }

    public void upload(String key, byte[] content, String contentType) {
        throw unavailable();
    }

    public boolean exists(String key) {
        throw unavailable();
    }

    public void delete(String key) {
        throw unavailable();
    }

    public String createReadUrl(String key, String localReadPath) {
        throw unavailable();
    }

    public byte[] read(String key) {
        throw unavailable();
    }
}
