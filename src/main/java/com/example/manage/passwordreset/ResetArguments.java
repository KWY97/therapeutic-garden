package com.example.manage.passwordreset;

import java.util.Locale;

record ResetArguments(DatabaseTarget target, PasswordMode passwordMode, boolean write,
        boolean allowProductionTunnel) {
    static ResetArguments parse(String[] args) {
        DatabaseTarget target = null;
        PasswordMode passwordMode = null;
        boolean write = false;
        boolean explicitDryRun = false;
        boolean allowProductionTunnel = false;

        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--target" -> {
                    if (target != null || ++index >= args.length) {
                        throw invalid("--target LOCAL|PRODUCTION 값이 필요합니다.");
                    }
                    try {
                        target = DatabaseTarget.valueOf(args[index].toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException ex) {
                        throw invalid("--target은 LOCAL 또는 PRODUCTION이어야 합니다.");
                    }
                }
                case "--generate" -> {
                    if (passwordMode != null) throw invalid("--generate와 --reuse 중 하나만 선택해야 합니다.");
                    passwordMode = PasswordMode.GENERATE;
                }
                case "--reuse" -> {
                    if (passwordMode != null) throw invalid("--generate와 --reuse 중 하나만 선택해야 합니다.");
                    passwordMode = PasswordMode.REUSE;
                }
                case "--write" -> {
                    if (write) throw invalid("--write가 중복되었습니다.");
                    write = true;
                }
                case "--dry-run" -> {
                    if (explicitDryRun) throw invalid("--dry-run이 중복되었습니다.");
                    explicitDryRun = true;
                }
                case "--allow-production-tunnel" -> {
                    if (allowProductionTunnel) {
                        throw invalid("--allow-production-tunnel이 중복되었습니다.");
                    }
                    allowProductionTunnel = true;
                }
                default -> throw invalid("허용되지 않은 인수가 있습니다. 비밀값은 명령행에 넣지 마세요.");
            }
        }
        if (target == null) throw invalid("--target LOCAL|PRODUCTION을 명시해야 합니다.");
        if (passwordMode == null) throw invalid("--generate 또는 --reuse를 명시해야 합니다.");
        if (write && explicitDryRun) throw invalid("--dry-run과 --write를 함께 사용할 수 없습니다.");
        if (allowProductionTunnel && target != DatabaseTarget.PRODUCTION) {
            throw invalid("--allow-production-tunnel은 PRODUCTION에서만 사용할 수 있습니다.");
        }
        if (target == DatabaseTarget.PRODUCTION && passwordMode == PasswordMode.GENERATE) {
            throw invalid("PRODUCTION은 LOCAL에서 보관한 비밀번호를 --reuse로만 입력해야 합니다.");
        }
        return new ResetArguments(target, passwordMode, write, allowProductionTunnel);
    }

    String modeLabel() {
        return write ? "WRITE" : "DRY_RUN";
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}

enum PasswordMode {
    GENERATE,
    REUSE
}
