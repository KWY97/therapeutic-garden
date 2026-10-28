package com.example.manage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Entity
@Getter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_image_spot_position", columnNames = {"site_image_id", "spot_id"}))
@org.hibernate.annotations.Check(constraints = "x_percent between 0 and 100 and y_percent between 0 and 100")
public class SiteImageSpotPosition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long positionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_image_id", nullable = false)
    private SiteImage siteImage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "spot_id", nullable = false)
    private HealingSpot healingSpot;

    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal xPercent;

    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal yPercent;

    public SiteImageSpotPosition(SiteImage image, HealingSpot spot, BigDecimal x, BigDecimal y) {
        siteImage = image;
        healingSpot = spot;
        move(x, y);
    }

    public void move(BigDecimal x, BigDecimal y) {
        validate(x);
        validate(y);
        xPercent = x.setScale(4, RoundingMode.HALF_UP);
        yPercent = y.setScale(4, RoundingMode.HALF_UP);
    }

    public static void validate(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0)
            throw new IllegalArgumentException("좌표는 0~100 사이여야 합니다.");
    }
}
