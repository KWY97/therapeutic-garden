package com.example.manage.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class HealingCourseForm {

    @NotNull(message = "사이트를 선택해 주세요.")
    private Long siteId;

    @NotBlank(message = "HC 코드를 입력해 주세요.")
    @Size(max = 255, message = "HC 코드는 255자 이하여야 합니다.")
    private String code;

    @NotBlank(message = "코스명을 입력해 주세요.")
    @Size(max = 255, message = "코스명은 255자 이하여야 합니다.")
    private String name;

    @DecimalMin(value = "-90", message = "위도는 -90 이상이어야 합니다.")
    @DecimalMax(value = "90", message = "위도는 90 이하여야 합니다.")
    private Double centerLatitude;

    @DecimalMin(value = "-180", message = "경도는 -180 이상이어야 합니다.")
    @DecimalMax(value = "180", message = "경도는 180 이하여야 합니다.")
    private Double centerLongitude;

    @DecimalMin(value = "0", inclusive = false, message = "반경은 0보다 커야 합니다.")
    @DecimalMax(value = "1.7976931348623157E308", message = "반경은 유한한 숫자로 입력해 주세요.")
    private Double radius;
}
