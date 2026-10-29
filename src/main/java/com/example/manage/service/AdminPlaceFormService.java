package com.example.manage.service;

import com.example.manage.domain.HealingSpot;
import com.example.manage.domain.Site;
import com.example.manage.dto.HealingSpotForm;
import com.example.manage.dto.SiteForm;
import com.example.manage.repository.HealingSpotRepository;
import com.example.manage.repository.SiteRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** One transaction for parent fields and the complete image edit draft. */
@Service
@RequiredArgsConstructor
@Transactional
public class AdminPlaceFormService {
    private final SiteRepository siteRepository;
    private final HealingSpotRepository spotRepository;
    private final SiteService sites;
    private final HealingSpotService spots;
    private final SiteImageService siteImages;
    private final HealingSpotImageService spotImages;

    public Site createSite(SiteForm form, List<MultipartFile> files) {
        Site site = sites.createSite(form.getName(), form.getAddress(), form.getLatitude(),
                form.getLongitude(), form.getMapLevel());
        saveSiteImages(site.getSiteId(), form, files);
        return site;
    }

    public void updateSite(Long id, SiteForm form, List<MultipartFile> files) {
        siteRepository.findLockedById(id).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사이트입니다."));
        sites.updateSite(id, form.getName(), form.getAddress(), form.getLatitude(),
                form.getLongitude(), form.getMapLevel());
        saveSiteImages(id, form, files);
    }

    public HealingSpot createSpot(HealingSpotForm form, List<MultipartFile> files) {
        HealingSpot spot = spots.createHealingSpot(form.getCourseId(), form.getCode(), form.getName(),
                form.getLatitude(), form.getLongitude());
        saveSpotImages(spot.getSpotId(), form, files);
        return spot;
    }

    public void updateSpot(Long id, HealingSpotForm form, List<MultipartFile> files) {
        siteRepository.lockForMembershipChange();
        spotRepository.findLockedById(id).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 HealingSpot입니다."));
        spots.updateHealingSpot(id, form.getCourseId(), form.getCode(), form.getName(),
                form.getLatitude(), form.getLongitude());
        saveSpotImages(id, form, files);
    }

    private void saveSiteImages(Long id, SiteForm form, List<MultipartFile> files) {
        List<MultipartFile> selected = selectedFiles(files);
        imageWork(() -> {
            if (form.getImageOrder() != null)
                siteImages.edit(id, selected, form);
            else if (!selected.isEmpty())
                siteImages.upload(id, selected);
        });
    }

    private void saveSpotImages(Long id, HealingSpotForm form, List<MultipartFile> files) {
        List<MultipartFile> selected = selectedFiles(files);
        imageWork(() -> {
            if (form.getImageOrder() != null)
                spotImages.edit(id, selected, form);
            else if (!selected.isEmpty())
                spotImages.upload(id, selected);
        });
    }

    public static class ImageEditException extends IllegalArgumentException {
        ImageEditException(IllegalArgumentException cause) {
            super(cause.getMessage(), cause);
        }
    }

    private void imageWork(Runnable work) {
        try {
            work.run();
        } catch (com.example.manage.storage.ImageStorageException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new ImageEditException(exception);
        }
    }

    private List<MultipartFile> selectedFiles(List<MultipartFile> files) {
        if (files == null)
            return List.of();
        // Browsers send an empty part with no filename when the optional input is unused.
        // A selected, named zero-byte file must still reach ImageFilePolicy and be rejected.
        return files.stream().filter(file -> file == null || !file.isEmpty()
                || (file.getOriginalFilename() != null && !file.getOriginalFilename().isEmpty())).toList();
    }
}
