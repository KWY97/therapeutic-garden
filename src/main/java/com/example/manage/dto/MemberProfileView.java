package com.example.manage.dto;

import com.example.manage.domain.Member;
import lombok.Getter;

@Getter
public class MemberProfileView {

    private final Long memberId;
    private final Integer participantNo;
    private final Integer groupNo;
    private final String loginId;
    private final String name;
    private final String formattedPhone;

    public MemberProfileView(Member member) {
        this.memberId = member.getMemberId();
        this.participantNo = member.getParticipantNo();
        this.groupNo = member.getGroupNo();
        this.loginId = member.getLoginId();
        this.name = member.getName();
        String phone = member.getPhone();
        this.formattedPhone = phone != null && phone.length() == 11
                ? phone.substring(0, 3) + "-" + phone.substring(3, 7) + "-" + phone.substring(7)
                : phone;
    }
}
