# test004 적용 및 복구 절차

이번 코드 작업에서는 운영 DB 연결, DDL, 계정 생성, 배포를 실행하지 않는다. H2 테스트는 MySQL 마이그레이션이나 운영 스키마의 Hibernate `validate` 호환성을 보증하지 않는다.

## 로컬 검증

`./gradlew test`는 격리된 H2 데이터로 계정, 분석, 측정 이력, 권한, CLI 실패/재실행을 검증한다. CLI 단위 테스트의 고정 비밀번호 문자열은 실제 계정과 무관한 더미 값이다. 로컬·운영 test004 계정의 실제 비밀번호와 DB 비밀번호는 소스와 로그에 저장하지 않고 실행 시 숨김 입력으로 전달한다.

## 운영 적용 전 수동 확인

1. 검증된 백업과 복구 연습 결과를 확보한다. MySQL 버전, `member`의 실제 DDL, 기본값, FK/인덱스 이름을 조회하여 `docs/test004-schema.sql` 및 엔티티와 비교한다. 실제 MySQL에서 마이그레이션 후 `ddl-auto=validate` 기동이 통과하는지 배포 전에 확인한다. 이 검증은 아직 수행하지 않았다.
2. 운영 환경의 Railway 프로젝트·환경·서비스·MySQL 인스턴스와 터널 종착지를 별도 읽기 전용 절차로 직접 확인한다. CLI의 URL host, `select database()`, P004 식별 정보는 방어선이지만 터널이나 복제 DB의 실제 종착지를 증명하지 못한다. `RAILWAY_ENVIRONMENT_NAME`이 배포에 제공되고 `prod` 프로필이 활성화되는지도 확인한다. 코드의 기동 가드는 Railway 표식 또는 원격 MySQL 주소에서 `prod`와 `validate`를 요구한다. 표식 없는 로컬 터널은 자동 식별할 수 없다.
3. P004의 `member_id`, `participant_no=4`, 로그인 ID, 분석 요약/측정 이력을 확인한다. `test004` 로그인 ID가 없는지 확인한다. 확인한 로그인 ID는 민감한 설정으로 다루고 문서·명령행·로그에 쓰지 않는다.
4. 변경 시간을 정한 뒤 스키마 SQL을 수동 적용한다. MySQL `ALTER TABLE`은 암묵적 커밋을 일으키므로 한 트랜잭션으로 되돌릴 수 없다. 새 컬럼, 인덱스, FK와 기존 행의 `test_account=0`을 검증한다. 앱의 자동 DDL 적용에 의존하지 않는다.

## 계정 생성

선택 환경에만 `TEST_ACCOUNT_LOCAL_*` 또는 `TEST_ACCOUNT_PRODUCTION_*`를 설정한다. 필수 suffix는 `JDBC_URL`, `DB_USER`, `EXPECTED_DATABASE`, `EXPECTED_HOST`, `EXPECTED_P004_LOGIN_ID`이다. URL에는 계정명·비밀번호를 넣지 않는다. LOCAL은 loopback 주소만, PRODUCTION은 원격 주소를 허용한다. 운영 터널을 쓸 때만 `--allow-production-tunnel`을 지정한다. DB 비밀번호와 test004 비밀번호는 대화형 터미널의 숨김 입력으로 전달한다.

1. 먼저 `bash scripts/test004-provision --target LOCAL --p004-member-id <확인한 ID>`로 로컬 dry-run을 검증한다. 운영 작업 승인 후에는 `--target PRODUCTION`으로 별도 dry-run을 수행한다. 사전 점검 연결은 읽기 전용으로 설정하고 DB 이름, 접속 host, P004 번호/로그인 ID, test004 부재와 다음 참가자 번호를 확인한다. 출력에는 비밀번호·URL·민감한 환경변수 값이 없다.
2. 운영 접속 대상과 dry-run 결과를 사람이 다시 확인한 뒤 `bash scripts/test004-provision --target PRODUCTION --p004-member-id <확인한 ID> --write`를 별도로 실행한다. 터널이면 같은 명령에 `--allow-production-tunnel`을 추가한다. `APPLY PRODUCTION test004` 확인 문구와 숨김 비밀번호 입력을 요구한다. 확인 입력 중에는 DB 연결과 행 잠금을 유지하지 않는다. 쓰기 직전 새 연결에서 대상 및 중복 여부를 다시 확인한다.
3. 성공 후 새 계정의 별도 `member_id`, 유일한 `participant_no`, `test_account=1`, P004 참조 ID, BCrypt 해시를 확인한다. test004에 일정, 측정 원본, 개인 분석 요약 행이 없는지 확인한다. `WRITE_OUTCOME_UNKNOWN`이면 CLI의 읽기 전용 재확인 결과를 참고하되, **재실행 전에** 운영자가 대상 DB에서 `login_id='test004'` 행, 참조 ID, 해시와 중복 여부를 직접 확인한다. 자동 재시도하지 않는다.
4. 배포 뒤 test004 로그인/마이페이지/분석/측정 이력/빈 달력/P004 일정 접근 차단을 확인한다. 관리자 참가자 목록·선택지, P004 관리자 분석, 전체 KPI가 예상대로인지 확인한다.

## 복구

**계정 생성 전:** 마이그레이션만 적용됐다면 기존 모든 회원의 `test_account=0`, 모든 `analysis_reference_member_id IS NULL`을 확인한다. 새 코드가 기동 중이라면 먼저 사용을 중단하고 이전 버전으로 되돌린다. 이 조건과 백업을 검토한 뒤 FK, 인덱스, 참조 컬럼, 테스트 표시 컬럼 순서로 제거한다. SQL 예시는 `docs/test004-schema.sql`의 주석에 있다. 기존 회원 행은 삭제하지 않는다.

**계정 생성 후:** 먼저 새 코드 사용과 test004 세션을 중단하고, `login_id='test004'`가 정확히 한 행이며 그 계정이 실제 P004를 참조하는지 검증한다. test004의 일정·측정·요약·기타 참조 행이 없는지 조사한다. 행이 있다면 원인을 조사하고 백업과 복구 계획을 재검토한다. 확인된 test004 행만 삭제한 뒤 참조 컬럼이 전부 NULL이고 `test_account=1` 행이 없는지 확인한다. 이전 앱 버전으로 되돌린 후 FK → 인덱스 → 참조 컬럼 → 표시 컬럼을 제거한다. P004 및 기존 참가자의 원본 행과 분석 데이터는 변경하지 않는다. DDL 단계마다 결과를 확인하며, 실패하면 백업 기반 복구 절차를 따른다.

참조가 유지되는 동안 FK는 P004 삭제를 제한한다. 관리자 화면은 이를 안내한다. Raw 재집계는 `test_account=0`만 포함한다.
