package com.example.manage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
public class Admin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long adminId;

    @Column(nullable = false, unique = true)
    private String loginId;

    @Column(nullable = false)
    private String password;

    public Admin(String loginId, String password) {
        this.loginId = loginId;
        this.password = password;
    }

    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }
}
