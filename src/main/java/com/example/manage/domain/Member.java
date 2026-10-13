package com.example.manage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long memberId;

    @Column(nullable = false, unique = true)
    private Integer participantNo;

    @Column(nullable = false)
    private Integer groupNo;

    @Column(nullable = false, unique = true)
    private String loginId;

    @Column(nullable = false)
    private String password;

    private String name;
    private String phone;

    public Member(Integer participantNo, Integer groupNo, String loginId, String password) {
        this.participantNo = participantNo;
        this.groupNo = groupNo;
        this.loginId = loginId;
        this.password = password;
    }

    public Member(Integer participantNo, Integer groupNo, String loginId, String password, String name, String phone) {
        this(participantNo, groupNo, loginId, password);
        this.name = name;
        this.phone = phone;
    }

    public void update(
            Integer participantNo,
            Integer groupNo,
            String loginId,
            String name,
            String phone
    ) {
        this.participantNo = participantNo;
        this.groupNo = groupNo;
        this.loginId = loginId;
        this.name = name;
        this.phone = phone;
    }

    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }
}
