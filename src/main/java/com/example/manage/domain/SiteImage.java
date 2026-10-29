package com.example.manage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_site_image_spatial", columnNames = {"site_id", "spatial_image"}))
@Getter
@NoArgsConstructor
public class SiteImage extends ManagedImage {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    // NULL means unselected: MySQL permits multiple NULLs in this unique key.
    @Getter(lombok.AccessLevel.NONE)
    @Column(name = "spatial_image") // SPATIAL is a MySQL keyword; keep the physical name explicit.
    private Boolean spatial;

    public boolean isSpatial() {
        return Boolean.TRUE.equals(spatial);
    }

    public void changeSpatial(boolean selected) {
        spatial = selected ? Boolean.TRUE : null;
    }

    public SiteImage(Site site, String objectKey, String originalFileName,
            String contentType, int displayOrder, boolean representative) {
        super(objectKey, originalFileName, contentType, displayOrder, representative);
        this.site = site;
    }
}
