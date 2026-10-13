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
public class SiteImageService {
    private final SiteRepository parents;
    private final SiteImageRepository images;
    private final SiteImageSpotPositionRepository positions;
    private final ImageStorage storage;
    private final ImageStorageTransactions storageTransactions;
    private final ImageFilePolicy files;

    @Transactional(readOnly = true)
    public List<ImageResponse> list(Long parentId) {
        requireParent(parentId);
        return ordered(parentId).stream().map(image -> response(parentId, image)).toList();
    }

    @Transactional(readOnly = true)
    public String representativeReadUrl(Long parentId) {
        requireParent(parentId);
        return ordered(parentId).stream().filter(SiteImage::isRepresentative).findFirst()
                .map(image -> response(parentId, image).readUrl()).orElse(null);
    }

    private ImageResponse response(Long parentId, SiteImage image) {
        String url = storage.createReadUrl(image.getObjectKey(),
                "/admin/sites/" + parentId + "/images/" + image.getImageId() + "/content");
        return new ImageResponse(image.getImageId(), image.getOriginalFileName(), image.getContentType(),
                image.getDisplayOrder(), image.isRepresentative(), url, image.isSpatial());
    }

    public void upload(Long parentId, List<MultipartFile> uploads) {
        if (uploads == null || uploads.isEmpty()) throw new IllegalArgumentException("이미지를 선택해 주세요.");
        if (uploads.size() > ImageFilePolicy.MAX_FILES) throw new IllegalArgumentException("한 번에 최대 10장까지 등록할 수 있습니다.");
        uploads.forEach(files::validate); // Validate the entire batch before any storage side effect.
        Site parent = lockParent(parentId);
        List<SiteImage> current = ordered(parentId);
        int order = current.size();
        for (MultipartFile file : uploads) {
            String key = files.createKey(true, parentId, file.getContentType());
            try { storageTransactions.upload(key, file.getBytes(), file.getContentType()); }
            catch (IOException e) { throw new ImageStorageException("업로드 파일을 읽을 수 없습니다.", e); }
            images.save(new SiteImage(parent, key, files.displayName(file.getOriginalFilename()),
                    file.getContentType(), ++order, order == 1));
        }
        images.flush();
    }

    public void edit(Long parentId, List<MultipartFile> uploads, com.example.manage.dto.ImageEditForm form) {
        lockParent(parentId);
        List<SiteImage> current = ordered(parentId);
        ImageEditPlan plan = ImageEditPlan.validate(form, current, uploads.size());
        String spatial = form.getImageSpatial();
        if (spatial == null) spatial = current.stream().filter(SiteImage::isSpatial)
                .map(image -> "e:" + image.getImageId()).findFirst().orElse("");
        if (plan.deleted().contains(spatial)) spatial = "";
        if (!spatial.isEmpty() && !plan.order().contains(spatial))
            throw new IllegalArgumentException("모니터링 이미지 정보가 올바르지 않습니다. 화면을 다시 열어 주세요.");
        String previous = current.stream().filter(SiteImage::isSpatial)
                .map(image -> "e:" + image.getImageId()).findFirst().orElse("");
        if (!previous.equals(spatial)) lockParent(parentId).advanceSpatialRevision();
        // Release the unique role before assigning another image, regardless of flush ordering.
        if (!previous.isEmpty() && !previous.equals(spatial)) {
            current.forEach(image -> image.changeSpatial(false));
            images.flush();
        }
        Map<String, SiteImage> resolved = new HashMap<>();
        current.forEach(image -> resolved.put("e:" + image.getImageId(), image));
        // Upload first: invalid files or unavailable storage cannot touch existing objects.
        if (!uploads.isEmpty()) upload(parentId, uploads);
        Set<Long> existingIds = new HashSet<>();
        current.forEach(image -> existingIds.add(image.getImageId()));
        int index = 0;
        for (SiteImage image : ordered(parentId)) {
            if (!existingIds.contains(image.getImageId())) resolved.put("n:" + index++, image);
        }
        for (String key : plan.deleted()) {
            SiteImage image = resolved.get(key);
            storageTransactions.delete(image.getObjectKey(), image.getContentType());
            positions.deleteBySiteImageImageId(image.getImageId());
            images.delete(image);
        }
        for (int i = 0; i < plan.order().size(); i++) {
            String key = plan.order().get(i);
            SiteImage image = resolved.get(key);
            image.changeDisplayOrder(i + 1);
            image.changeRepresentative(key.equals(plan.representative()));
            image.changeSpatial(key.equals(spatial));
        }
        images.flush();
    }

    public void setRepresentative(Long parentId, Long imageId) {
        lockParent(parentId);
        List<SiteImage> current = ordered(parentId);
        SiteImage selected = owned(current, imageId);
        current.forEach(image -> image.changeRepresentative(image == selected));
        images.flush();
    }

    public void move(Long parentId, Long imageId, String direction) {
        if (!"up".equals(direction) && !"down".equals(direction))
            throw new IllegalArgumentException("올바르지 않은 이동 방향입니다.");
        lockParent(parentId);
        List<SiteImage> current = ordered(parentId);
        int from = current.indexOf(owned(current, imageId));
        int to = from + ("up".equals(direction) ? -1 : 1);
        if (to >= 0 && to < current.size()) Collections.swap(current, from, to);
        renumber(current);
        images.flush();
    }

    public void delete(Long parentId, Long imageId) {
        lockParent(parentId);
        List<SiteImage> current = ordered(parentId);
        SiteImage selected = owned(current, imageId);
        storageTransactions.delete(selected.getObjectKey(), selected.getContentType());
        positions.deleteBySiteImageImageId(selected.getImageId());
        if (selected.isSpatial()) lockParent(parentId).advanceSpatialRevision();
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
        List<SiteImage> current = ordered(parentId);
        for (SiteImage image : current) {
            storageTransactions.delete(image.getObjectKey(), image.getContentType());
            positions.deleteBySiteImageImageId(image.getImageId());
            images.delete(image);
        }
        images.flush();
    }

    @Transactional(readOnly = true)
    public ImageContent localContent(Long parentId, Long imageId) {
        if (!(storage instanceof LocalImageStorage)) throw new IllegalArgumentException("로컬 이미지 조회를 사용할 수 없습니다.");
        return authenticatedContent(parentId, imageId);
    }

    @Transactional(readOnly = true)
    public ImageContent authenticatedContent(Long parentId, Long imageId) {
        requireParent(parentId);
        SiteImage image = owned(ordered(parentId), imageId);
        return new ImageContent(storage.read(image.getObjectKey()), image.getContentType());
    }

    public record ImageContent(byte[] bytes, String contentType) {}
    private List<SiteImage> ordered(Long parentId) { return images.findBySiteSiteIdOrderByDisplayOrderAscImageIdAsc(parentId); }
    private SiteImage owned(List<SiteImage> current, Long imageId) {
        return current.stream().filter(image -> image.getImageId().equals(imageId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("이 Site에 속한 이미지가 아닙니다."));
    }
    private void renumber(List<SiteImage> current) {
        for (int i = 0; i < current.size(); i++) current.get(i).changeDisplayOrder(i + 1);
    }
    private Site lockParent(Long id) {
        return parents.findLockedById(id).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 Site입니다."));
    }
    private void requireParent(Long id) {
        if (!parents.existsById(id)) throw new IllegalArgumentException("존재하지 않는 Site입니다.");
    }
}
