package com.example.manage.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class SpatialLayoutForm {

    private Long siteImageId;
    private Long revision;
    private List<Position> positions = new ArrayList<>();

    @Getter
    @Setter
    public static class Position {

        private Long spotId;
        private BigDecimal xPercent;
        private BigDecimal yPercent;
    }
}
