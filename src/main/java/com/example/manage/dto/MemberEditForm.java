package com.example.manage.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MemberEditForm {

    @NotNull(message = "참가자 번호를 입력해 주세요.")
    @Min(
            value = 1,
            message = "참가자 번호는 1 이상이어야 합니다."
    )
    private Integer participantNo;

    @NotNull(message = "그룹을 선택해 주세요.")
    @Min(
            value = 1,
            message = "그룹은 1 이상이어야 합니다."
    )
    @Max(
            value = 3,
            message = "그룹은 3 이하여야 합니다."
    )
    private Integer groupNo;

    @NotBlank(message = "로그인 아이디를 입력해 주세요.")
    private String loginId;

    private String name;

    private String phone;
}
