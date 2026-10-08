package com.example.manage.passwordreset;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class ParticipantPasswords implements AutoCloseable {
    static final int PARTICIPANT_COUNT = 11;
    static final int MINIMUM_LENGTH = 16;
    static final int GENERATED_LENGTH = 24;
    private static final char[] ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%+-_=.?".toCharArray();

    private final List<char[]> values;

    private ParticipantPasswords(List<char[]> values) {
        this.values = values;
    }

    static ParticipantPasswords generate(SecureRandom random, SecretTerminal terminal) {
        List<char[]> values = new ArrayList<>();
        while (values.size() < PARTICIPANT_COUNT) {
            char[] value = new char[GENERATED_LENGTH];
            for (int index = 0; index < value.length; index++) {
                value[index] = ALPHABET[random.nextInt(ALPHABET.length)];
            }
            if (!contains(values, value)) values.add(value);
            else Arrays.fill(value, '\0');
        }
        terminal.printSecretLine("아래 비밀번호는 이 로컬 터미널에만 한 번 표시됩니다. 즉시 안전한 저장소에 보관하세요.");
        for (int index = 0; index < values.size(); index++) {
            terminal.printSecretLine("P%03d: %s".formatted(index + 1, new String(values.get(index))));
        }
        return new ParticipantPasswords(values);
    }

    static ParticipantPasswords readForReuse(SecretTerminal terminal) {
        List<char[]> values = new ArrayList<>();
        try {
            for (int participantNo = 1; participantNo <= PARTICIPANT_COUNT; participantNo++) {
                String label = "P%03d".formatted(participantNo);
                char[] first = terminal.readSecret(label + " 새 비밀번호: ");
                char[] confirmation = terminal.readSecret(label + " 새 비밀번호 재입력: ");
                try {
                    validate(first, label);
                    if (!Arrays.equals(first, confirmation)) {
                        throw new IllegalArgumentException(label + " 비밀번호 재입력이 일치하지 않습니다.");
                    }
                    if (contains(values, first)) {
                        throw new IllegalArgumentException("참가자 비밀번호 11개는 모두 달라야 합니다.");
                    }
                    values.add(first);
                    first = null;
                } finally {
                    if (first != null) Arrays.fill(first, '\0');
                    Arrays.fill(confirmation, '\0');
                }
            }
            return new ParticipantPasswords(values);
        } catch (RuntimeException ex) {
            values.forEach(value -> Arrays.fill(value, '\0'));
            throw ex;
        }
    }

    char[] valueFor(int participantNo) {
        return values.get(participantNo - 1);
    }

    private static boolean contains(List<char[]> values, char[] candidate) {
        for (char[] value : values) {
            if (Arrays.equals(value, candidate)) return true;
        }
        return false;
    }

    private static void validate(char[] value, String label) {
        if (value.length < MINIMUM_LENGTH) {
            throw new IllegalArgumentException(label + " 비밀번호는 최소 16자여야 합니다.");
        }
        for (char character : value) {
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException(label + " 비밀번호에 제어 문자를 사용할 수 없습니다.");
            }
        }
    }

    @Override public void close() {
        values.forEach(value -> Arrays.fill(value, '\0'));
        values.clear();
    }
}
