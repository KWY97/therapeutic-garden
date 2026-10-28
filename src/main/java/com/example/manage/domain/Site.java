package com.example.manage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long siteId;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Column(nullable = false)
    private Integer mapLevel;

    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long spatialRevision;

    public void advanceSpatialRevision() {
        spatialRevision++;
    }

    public Site(String name, String address, Double latitude, Double longitude, Integer mapLevel) {
        this.name = name;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.mapLevel = mapLevel;
    }

    public void update(String name, String address, Double latitude, Double longitude, Integer mapLevel) {
        this.name = name;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.mapLevel = mapLevel;
    }
}
