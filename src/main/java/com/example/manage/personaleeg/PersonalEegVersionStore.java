package com.example.manage.personaleeg;

import java.sql.*;

/** Explicit local/test activation primitive. Imports never call it. */
public final class PersonalEegVersionStore {
    public void activate(Connection c, long batchId) throws SQLException {
        activate(c, batchId, null, false);
    }

    /** Guarded operator path: the active pointer must still equal the inspected value. */
    public void activateExpected(Connection c, long batchId, Long expectedCurrentBatchId) throws SQLException {
        activate(c, batchId, expectedCurrentBatchId, true);
    }

    private void activate(Connection c, long batchId, Long expectedCurrentBatchId,
            boolean enforceExpected) throws SQLException {
        if (!c.getAutoCommit())
            throw new IllegalArgumentException("새 connection 필요");
        if (batchId <= 0)
            throw new IllegalArgumentException("배치 ID 오류");
        c.setAutoCommit(false);
        try {
            var integrity = new PersonalEegBatchIntegrity();
            var current = integrity.activeForUpdate(c);
            if (enforceExpected && !java.util.Objects.equals(current.batchId(), expectedCurrentBatchId))
                throw new IllegalArgumentException("활성 버전이 expected current와 일치하지 않습니다.");
            integrity.requireValid(c, batchId, null);
            int changed;
            try (var s = c.prepareStatement("update personal_eeg_active_version set batch_id=?,activated_at=current_timestamp where id=1")) {
                s.setLong(1, batchId);
                changed = s.executeUpdate();
            }
            if (changed == 0)
                try (var s = c.prepareStatement("insert into personal_eeg_active_version (id,batch_id,activated_at) values (1,?,current_timestamp)")) {
                    s.setLong(1, batchId);
                    s.executeUpdate();
                }
            c.commit();
        } catch (SQLException | RuntimeException ex) {
            c.rollback();
            throw ex;
        }
    }

    public void returnToLegacy(Connection c) throws SQLException {
        returnToLegacy(c, null, false);
    }

    /** Guarded operator path: the active pointer must still equal the inspected value. */
    public void returnToLegacyExpected(Connection c, Long expectedCurrentBatchId) throws SQLException {
        returnToLegacy(c, expectedCurrentBatchId, true);
    }

    private void returnToLegacy(Connection c, Long expectedCurrentBatchId,
            boolean enforceExpected) throws SQLException {
        if (!c.getAutoCommit())
            throw new IllegalArgumentException("새 connection 필요");
        c.setAutoCommit(false);
        try {
            var current = new PersonalEegBatchIntegrity().activeForUpdate(c);
            if (enforceExpected && !java.util.Objects.equals(current.batchId(), expectedCurrentBatchId))
                throw new IllegalArgumentException("활성 버전이 expected current와 일치하지 않습니다.");
            try (var s = c.createStatement()) {
                s.executeUpdate("update personal_eeg_active_version set batch_id=null,activated_at=current_timestamp where id=1");
            }
            c.commit();
        } catch (SQLException | RuntimeException ex) {
            c.rollback();
            throw ex;
        }
    }
}
