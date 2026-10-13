-- Manual MySQL migration. Review and apply before deploying code with ddl-auto=validate.
-- DDL commits implicitly; take a verified backup and run the preflight in test004-rollout.md.
ALTER TABLE member
    ADD COLUMN test_account BIT(1) NOT NULL DEFAULT b'0',
    ADD COLUMN analysis_reference_member_id BIGINT NULL,
    ADD INDEX idx_member_analysis_reference (analysis_reference_member_id),
    ADD CONSTRAINT fk_member_analysis_reference
        FOREIGN KEY (analysis_reference_member_id) REFERENCES member(member_id)
        ON DELETE RESTRICT;

-- Recovery is manual. MySQL ALTER TABLE commits implicitly; do not assume ROLLBACK undoes it.
-- First follow the account/relationship checks in test004-rollout.md. Never delete P004.
-- Only after test004 is absent and no other row uses these fields:
-- ALTER TABLE member
--     DROP FOREIGN KEY fk_member_analysis_reference,
--     DROP INDEX idx_member_analysis_reference,
--     DROP COLUMN analysis_reference_member_id,
--     DROP COLUMN test_account;
