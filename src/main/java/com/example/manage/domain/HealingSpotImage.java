package com.example.manage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
public class HealingSpotImage extends ManagedImage {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "spot_id", nullable = false)
    private HealingSpot healingSpot;

    public HealingSpotImage(HealingSpot healingSpot, String objectKey, String originalFileName,
            String contentType, int displayOrder, boolean representative) {
        super(objectKey, originalFileName, contentType, displayOrder, representative);
        this.healingSpot = healingSpot;
    }
}
