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

The current workbook remains outside Git at
`../private-data/EEG_Personal_Report_All_Participants_v.20261023_ver.2.xlsx` (relative to
`manage`). Its SHA-256 is
`2dd867810d58525dc6d289a3b3d6c070e2a6a53eba23f1ed4b571b84da513819`.
The previous workbook remains at `.local/egg-report/EEG_Personal_Report_All_Participants.xlsx`
with SHA-256 `25aa3eb3735cb57ec6b88da8985070f48da397bc2f0ba1168834c52520f5dfb7`.
The importer reads only `날짜별_결과`: participant, source date, HC course, HS spot, stress
Baseline/Post, and emotional Baseline/Post. It resolves the four metric columns by their
exact header names because v2 rearranged the columns. It validates 11 participants, the
mapped Site/Member/Course/Spot IDs, duplicate source and display session keys, and paired
Post nullability. EEG band values are neither required nor stored.

The parser accepts the previous report shape (65 sessions, 130 HS results, four known
missing Post pairs) and the current report shape (67 sessions, 134 HS results, zero missing
Post pairs). The two added sessions are P009 and P010 on 2026-08-21 in HC-C. Existing
session keys and Baseline values are unchanged. The four previously missing Post pairs
are the only changed values among the original 130 HS results.

P008 / HC-B / source date `2026-01-26` uses confirmed display date `2026-09-04`, based on
the user's decision. Both dates are stored. This correction applies to one matching
session only; a collision after correction aborts parsing and is also prevented by the
display-session unique key. A future report that already contains `2026-09-04` for this
session is accepted without changing its source date.

The previous report has missing Post pairs at P003/2026-08-19/HS2,
P003/2026-08-24/HS4, P004/2026-08-26/HS5, and P005/2026-08-14/HS3.
The v2 report fills all four. The previous batch retains its NULL values; switching back
restores its prior graph and table values, including `측정 없음` and `계산 불가`.

## Import, activation, and later corrections

Offline dry-run never connects to a DB:

```sh
./gradlew personalEegImport --args='--file ../private-data/EEG_Personal_Report_All_Participants_v.20261023_ver.2.xlsx --site-id 1 --version eeg-2026-report-v2 --dry-run'
```

An explicit `--write` requires `--confirm-source-sha` and a localhost MySQL URL in
`PERSONAL_EEG_JDBC_URL` plus credentials in `PERSONAL_EEG_DB_USER` and
`PERSONAL_EEG_DB_PASSWORD`. In this stage, use only a **separate isolated database**.
Write uses one transaction, imports a draft, and never activates it. The original-byte
SHA-256 and version label each have a unique constraint. The same SHA/version is a no-op;
the same SHA with a new label or a reused label with different content is rejected.

`PersonalEegVersionStore` explicitly activates or switches to a reviewed batch and can
clear the pointer to return to legacy history. It checks stored counts, two HS results
per session, Post null pairs, HC/HS relationships, and permitted date correction. No
startup or import path calls activation; this stage exercises it only in isolated tests.

Import v2 under a distinct version label as another draft. The isolated H2 integration
test imports both files as separate batches, compares all 134 v2 keys and four values
against stored rows, activates v2, then returns to v1. Returning the pointer to the prior
batch restores the previous personal values; clearing it restores legacy personal history.
No prior batch or legacy measurement is updated. Production activation is outside this work.

Production migration, import, activation, and deployment require a separate review with
backup/restore validation and read-only target mapping checks. They are outside this stage.

## Guarded Production version switch

`productionPersonalEegVersion` is the only operator entry point for changing the active
personal EEG pointer. It never creates schema, imports data, or updates an account, legacy
measurement, or Summary table. `--list` uses a read-only transaction. A switch locks the
singleton active row, rechecks the expected current batch, runs the same batch integrity
checks used by `PersonalEegVersionStore`, and changes only `personal_eeg_active_version`.

Provide credentials through protected environment variables, never command arguments or a
tracked `.env` file. The expected counts below must be obtained from a fresh read-only
Production check immediately before use.

```text
PERSONAL_EEG_PROD_JDBC_URL
PERSONAL_EEG_PROD_DB_USER
PERSONAL_EEG_PROD_DB_PASSWORD
PERSONAL_EEG_PROD_EXPECTED_DATABASE
PERSONAL_EEG_PROD_EXPECTED_SITE_ID
PERSONAL_EEG_PROD_EXPECTED_SITE_NAME
PERSONAL_EEG_PROD_TARGET_LABEL
PERSONAL_EEG_PROD_EXPECTED_ADMIN_ROWS
PERSONAL_EEG_PROD_EXPECTED_MEMBER_ROWS
PERSONAL_EEG_PROD_EXPECTED_OPERATING_MEMBERS
PERSONAL_EEG_PROD_EXPECTED_TEST_ACCOUNTS
PERSONAL_EEG_PROD_EXPECTED_LEGACY_SESSIONS
PERSONAL_EEG_PROD_EXPECTED_LEGACY_RESULTS
PERSONAL_EEG_PROD_EXPECTED_SUMMARY_ROWS
```

List the current pointer and all batches before every change. A loopback URL is accepted only
for an independently verified Production tunnel and requires the explicit tunnel flag.

```sh
./gradlew productionPersonalEegVersion \
  --args='--list --allow-production-tunnel'
```

Activate a reviewed v2 batch only with values copied from that successful list output:

```sh
./gradlew productionPersonalEegVersion --args='\
  --activate-batch <V2_BATCH_ID> --write --allow-production-tunnel \
  --confirm-environment production \
  --confirm-target <EXPECTED_TARGET_LABEL> \
  --confirm-database <EXPECTED_DATABASE> \
  --confirm-batch-id <V2_BATCH_ID> \
  --confirm-version eeg-2026-report-v2 \
  --confirm-source-sha 2dd867810d58525dc6d289a3b3d6c070e2a6a53eba23f1ed4b571b84da513819 \
  --expect-current legacy'
```

Switch to v1 by using its listed ID/version and the current v2 ID in `--expect-current`.
Return to legacy as follows:

```sh
./gradlew productionPersonalEegVersion --args='\
  --legacy --write --allow-production-tunnel \
  --confirm-environment production \
  --confirm-target <EXPECTED_TARGET_LABEL> \
  --confirm-database <EXPECTED_DATABASE> \
  --confirm-legacy --expect-current <CURRENT_BATCH_ID>'
```

Remote JDBC URLs must omit `--allow-production-tunnel`; loopback URLs require it. Repeating an
already completed command is a verified no-op. A changed current pointer, invalid batch,
wrong database/Site, unexpected protected-table count, or missing confirmation fails closed.
