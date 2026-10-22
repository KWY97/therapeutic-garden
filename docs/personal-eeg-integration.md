# Personal EEG dated-history integration

## Scope and storage

Only the participant and administrator dated measurement history reads the new personal
EEG version. Landing, improvement cards, Summary, and the legacy Session/Spot tables keep
their existing sources. Both personal pages already use `MemberAnalysisModelService`,
`MeasurementHistoryView`, and the same graphs and numeric tables. The four Excel values
are stored as reported; the existing view calculates changes and rates.

`docs/personal-eeg-analysis-schema.sql` is an additive, manual MySQL migration with four
tables: batch, dated session, HS result, and one active-version pointer. It changes no
legacy table and is not executed by Spring Boot. The new tables use JDBC rather than JPA;
with `ddl-auto=validate`, a database without these tables keeps the legacy history path.
Member, Course, and Spot references restrict deletion while a batch retains them.

The original workbook remains outside Git at
`.local/egg-report/EEG_Personal_Report_All_Participants.xlsx`. The importer reads only
`날짜별_결과`: participant, source date, HC course, HS spot, stress Baseline/Post, and emotional
Baseline/Post. It validates the exact four metric headers per spot, 11 participants,
65 sessions, 130 HS results, the mapped Site/Member/Course/Spot IDs, duplicate source and
display session keys, and paired Post nullability. EEG band values are neither required
nor stored. The six EEG-only P009/P010 slots do not become personal history or DB rows.

P008 / HC-B / source date `2026-01-26` uses confirmed display date `2026-09-04`, based on
the user's decision. Both dates are stored. This correction applies to one matching
session only; a collision after correction aborts parsing and is also prevented by the
display-session unique key. A future report that already contains `2026-09-04` for this
session is accepted without changing its source date.

The four currently missing Post pairs are P003/2026-08-19/HS2,
P003/2026-08-24/HS4, P004/2026-08-26/HS5, and P005/2026-08-14/HS3. They remain NULL.
The parser rejects missing Post at any other key and accepts later values at these four
keys. The existing graph and table show Baseline, `측정 없음` for Post, and `계산 불가` for
change/rate. No values are inferred from EEG-only slots.

## Import, activation, and later corrections

Offline dry-run never connects to a DB:

```sh
./gradlew personalEegImport --args='--file .local/egg-report/EEG_Personal_Report_All_Participants.xlsx --site-id 1 --version eeg-2026-report-v1 --dry-run'
```

An explicit `--write` requires `--confirm-source-sha` and a localhost MySQL URL in
`PERSONAL_EEG_JDBC_URL` plus credentials in `PERSONAL_EEG_DB_USER` and
`PERSONAL_EEG_DB_PASSWORD`. In this stage, use only a **separate isolated database**.
Write uses one transaction, imports a draft, and never activates it. The original-byte
SHA-256 and version label each have a unique constraint. The same SHA/version is a no-op;
the same SHA with a new label or a reused label with different content is rejected.

`PersonalEegVersionStore` explicitly activates or switches to a reviewed batch and can
clear the pointer to return to legacy history. It checks stored counts, two HS results
per session, Post null pairs, HC/HS relationships, and permitted date correction. Four
allowed Post NULLs and excluded EEG slots do not block activation. No startup or import
path calls activation; this stage exercises it only in isolated tests.

When the four Post values are confirmed, prepare a **new reviewed workbook** with those
cells filled, keep the previous workbook and its batch, and choose a new version label.
Dry-run and compare all participant/date/course/spot keys and four values against the
previous batch. Import the new SHA as another draft into an isolated DB, verify counts and
screens, then switch the active pointer only after a separate operational approval.
Returning the pointer to the prior batch restores the previous personal values; clearing
it restores the legacy personal history. No prior batch or legacy measurement is updated.

Production migration, import, activation, and deployment require a separate review with
backup/restore validation and read-only target mapping checks. They are outside this stage.
