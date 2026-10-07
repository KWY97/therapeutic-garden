package com.example.manage.healingeffect;

import java.util.*;

final class ProductionCliArguments {
    private ProductionCliArguments() {}

    static Map<String, String> parse(String[] args, Set<String> valued, Set<String> flags) {
        var options = new LinkedHashMap<String, String>();
        var allowed = new HashSet<String>();
        allowed.addAll(valued);
        allowed.addAll(flags);
        for (int index = 0; index < args.length; index++) {
            String key = args[index];
            if (!allowed.contains(key) || options.containsKey(key))
                throw new IllegalArgumentException("잘못된 argument: " + key);
            if (valued.contains(key)) {
                if (++index >= args.length || args[index].startsWith("--"))
                    throw new IllegalArgumentException("argument 값 누락: " + key);
                options.put(key, args[index]);
            } else {
                options.put(key, "true");
            }
        }
        return Map.copyOf(options);
    }

    static long positiveSiteId(Map<String, String> options) {
        try {
            long siteId = Long.parseLong(options.getOrDefault("--site-id", ""));
            if (siteId <= 0) throw new NumberFormatException();
            return siteId;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("양의 --site-id 값이 필요합니다.");
        }
    }

    static boolean dryRun(Map<String, String> options) {
        boolean dryRun = options.containsKey("--dry-run");
        boolean write = options.containsKey("--write");
        if (dryRun == write)
            throw new IllegalArgumentException("--dry-run 또는 --write 중 정확히 하나가 필요합니다.");
        return dryRun;
    }

    static void requireWriteConfirmations(Map<String, String> options, String expectedSha,
            boolean requireEmptyRaw) {
        if (!"production".equals(options.get("--confirm-environment")))
            throw new IllegalArgumentException("write에는 --confirm-environment production이 필요합니다.");
        String confirmedSha = options.get("--confirm-source-sha");
        ProductionDatabaseTargetGuard.validateSha(confirmedSha, "confirmed source SHA");
        if (!expectedSha.equals(confirmedSha))
            throw new IllegalArgumentException("confirmed source SHA가 expected SHA와 일치하지 않습니다.");
        if (requireEmptyRaw && !options.containsKey("--confirm-empty-raw"))
            throw new IllegalArgumentException("write에는 --confirm-empty-raw가 필요합니다.");
    }

    static void rejectWriteConfirmationsForDryRun(Map<String, String> options) {
        if (options.containsKey("--confirm-environment") || options.containsKey("--confirm-source-sha")
                || options.containsKey("--confirm-empty-raw"))
            throw new IllegalArgumentException("dry-run에는 write confirmation argument를 사용할 수 없습니다.");
    }
}
