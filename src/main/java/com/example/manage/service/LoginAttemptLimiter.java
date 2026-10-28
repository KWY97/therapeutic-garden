package com.example.manage.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

@Component
public class LoginAttemptLimiter {
    public enum Role {
        ADMIN, MEMBER
    }

    private record Key(String loginIdDigest, String peerDigest) {
    }

    private static final class Attempts {
        private long windowStart;
        private long lastAttempt;
        private long blockedAt;
        private int count;
        private boolean blocked;

        private Attempts(long now) {
            windowStart = now;
            lastAttempt = now;
        }
    }

    private final int maxAttempts;
    private final long windowNanos;
    private final long cooldownNanos;
    private final long retentionNanos;
    private final int maxEntriesPerRole;
    private final LongSupplier nanoTime;
    private final Map<Role, LinkedHashMap<Key, Attempts>> attempts = new EnumMap<>(Role.class);
    private long operations;

    @Autowired
    public LoginAttemptLimiter(
            @Value("${security.login-limit.max-attempts:5}") int maxAttempts,
            @Value("${security.login-limit.window-seconds:120}") long windowSeconds,
            @Value("${security.login-limit.cooldown-seconds:20}") long cooldownSeconds,
            @Value("${security.login-limit.max-entries-per-role:5000}") int maxEntriesPerRole
    ) {
        this(maxAttempts, windowSeconds, cooldownSeconds, maxEntriesPerRole, System::nanoTime);
    }

    LoginAttemptLimiter(int maxAttempts, long windowSeconds, long cooldownSeconds,
            int maxEntriesPerRole, LongSupplier nanoTime) {
        if (maxAttempts < 1 || windowSeconds < 1 || cooldownSeconds < 1 || maxEntriesPerRole < 1) {
            throw new IllegalArgumentException("Login limit settings must be positive");
        }
        this.maxAttempts = maxAttempts;
        this.windowNanos = Math.multiplyExact(windowSeconds, 1_000_000_000L);
        this.cooldownNanos = Math.multiplyExact(cooldownSeconds, 1_000_000_000L);
        this.retentionNanos = Math.max(windowNanos, cooldownNanos);
        this.maxEntriesPerRole = maxEntriesPerRole;
        this.nanoTime = nanoTime;
        for (Role role : Role.values())
            attempts.put(role, new LinkedHashMap<>(16, 0.75f, true));
    }

    public synchronized boolean allow(Role role, String loginId, String peerAddress) {
        long now = nanoTime.getAsLong();
        LinkedHashMap<Key, Attempts> entries = attempts.get(role);
        if ((++operations & 63) == 0 || entries.size() >= maxEntriesPerRole) {
            removeExpired(entries, now);
        }

        Key key = new Key(digest(loginId), digest(peerAddress));
        Attempts state = entries.get(key);
        if (state == null) {
            if (entries.size() >= maxEntriesPerRole) {
                Iterator<Key> oldest = entries.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            state = new Attempts(now);
            entries.put(key, state);
        } else if (state.blocked && now - state.blockedAt < cooldownNanos) {
            return false;
        } else if (state.blocked || now - state.windowStart >= windowNanos) {
            state.windowStart = now;
            state.count = 0;
            state.blocked = false;
        }

        state.lastAttempt = now;
        state.count++;
        if (state.count >= maxAttempts) {
            state.blocked = true;
            state.blockedAt = now;
        }
        return true;
    }

    public synchronized void clearOnSuccess(Role role, String loginId, String peerAddress) {
        attempts.get(role).remove(new Key(digest(loginId), digest(peerAddress)));
    }

    synchronized int trackedKeys(Role role) {
        return attempts.get(role).size();
    }

    private void removeExpired(LinkedHashMap<Key, Attempts> entries, long now) {
        entries.entrySet().removeIf(entry -> now - entry.getValue().lastAttempt >= retentionNanos);
    }

    private String digest(String value) {
        try {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
