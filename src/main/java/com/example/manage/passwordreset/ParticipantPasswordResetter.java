package com.example.manage.passwordreset;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.CharBuffer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ParticipantPasswordResetter {
    static final int FIRST_PARTICIPANT = 1;
    static final int LAST_PARTICIPANT = 11;

    record Account(long memberId, int participantNo, String maskedLoginId) {}

    List<Account> inspect(Connection connection, boolean lock) throws SQLException {
        String sql = "select member_id, participant_no, login_id from member "
                + "where participant_no between ? and ? order by participant_no" + (lock ? " for update" : "");
        List<Account> accounts = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setInt(1, FIRST_PARTICIPANT);
            statement.setInt(2, LAST_PARTICIPANT);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    accounts.add(new Account(rows.getLong("member_id"), rows.getInt("participant_no"),
                            mask(rows.getString("login_id"))));
                }
            }
        }
        validateExactParticipants(accounts);
        return List.copyOf(accounts);
    }

    Map<Integer, String> encode(ParticipantPasswords passwords) {
        var encoder = new BCryptPasswordEncoder();
        Map<Integer, String> hashes = new LinkedHashMap<>();
        for (int participantNo = FIRST_PARTICIPANT; participantNo <= LAST_PARTICIPANT; participantNo++) {
            hashes.put(participantNo, encoder.encode(CharBuffer.wrap(passwords.valueFor(participantNo))));
        }
        return hashes;
    }

    void write(Connection connection, Map<Integer, String> hashes) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            inspect(connection, true);
            try (var update = connection.prepareStatement(
                    "update member set password = ? where participant_no = ?")) {
                for (int participantNo = FIRST_PARTICIPANT; participantNo <= LAST_PARTICIPANT; participantNo++) {
                    update.setString(1, hashes.get(participantNo));
                    update.setInt(2, participantNo);
                    if (update.executeUpdate() != 1) {
                        throw new SQLException("participant password update count mismatch", "P0001");
                    }
                }
            }
            verifyStoredHashes(connection, hashes);
            connection.commit();
        } catch (SQLException | RuntimeException ex) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                ex.addSuppressed(rollbackFailure);
            }
            throw ex;
        } finally {
            try {
                connection.setAutoCommit(originalAutoCommit);
            } catch (SQLException ignored) {
                // The connection is closed by the caller; never log its potentially sensitive details.
            }
        }
    }

    private void verifyStoredHashes(Connection connection, Map<Integer, String> expected) throws SQLException {
        try (var statement = connection.prepareStatement(
                "select participant_no, password from member where participant_no between ? and ? order by participant_no")) {
            statement.setInt(1, FIRST_PARTICIPANT);
            statement.setInt(2, LAST_PARTICIPANT);
            int count = 0;
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    count++;
                    if (!expected.get(rows.getInt(1)).equals(rows.getString(2))) {
                        throw new SQLException("stored password verification failed", "P0002");
                    }
                }
            }
            if (count != ParticipantPasswords.PARTICIPANT_COUNT) {
                throw new SQLException("stored password verification count mismatch", "P0003");
            }
        }
    }

    private void validateExactParticipants(List<Account> accounts) {
        if (accounts.size() != ParticipantPasswords.PARTICIPANT_COUNT) {
            throw new IllegalArgumentException("participant_no=1~11이 정확히 존재하지 않습니다.");
        }
        for (int index = 0; index < accounts.size(); index++) {
            if (accounts.get(index).participantNo() != index + 1) {
                throw new IllegalArgumentException("participant_no=1~11이 정확히 존재하지 않습니다.");
            }
        }
    }

    private static String mask(String loginId) {
        if (loginId == null || loginId.isEmpty()) return "[EMPTY]";
        if (loginId.length() == 1) return "*";
        if (loginId.length() == 2) return loginId.charAt(0) + "*";
        char[] middle = new char[Math.min(8, loginId.length() - 2)];
        Arrays.fill(middle, '*');
        return loginId.charAt(0) + new String(middle) + loginId.charAt(loginId.length() - 1);
    }
}
