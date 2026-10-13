package com.example.manage.service;

import com.example.manage.domain.Admin;
import com.example.manage.domain.Member;
import com.example.manage.repository.AdminRepository;
import com.example.manage.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class AccountPasswordService {
    public enum Result {
        SUCCESS, ACCOUNT_NOT_FOUND, INCORRECT_CURRENT, TOO_SHORT, TOO_LONG, CONFIRMATION_MISMATCH, REUSED
    }

    private final MemberRepository members;
    private final AdminRepository admins;
    private final PasswordEncoder encoder;

    @Transactional
    public Result changeMember(Long memberId, String current, String next, String confirmation) {
        Member member = memberId == null ? null : members.findLockedById(memberId).orElse(null);
        if (member == null) return Result.ACCOUNT_NOT_FOUND;
        Result validation = validate(member.getPassword(), current, next, confirmation);
        if (validation != Result.SUCCESS) return validation;
        member.changePassword(encoder.encode(next));
        return Result.SUCCESS;
    }

    @Transactional
    public Result changeAdmin(Long adminId, String current, String next, String confirmation) {
        Admin admin = adminId == null ? null : admins.findLockedById(adminId).orElse(null);
        if (admin == null) return Result.ACCOUNT_NOT_FOUND;
        Result validation = validate(admin.getPassword(), current, next, confirmation);
        if (validation != Result.SUCCESS) return validation;
        admin.changePassword(encoder.encode(next));
        return Result.SUCCESS;
    }

    private Result validate(String stored, String current, String next, String confirmation) {
        if (current == null || !encoder.matches(current, stored)) return Result.INCORRECT_CURRENT;
        if (next == null || next.codePointCount(0, next.length()) < 12) return Result.TOO_SHORT;
        if (next.getBytes(StandardCharsets.UTF_8).length > 72) return Result.TOO_LONG;
        if (!next.equals(confirmation)) return Result.CONFIRMATION_MISMATCH;
        if (encoder.matches(next, stored)) return Result.REUSED;
        return Result.SUCCESS;
    }
}
