package com.example.manage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@MappedSuperclass
@Getter
@NoArgsConstructor
public abstract class ManagedImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long imageId;

    @Column(nullable = false, length = 512)
    private String objectKey;

    private String originalFileName;

    private String contentType;

    @Column(nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean representative;

    protected ManagedImage(String objectKey, String originalFileName, String contentType,
            int displayOrder, boolean representative) {
        this.objectKey = objectKey;
        this.originalFileName = originalFileName;
        this.contentType = contentType;
        this.displayOrder = displayOrder;
        this.representative = representative;
    }

    public void changeRepresentative(boolean representative) {
        this.representative = representative;
    }

    public void changeDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }
}
