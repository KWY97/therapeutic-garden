package com.example.manage.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class HealingSpotForm extends ImageEditForm {

    @NotNull(message = "코스를 선택해 주세요.")
    private Long courseId;

    @NotBlank(message = "HS 코드를 입력해 주세요.")
    @Size(max = 255, message = "HS 코드는 255자 이하여야 합니다.")
    private String code;

    @NotBlank(message = "스팟명을 입력해 주세요.")
    @Size(max = 255, message = "스팟명은 255자 이하여야 합니다.")
    private String name;

    @NotNull(message = "지도에서 스팟의 위치를 지정해 주세요.")
    @DecimalMin(value = "-90", message = "지도에서 스팟의 위치를 다시 지정해 주세요.")
    @DecimalMax(value = "90", message = "지도에서 스팟의 위치를 다시 지정해 주세요.")
    private Double latitude;

    @NotNull(message = "지도에서 스팟의 위치를 지정해 주세요.")
    @DecimalMin(value = "-180", message = "지도에서 스팟의 위치를 다시 지정해 주세요.")
    @DecimalMax(value = "180", message = "지도에서 스팟의 위치를 다시 지정해 주세요.")
    private Double longitude;
}
