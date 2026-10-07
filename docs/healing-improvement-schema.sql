-- MANUAL, non-destructive phase-1 schema preparation. Never executed at application startup.
-- New columns stay nullable until existing rows have been explicitly rebuilt and verified.
ALTER TABLE healing_spot_effect_summary
    ADD COLUMN participant_count INT NULL,
    ADD COLUMN total_experience_count INT NULL,
    ADD COLUMN stress_improved_count INT NULL,
    ADD COLUMN emotional_improved_count INT NULL;

ALTER TABLE member_healing_spot_effect_summary
    ADD COLUMN total_experience_count INT NULL,
    ADD COLUMN stress_improved_count INT NULL,
    ADD COLUMN emotional_improved_count INT NULL;
