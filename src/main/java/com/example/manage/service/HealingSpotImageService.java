package com.example.manage.service;

import com.example.manage.domain.*;
import com.example.manage.dto.ImageResponse;
import com.example.manage.repository.*;
import com.example.manage.storage.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional
public class HealingSpotImageService {

    private final HealingSpotRepository parents;
    private final HealingSpotImageRepository images;
    private final ImageStorage storage;
    private final ImageStorageTransactions storageTransactions;
    private final ImageFilePolicy files;
    private final MonitoringThumbnailService thumbnails;

    @Transactional(readOnly = true)
    public List<ImageResponse> list(Long parentId) {
        requireParent(parentId);
        return ordered(parentId).stream().map(image -> response(parentId, image)).toList();
    }

    @Transactional(readOnly = true)
    public String monitoringReadUrl(Long parentId) {
        requireParent(parentId);
        return ordered(parentId).stream().filter(HealingSpotImage::isRepresentative).findFirst()
                .map(image -> thumbnails.readUrl(image.getObjectKey(),
                        "/admin/spots/" + parentId + "/images/" + image.getImageId() + "/thumbnail",
                        () -> response(parentId, image).readUrl())).orElse(null);
    }

    @Transactional(readOnly = true)
    public ImageContent localThumbnail(Long parentId, Long imageId) {
        if (!(storage instanceof LocalImageStorage))
            throw new IllegalArgumentException("로컬 이미지 조회를 사용할 수 없습니다.");
        return authenticatedThumbnail(parentId, imageId);
    }

    @Transactional(readOnly = true)
    public ImageContent authenticatedThumbnail(Long parentId, Long imageId) {
        requireParent(parentId);
        HealingSpotImage image = owned(ordered(parentId), imageId);
        return thumbnails.content(image.getObjectKey(), image.getContentType());
    }

    private ImageResponse response(Long parentId, HealingSpotImage image) {
        String url = storage.createReadUrl(image.getObjectKey(),
                "/admin/spots/" + parentId + "/images/" + image.getImageId() + "/content");
        return new ImageResponse(image.getImageId(), image.getOriginalFileName(), image.getContentType(),
                image.getDisplayOrder(), image.isRepresentative(), url);
    }

    public void upload(Long parentId, List<MultipartFile> uploads) {
        files.validateBatch(uploads);
        uploadValidated(parentId, uploads);
    }

    private void uploadValidated(Long parentId, List<MultipartFile> uploads) {
        HealingSpot parent = lockParent(parentId);
        List<HealingSpotImage> current = ordered(parentId);
        int order = current.size();
        for (MultipartFile file : uploads) {
            String key = files.createKey(false, parentId, file.getContentType());
            try {
                storageTransactions.upload(key, file.getBytes(), file.getContentType());
            } catch (IOException e) {
                throw new ImageStorageException("업로드 파일을 읽을 수 없습니다.", e);
            }
            images.save(new HealingSpotImage(parent, key, files.displayName(file.getOriginalFilename()),
                    file.getContentType(), ++order, order == 1));
        }
        images.flush();
    }

    public void edit(Long parentId, List<MultipartFile> uploads, com.example.manage.dto.ImageEditForm form) {
        lockParent(parentId);
        List<HealingSpotImage> current = ordered(parentId);
        ImageEditPlan plan = ImageEditPlan.validate(form, current, uploads.size());
        if (!uploads.isEmpty())
            files.validateBatch(uploads);
        Map<String, HealingSpotImage> resolved = new HashMap<>();
        current.forEach(image -> resolved.put("e:" + image.getImageId(), image));
        // Upload first: invalid files or unavailable storage cannot touch existing objects.
        if (!uploads.isEmpty())
            uploadValidated(parentId, uploads);
        Set<Long> existingIds = new HashSet<>();
        current.forEach(image -> existingIds.add(image.getImageId()));
        int index = 0;
        for (HealingSpotImage image : ordered(parentId)) {
            if (!existingIds.contains(image.getImageId()))
                resolved.put("n:" + index++, image);
        }
        for (String key : plan.deleted()) {
            HealingSpotImage image = resolved.get(key);
            storageTransactions.delete(image.getObjectKey(), image.getContentType());
            thumbnails.deleteAfterCommit(image.getObjectKey());
            images.delete(image);
        }
        for (int i = 0; i < plan.order().size(); i++) {
            String key = plan.order().get(i);
            HealingSpotImage image = resolved.get(key);
            image.changeDisplayOrder(i + 1);
            image.changeRepresentative(key.equals(plan.representative()));
        }
        images.flush();
    }

    public void setRepresentative(Long parentId, Long imageId) {
        lockParent(parentId);
        List<HealingSpotImage> current = ordered(parentId);
        HealingSpotImage selected = owned(current, imageId);
        current.forEach(image -> image.changeRepresentative(image == selected));
        images.flush();
    }

    public void move(Long parentId, Long imageId, String direction) {
        if (!"up".equals(direction) && !"down".equals(direction))
            throw new IllegalArgumentException("올바르지 않은 이동 방향입니다.");
        lockParent(parentId);
        List<HealingSpotImage> current = ordered(parentId);
        int from = current.indexOf(owned(current, imageId));
        int to = from + ("up".equals(direction) ? -1 : 1);
        if (to >= 0 && to < current.size())
            Collections.swap(current, from, to);
        renumber(current);
        images.flush();
    }

    public void delete(Long parentId, Long imageId) {
        lockParent(parentId);
        List<HealingSpotImage> current = ordered(parentId);
        HealingSpotImage selected = owned(current, imageId);
        storageTransactions.delete(selected.getObjectKey(), selected.getContentType());
        thumbnails.deleteAfterCommit(selected.getObjectKey());
        images.delete(selected);
        current.remove(selected);
        if (selected.isRepresentative() && !current.isEmpty()) {
            current.forEach(image -> image.changeRepresentative(false));
            current.getFirst().changeRepresentative(true);
        }
        renumber(current);
        images.flush();
    }

    // Called only after the existing parent deletion guards; joins their transaction.
    public void deleteAll(Long parentId) {
        lockParent(parentId);
        List<HealingSpotImage> current = ordered(parentId);
        for (HealingSpotImage image : current) {
            storageTransactions.delete(image.getObjectKey(), image.getContentType());
            thumbnails.deleteAfterCommit(image.getObjectKey());
            images.delete(image);
        }
        images.flush();
    }

    @Transactional(readOnly = true)
    public ImageContent localContent(Long parentId, Long imageId) {
        if (!(storage instanceof LocalImageStorage))
            throw new IllegalArgumentException("로컬 이미지 조회를 사용할 수 없습니다.");
        return authenticatedContent(parentId, imageId);
    }

    @Transactional(readOnly = true)
    public ImageContent authenticatedContent(Long parentId, Long imageId) {
        requireParent(parentId);
        HealingSpotImage image = owned(ordered(parentId), imageId);
        return new ImageContent(storage.read(image.getObjectKey()), image.getContentType());
    }

    public record ImageContent(byte[] bytes, String contentType) {}

    private List<HealingSpotImage> ordered(Long parentId) {
        return images.findByHealingSpotSpotIdOrderByDisplayOrderAscImageIdAsc(parentId);
    }

    private HealingSpotImage owned(List<HealingSpotImage> current, Long imageId) {
        return current.stream().filter(image -> image.getImageId().equals(imageId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("이 HS에 속한 이미지가 아닙니다."));
    }

    private void renumber(List<HealingSpotImage> current) {
        for (int i = 0; i < current.size(); i++)
            current.get(i).changeDisplayOrder(i + 1);
    }

    private HealingSpot lockParent(Long id) {
        return parents.findLockedById(id).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 HealingSpot입니다."));
    }

    private void requireParent(Long id) {
        if (!parents.existsById(id))
            throw new IllegalArgumentException("존재하지 않는 HealingSpot입니다.");
    }
}
