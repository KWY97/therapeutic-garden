-- MANUAL preparation for a future authorized local import. NOT executed by the importer.
-- Use only after reviewing the target database and taking a backup.
-- No IF NOT EXISTS: fail visibly if a table already exists with an unknown definition.
CREATE TABLE healing_spot_effect_summary (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    spot_id BIGINT NOT NULL,
    stress_participant_count INT NOT NULL,
    stress_valid_session_count INT NOT NULL,
    stress_reduction_rate DECIMAL(38,18) NOT NULL,
    emotional_participant_count INT NOT NULL,
    emotional_valid_session_count INT NOT NULL,
    emotional_increase_rate DECIMAL(38,18) NOT NULL,
    participant_count INT NULL,
    total_experience_count INT NULL,
    stress_improved_count INT NULL,
    emotional_improved_count INT NULL,
    CONSTRAINT uk_spot_effect UNIQUE (spot_id),
    CONSTRAINT fk_effect_spot FOREIGN KEY (spot_id) REFERENCES healing_spot (spot_id)
) ENGINE=InnoDB;

CREATE TABLE member_healing_spot_effect_summary (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    spot_id BIGINT NOT NULL,
    stress_valid_session_count INT NOT NULL,
    stress_reduction_rate DECIMAL(38,18) NOT NULL,
    emotional_valid_session_count INT NOT NULL,
    emotional_increase_rate DECIMAL(38,18) NOT NULL,
    total_experience_count INT NULL,
    stress_improved_count INT NULL,
    emotional_improved_count INT NULL,
    CONSTRAINT uk_member_spot_effect UNIQUE (member_id, spot_id),
    CONSTRAINT fk_member_effect_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_member_effect_spot FOREIGN KEY (spot_id) REFERENCES healing_spot (spot_id)
) ENGINE=InnoDB;

CREATE TABLE healing_effect_import_batch (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_sha256 VARCHAR(64) NOT NULL,
    source_filename VARCHAR(255) NOT NULL,
    imported_at DATETIME(6) NOT NULL,
    overall_summary_count INT NOT NULL,
    participant_summary_count INT NOT NULL,
    target_site_id BIGINT NOT NULL,
    CONSTRAINT uk_effect_import_sha UNIQUE (source_sha256)
) ENGINE=InnoDB;
-- target_site_id deliberately has no FK: retain the import audit after deletions.
-- MySQL DDL implicitly commits; schema preparation is separate from data import.
