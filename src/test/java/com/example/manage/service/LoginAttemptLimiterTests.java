package com.example.manage.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptLimiterTests {
    private final AtomicLong now = new AtomicLong();
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(5, 120, 20, 2, now::get);

    @Test
    void fifthAttemptIsAllowedAndCooldownEndsAtExactBoundary() {
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(limiter.allow(LoginAttemptLimiter.Role.ADMIN, "admin", "192.0.2.1")).isTrue();
        }
        assertThat(limiter.allow(LoginAttemptLimiter.Role.ADMIN, "admin", "192.0.2.1")).isFalse();
        now.set(19_999_999_999L);
        assertThat(limiter.allow(LoginAttemptLimiter.Role.ADMIN, "admin", "192.0.2.1")).isFalse();
        now.set(20_000_000_000L);
        assertThat(limiter.allow(LoginAttemptLimiter.Role.ADMIN, "admin", "192.0.2.1")).isTrue();
    }

    @Test
    void windowExpiryAndSuccessClearTheCounter() {
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(limiter.allow(LoginAttemptLimiter.Role.MEMBER, "member", "192.0.2.1")).isTrue();
        }
        now.set(120_000_000_000L);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(limiter.allow(LoginAttemptLimiter.Role.MEMBER, "member", "192.0.2.1")).isTrue();
        }
        assertThat(limiter.allow(LoginAttemptLimiter.Role.MEMBER, "member", "192.0.2.1")).isFalse();
        limiter.clearOnSuccess(LoginAttemptLimiter.Role.MEMBER, "member", "192.0.2.1");
        assertThat(limiter.allow(LoginAttemptLimiter.Role.MEMBER, "member", "192.0.2.1")).isTrue();
    }

    @Test
    void rolesAccountsAndPeerAddressesHaveIndependentCounters() {
        for (int attempt = 0; attempt < 5; attempt++) {
            limiter.allow(LoginAttemptLimiter.Role.ADMIN, "same", "192.0.2.1");
        }
        assertThat(limiter.allow(LoginAttemptLimiter.Role.ADMIN, "same", "192.0.2.1")).isFalse();
        assertThat(limiter.allow(LoginAttemptLimiter.Role.MEMBER, "same", "192.0.2.1")).isTrue();
        assertThat(limiter.allow(LoginAttemptLimiter.Role.ADMIN, "other", "192.0.2.1")).isTrue();
        assertThat(limiter.allow(LoginAttemptLimiter.Role.ADMIN, "same", "192.0.2.2")).isTrue();
    }

    @Test
    void expiredRecordsAreRemovedAndPerRoleCapacityIsBounded() {
        limiter.allow(LoginAttemptLimiter.Role.ADMIN, "first", "192.0.2.1");
        limiter.allow(LoginAttemptLimiter.Role.ADMIN, "second", "192.0.2.1");
        limiter.allow(LoginAttemptLimiter.Role.ADMIN, "third", "192.0.2.1");
        assertThat(limiter.trackedKeys(LoginAttemptLimiter.Role.ADMIN)).isEqualTo(2);
        assertThat(limiter.trackedKeys(LoginAttemptLimiter.Role.MEMBER)).isZero();

        now.set(120_000_000_000L);
        limiter.allow(LoginAttemptLimiter.Role.ADMIN, "fourth", "192.0.2.1");
        assertThat(limiter.trackedKeys(LoginAttemptLimiter.Role.ADMIN)).isEqualTo(1);
    }

    @Test
    void concurrentReservationsDoNotExceedTheLimit() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(16)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int attempt = 0; attempt < 32; attempt++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return limiter.allow(LoginAttemptLimiter.Role.ADMIN, "parallel", "192.0.2.1");
                }));
            }
            start.countDown();
            int allowed = 0;
            for (Future<Boolean> result : results) if (result.get()) allowed++;
            assertThat(allowed).isEqualTo(5);
        }
    }
}
