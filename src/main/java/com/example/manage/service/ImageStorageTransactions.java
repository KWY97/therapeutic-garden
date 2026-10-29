package com.example.manage.service;

import com.example.manage.config.ImageStorageProperties;
import com.example.manage.storage.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.*;

/** Best-effort compensation, including failures at DB commit (not only save/flush). */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImageStorageTransactions {

    private final ImageStorage storage;
    private final ImageStorageProperties properties;

    public void upload(String key, byte[] bytes, String contentType) {
        requireTransaction();
        // Register before PUT: a timeout can mean the server already stored the object.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED)
                    return;
                if (status == STATUS_UNKNOWN) {
                    log.error("Image upload transaction outcome unknown; reconcile objectKey={}", key);
                    return;
                }
                try {
                    storage.delete(key);
                } catch (RuntimeException e) {
                    // Never log SDK exceptions, credentials or presigned URLs.
                    log.error("Image upload compensation failed; reconcile objectKey={}", key);
                }
            }
        });
        storage.upload(key, bytes, contentType);
    }

    public void delete(String key, String contentType) {
        requireTransaction();
        Path backup;
        try {
            Path directory = properties.recoveryDirectory().toAbsolutePath().normalize();
            Files.createDirectories(directory);
            backup = Files.createTempFile(directory, "image-", ".backup");
            try {
                Files.write(backup, storage.read(key));
                Files.writeString(metadata(backup), key + "\n" + contentType + "\n");
            } catch (RuntimeException | IOException e) {
                Files.deleteIfExists(backup);
                Files.deleteIfExists(metadata(backup));
                throw e;
            }
        } catch (IOException e) {
            throw new ImageStorageException("이미지 삭제 복구 파일을 준비하지 못했습니다. 삭제하지 않았습니다.", e);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_UNKNOWN) {
                    log.error("Image deletion transaction outcome unknown; reconcile backup={}", backup);
                    return;
                }
                if (status != STATUS_COMMITTED) {
                    try {
                        storage.upload(key, Files.readAllBytes(backup), contentType);
                    } catch (RuntimeException | IOException e) {
                        log.error("Image deletion rollback restore failed; recover objectKey={} from backup={}", key, backup);
                        return; // Preserve bytes and metadata for manual recovery.
                    }
                }
                try {
                    Files.deleteIfExists(backup);
                    Files.deleteIfExists(metadata(backup));
                } catch (IOException e) {
                    log.warn("Image recovery file cleanup required: {}", backup);
                }
            }
        });
        storage.delete(key); // A failure propagates and rolls back the DB transaction.
    }

    private Path metadata(Path backup) {
        return backup.resolveSibling(backup.getFileName() + ".metadata");
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("이미지 저장 작업에는 DB 트랜잭션이 필요합니다.");
    }
}
