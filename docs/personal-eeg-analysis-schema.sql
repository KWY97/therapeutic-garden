-- Manual, additive MySQL migration. Apply only to a reviewed isolated database first.
-- The application uses JDBC for these tables; prod ddl-auto=validate never creates them.
-- No existing measurement, effect-summary, or import-batch table is changed.
CREATE TABLE personal_eeg_analysis_batch (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_sha256 VARCHAR(64) NOT NULL,
    source_filename VARCHAR(255) NOT NULL,
    version_label VARCHAR(64) NOT NULL,
    target_site_id BIGINT NOT NULL,
    imported_at TIMESTAMP NOT NULL,
    session_count INT NOT NULL,
    result_count INT NOT NULL,
    complete_count INT NOT NULL,
    post_missing_count INT NOT NULL,
    CONSTRAINT uk_personal_eeg_source UNIQUE (source_sha256),
    CONSTRAINT uk_personal_eeg_version UNIQUE (version_label),
    CONSTRAINT fk_personal_eeg_site FOREIGN KEY (target_site_id) REFERENCES site(site_id)
) ENGINE=InnoDB;

CREATE TABLE personal_eeg_analysis_session (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    source_row INT NOT NULL,
    member_id BIGINT NOT NULL,
    course_id BIGINT NOT NULL,
    source_date DATE NOT NULL,
    confirmed_date DATE NOT NULL,
    CONSTRAINT uk_personal_eeg_source_row UNIQUE (batch_id, source_row),
    CONSTRAINT uk_personal_eeg_source_session UNIQUE (batch_id, member_id, source_date, course_id),
    CONSTRAINT uk_personal_eeg_display_session UNIQUE (batch_id, member_id, confirmed_date, course_id),
    CONSTRAINT fk_personal_eeg_session_batch FOREIGN KEY (batch_id) REFERENCES personal_eeg_analysis_batch(id),
    CONSTRAINT fk_personal_eeg_session_member FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE RESTRICT,
    CONSTRAINT fk_personal_eeg_session_course FOREIGN KEY (course_id) REFERENCES healing_course(course_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE personal_eeg_spot_result (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    spot_id BIGINT NOT NULL,
    baseline_stress DECIMAL(38,18) NOT NULL,
    stress_post DECIMAL(38,18) NULL,
    baseline_emotional DECIMAL(38,18) NOT NULL,
    emotional_post DECIMAL(38,18) NULL,
    CONSTRAINT uk_personal_eeg_session_spot UNIQUE (session_id, spot_id),
    CONSTRAINT ck_personal_eeg_post_pair CHECK ((stress_post IS NULL AND emotional_post IS NULL) OR (stress_post IS NOT NULL AND emotional_post IS NOT NULL)),
    CONSTRAINT fk_personal_eeg_result_session FOREIGN KEY (session_id) REFERENCES personal_eeg_analysis_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_personal_eeg_result_spot FOREIGN KEY (spot_id) REFERENCES healing_spot(spot_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

-- Exactly one personal analysis version can be active; no batch is activated by import.
CREATE TABLE personal_eeg_active_version (
    id TINYINT NOT NULL PRIMARY KEY,
    batch_id BIGINT NULL,
    activated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_personal_eeg_active_singleton CHECK (id = 1),
    CONSTRAINT uk_personal_eeg_active_batch UNIQUE (batch_id),
    CONSTRAINT fk_personal_eeg_active_batch FOREIGN KEY (batch_id) REFERENCES personal_eeg_analysis_batch(id)
) ENGINE=InnoDB;
