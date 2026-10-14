# test004 운영 배포 사전 점검 (2026-10-14 갱신)

이 문서는 `feature/test-member-account` (`977fadb`)를 운영에 적용하기 위한 실행 순서다. 아래의 운영 조회는 모두 읽기 전용 세션에서 수행했다. 이 문서를 작성하는 동안 운영 DB/계정/변수, Railway 배포·연결 설정, 원격 Git은 변경하지 않았다. 비밀번호, 로그인 ID, 개인 데이터는 기록하지 않는다.

## 확인된 대상과 현 상태

- Railway 프로젝트 `adorable-art` (`85d662e5-26a9-4198-82fe-db6afc848b74`), 환경 `production`, 웹서비스 `schedule-manager` (`0584c63b-c6d3-46a0-882c-39e8ea4e75b8`), DB 서비스 `MySQL` (`c24e5f57-5b2f-40b8-adc0-dd5309026d42`). 로컬 Railway CLI 기본 링크는 **다른 서비스**인 `therapeutic-garden`이므로 모든 운영 명령에 서비스와 환경을 명시한다.
- GitHub의 이전 주소 `KWY97/schedule-manager`는 현재 주소 [`KWY97/therapeutic-garden`](https://github.com/KWY97/therapeutic-garden)으로 HTTP 301 리다이렉트된다. GitHub REST API도 이전 주소에서 repository ID `1322666893`으로 301 리다이렉트된다. 두 주소의 API 응답은 동일한 repository ID `1322666893`, node ID `R_kgDOTtZLjQ`, 현재 이름 `KWY97/therapeutic-garden`을 반환한다. **두 저장소가 별도로 존재하는 것이 아니다.** 로컬 Git `origin`은 현재 주소를 가리킨다.
- Railway는 `schedule-manager` 서비스의 소스와 배포 메타데이터에 이전 문자열 `KWY97/schedule-manager`를 표시한다. 이는 이름 변경 전 저장된 표시값으로 보는 것이 타당하다. Railway 메타데이터에는 GitHub repository ID가 노출되지 않아 연결 ID 자체를 직접 대조하지는 못했다. 기존 성공 배포는 `develop`의 `5496dba4267bce2f31f20a171270a47e9d309946`이며, 이전 GitHub 주소를 통한 원격 `develop` 조회와 로컬 `origin/develop`도 같은 커밋이었다. 이전 주소가 현재 주소의 동일한 repository ID로 리다이렉트되므로 별도 저장소 차이가 아니다. 로컬 기능 커밋 `977fadb`의 직접 선행 커밋이 `5496dba`다. 이 증거로 현재 별도 저장소 통합이나 선제적인 Railway 재연결은 필요하지 않다. 실제 수동 배포 전에는 병합 커밋을 Railway가 가져올 수 있는지 읽기 전용으로 확인하고, 문제가 생길 때만 연결 상태를 다시 검토한다.
- 웹서비스는 `SPRING_PROFILES_ACTIVE=prod`이고 DB URL은 Railway 내부 네트워크의 MySQL 주소다. URL의 host·database·사용자는 MySQL 서비스의 `MYSQLHOST`/내부 도메인·`MYSQLDATABASE`·`MYSQLUSER`와 각각 일치했다(값은 출력하지 않았다). DB 접속 설정과 스토리지 필수 키가 존재한다. 웹서비스의 DB 사용자는 `root`다. MySQL 서비스에 `railway ssh -s MySQL -e production`으로 접속해 `database()=railway`, MySQL `9.7.2`, `current_user()=root@%`를 확인했다. SSH는 운영 DB 컨테이너 내부에서만 수행했다.
- `mysql.user`에는 root와 MySQL 시스템 계정 외의 계정이 0개다. 별도 SELECT 전용 계정은 없다. `member`는 InnoDB이며 기존 컬럼 7개(`group_no`, `participant_no`, `member_id`, `login_id`, `name`, `password`, `phone`)다. `test_account`와 `analysis_reference_member_id`는 없다. 새 FK/인덱스 이름 충돌도 없다.
- 기존 회원은 11명이다. `participant_no=4`는 정확히 1행, `login_id='test004'`는 0행이다. P004 관련 요약 6행, 측정 세션 7행, spot 측정 13행, 일정 0행을 **건수만** 확인했다. `member`를 참조하는 기존 테이블은 `schedule`, `healing_measurement_session`, `member_healing_spot_effect_summary`다.
- 따라서 `docs/test004-schema.sql`의 두 컬럼, 인덱스, 자기 참조 FK가 필요하다. 현재 운영 스키마에서는 새 코드의 Hibernate `ddl-auto=validate`가 통과할 수 없다. 운영 DB에 DDL을 실행하기 전에 별도 복원본에서 검증한다.

## 읽기 전용 DB 점검과 최소 권한 계정

현재 확인된 접속 경로는 Railway SSH → `MySQL` 서비스 → 컨테이너 내부 `127.0.0.1`이다. 서비스 ID, 환경, DB 이름을 매 작업 직전에 다시 대조한다. `railway connect MySQL --tunnel-only -e production`은 별도 로컬 클라이언트가 필요할 때의 대안이며, 터널 포트만으로 운영 DB의 정체를 판단하지 않는다. 원격 접속 시에는 DB 암호를 명령행이나 로그에 넣지 않는다.

앞선 메타데이터 조회는 다음과 같이 세션 기본 트랜잭션을 읽기 전용으로 고정했다. `mysql` 명령의 SQL에는 `SELECT`만 넣고, 계정 이름·암호·개인 데이터 컬럼은 출력하지 않는다. 운영 DB 계정 생성 전까지 이 경로는 root 권한이므로 명령을 사전 검토한다.

```sh
railway ssh -s MySQL -e production sh -lc \
  'MYSQL_PWD="$MYSQLPASSWORD" mysql -h 127.0.0.1 -u "$MYSQLUSER" "$MYSQLDATABASE" \
   --batch --skip-column-names \
   --init-command="SET SESSION TRANSACTION READ ONLY" \
   --execute="SELECT DATABASE(), @@version, CURRENT_USER();"'
```

후속 운영 조회용 계정이 필요하면 승인된 DB 관리자 세션에서 아래 SQL을 **별도로** 실행한다. 임의의 강한 암호를 승인된 비밀 저장소에서 받아 대화형 SQL 클라이언트에 입력하고, 이 문서나 셸 기록에 적지 않는다. 아래 host는 DB 컨테이너 내부 TCP 접속(`-h 127.0.0.1`)용이다. 접속이 실패하면 MySQL이 실제로 인식한 host를 확인한 뒤 정확한 host로 조정한다. `SELECT` 외 권한을 주지 않는다.

```sql
CREATE USER 'test004_audit'@'127.0.0.1' IDENTIFIED BY '<비밀 저장소에서 입력>';
GRANT SELECT ON railway.member TO 'test004_audit'@'127.0.0.1';
GRANT SELECT ON railway.schedule TO 'test004_audit'@'127.0.0.1';
GRANT SELECT ON railway.member_healing_spot_effect_summary TO 'test004_audit'@'127.0.0.1';
GRANT SELECT ON railway.healing_measurement_session TO 'test004_audit'@'127.0.0.1';
GRANT SELECT ON railway.healing_spot_measurement TO 'test004_audit'@'127.0.0.1';
SHOW GRANTS FOR 'test004_audit'@'127.0.0.1';
```

새 계정으로 다시 접속해 `CURRENT_USER()`, `SHOW GRANTS`, `SELECT` 성공을 확인한다. `INSERT`, `UPDATE`, `DELETE`, `CREATE`, `ALTER`, `DROP`, `GRANT OPTION`이 없음을 권한 목록에서 확인한다. 메타데이터 조회에도 해당 계정을 사용한다. 전체 DB 백업과 복원은 별도 백업 권한 또는 관리자 경로로 수행한다.

## 백업, 복원 연습, Hibernate 검증

2026-10-14 읽기 전용 점검에서 운영 `mysql-volume`의 볼륨 백업은 **2026-10-03 11:07:57 UTC 생성 1건**(만료 예정 2026-11-02)만 확인됐다. 자동 백업 일정은 없고 MySQL PITR은 비활성화되어 있으며 PITR 전체 백업 수도 0이다. 프로젝트에는 `production` 환경만 있다. `manage-backup.sql`은 2026-09-10 수정된 오래된 로컬 파일로, 출처와 복원 가능성이 검증되지 않아 최신 운영 백업으로 취급하지 않는다.

사용자 승인 후 **2026-10-13 15:26 UTC**에 운영 MySQL 9.7.2의 `mysqldump --single-transaction --quick --skip-lock-tables`로 `railway` DB 전체의 논리 백업을 만들었다. 16개 테이블이 모두 InnoDB이며 백업에는 CREATE TABLE 16개와 정상 완료 마커가 있다. 파일은 Git에서 제외되는 `.local/production-backups/railway-production-20261013152619Z.sql.gz`에 보관하고, 디렉터리 0700·파일 및 SHA-256 체크섬 파일 0600을 확인했다. `gzip -t`와 체크섬 재검사가 통과했다. 백업 파일은 별도 삭제 승인 전까지 보관한다.

백업은 기존 로컬 `manage` DB와 분리된 소켓 전용 임시 MySQL 9.2.0에 SQL 수정 없이 복원되었다. 복원본의 16개 InnoDB 테이블, `member` 11행, P004 요약 6행·측정 세션 7행·spot 측정 13행이 기존 운영 기준치와 일치했다. 네트워크가 차단된 별도 데이터 디렉터리를 확인했고, 검증 후 임시 서버를 종료하고 복원 사본을 삭제했다.

2026-10-14에 같은 백업의 SHA-256과 gzip 무결성을 재확인한 후, 기존 `manage`와 다른 데이터 디렉터리의 로컬 MySQL 9.2.0(`127.0.0.1` 전용 임시 포트)에 다시 복원했다. 복원 전에 별도 데이터 디렉터리와 빈 스키마를 확인했다. `docs/test004-schema.sql`은 **이 격리 복원본에만** 오류 없이 적용되었다. `test_account bit(1) NOT NULL DEFAULT b'0'`, `analysis_reference_member_id bigint NULL`, `idx_member_analysis_reference`, 자기 참조 `fk_member_analysis_reference`와 `ON DELETE RESTRICT`를 확인했다. 기존 회원 11명의 새 표시값은 모두 0, 참조값은 모두 NULL이었다. 기존 `member`의 7개 원래 필드에 대한 SHA-256 집계와 나머지 15개 테이블의 MySQL 확장 체크섬이 마이그레이션 전후 동일했다. P004 관련 요약 6행·측정 세션 7행·spot 측정 13행도 유지됐다. 새 코드를 복원본에만 연결하여 `prod` 프로필, `ddl-auto=validate`로 웹 앱 전체를 기동했고 JPA 초기화와 시작이 성공했다. 검증 앱에는 운영 DB·스토리지 자격 증명을 전달하지 않았다.

`./gradlew test --no-daemon --rerun-tasks`는 396개 테스트 모두 통과했다(실패·오류·건너뜀 0). 여기에는 `ProductionDdlGuard`의 Railway/원격 MySQL 차단 및 `prod+validate` 허용 사례가 포함된다. 검증 앱과 격리 MySQL을 종료한 뒤 임시 복원 사본을 삭제했고, 원본 백업과 체크섬은 보관했다. 백업은 `.gitignore`의 `.local/` 규칙에 의해 Git에서 제외된다.

**남은 한계:** 운영은 MySQL 9.7.2, 격리 검증은 MySQL 9.2.0이다. 복원과 DDL, Hibernate 매핑의 9.2 호환성은 입증했지만 9.7.2의 실행 결과와 운영 부하·DDL 소요 시간까지 입증한 것은 아니다. 별도 MySQL 9.7.2 격리 환경은 아직 없다. 현재 백업은 2026-10-13 15:26 UTC의 시점 사본이므로 실제 운영 변경 직전 최신 백업·무결성 확인과 그 이후 운영 스키마 변동 재확인이 필요하다. 운영 환경변수는 DB·스토리지 필수 키의 존재만 확인했으며, 대기 중인 Railway 설정 변경은 아직 확인하지 않았다.

[Railway 볼륨 백업 복원](https://docs.railway.com/volumes/backups)은 원래 서비스의 볼륨을 교체하고 서비스를 재배포하는 절차다. **운영 볼륨 백업의 Restore 버튼/API를 격리 검증에 사용하지 않는다.** PITR의 새 서비스 복원은 현재 비활성화되어 있고 새 유료 리소스가 필요하다. 실제 운영 데이터의 논리 백업 생성·외부 복제도 별도 승인을 받은 뒤에만 수행한다.

1. 운영 변경 직전에 Railway 프로젝트/환경/DB 서비스 ID를 재확인하고 **새로운 시점의 논리 백업**을 별도 승인 하에 확보한다. 운영과 같은 9.7.2 `mysqldump --single-transaction --quick --routines --events --triggers --no-tablespaces --set-gtid-purged=OFF --skip-lock-tables --skip-add-locks`를 사용하고 해시·압축 무결성을 확인한다. 보관 위치는 접근 제한과 암호화 여부를 확인하고 0700/0600 권한을 적용한다. 백업에는 회원 정보와 비밀번호 해시가 포함되므로 출력·공유하지 않는다.
2. 가능하면 MySQL 9.7.2의 별도 격리 환경에서 최신 백업의 복원·DDL·`validate`를 재확인한다. 아직 이 환경이 없으므로 사용 전 비용과 데이터 복제 승인, 운영과의 네트워크·자격 증명 분리를 확인한다. 기존 9.2 검증 결과만으로 진행하기로 결정한다면 버전 차이를 명시적으로 위험으로 수용한다. Railway 운영 볼륨에 Restore를 누르지 않는다.
3. 복원본에서 이미 검증한 컬럼, 인덱스, FK, 기존 행 기본값, 체크섬, Hibernate 기동 절차를 최신 백업에도 재적용하고 새 결과를 기록한다. 운영 DB를 대상으로 앱 기동 검증을 수행하지 않는다.
4. 복원본에서 운영자 기능, P004 분석, 일정·측정 조회의 회귀 점검을 마친다. 실패 시 SQL/매핑을 수정하고 백업→복원→마이그레이션→validate 순서를 다시 수행한다. MySQL `ALTER TABLE`은 암묵적으로 커밋하므로 `ROLLBACK`을 복구 수단으로 간주하지 않는다.

## Git, 운영 적용, test004 검증 순서

1. 로컬 `origin=KWY97/therapeutic-garden`과 원격 `develop=5496dba`를 확인했다. 로컬 `977fadb`는 이 커밋을 직접 선행 커밋으로 가진다. 최종 문서 변경을 커밋한 뒤 기능 브랜치를 현재 이름의 `origin`에 Push하고 **같은 저장소**의 `develop` 대상 PR을 생성하여 diff, 운영 스키마 SQL, 복원 결과를 검토한다. Push와 PR 생성은 각각 사용자 승인 후 수행한다. 별도 저장소로 코드를 옮기거나 Railway 연결을 선제적으로 바꾸지 않는다.
2. 자동 배포가 여전히 꺼져 있고 Railway에 대기 중인 설정 변경이 없는지 읽기 전용으로 확인한 뒤, 승인받아 PR을 병합한다. 병합 커밋을 기록한다. 운영 DB 최신 백업/복원 검증 결과와 변경 시간·복구 담당자를 확정한다. 확인할 수 없는 대기 변경이 있으면 수동 배포를 중단한다.
3. 기존 웹서비스가 정상인 상태에서 운영 `railway.member`에 수동 마이그레이션을 적용한다. 컬럼 타입·기본값·NULL 여부, FK/인덱스, 기존 11명 모두 `test_account=0` 및 참조 NULL, P004 건수와 `test004` 부재를 확인한다. DDL 실패 시 즉시 중단하고 복원 계획을 실행한다.
4. Railway `production`의 **schedule-manager**가 병합 커밋을 가져올 수 있는지 확인한 뒤 해당 커밋으로 수동 배포한다. Railway 화면에 옛 저장소명이 남더라도 커밋 SHA와 서비스 ID를 기준으로 대상을 확인한다. `prod`와 `ddl-auto=validate`, 시작 로그, health, 배포 commit SHA를 확인한다. 이전 운영 커밋 `5496dba`의 `prod` 설정은 `ddl-auto=update`이므로 롤백 재기동 때도 스키마 변경 가능성을 고려한다.
5. 새 앱이 안정된 뒤 `docs/test004-rollout.md`에 따라 운영 CLI dry-run을 수행한다. P004의 내부 `member_id`와 로그인 ID는 승인된 운영 명부/DB에서 비공개로 대조한다. CLI가 `DRY_RUN_OK`, P004 일치, `test004` 부재를 확인한 뒤 승인된 계정 생성 단계에서만 `--write`를 사용한다. 비밀번호는 숨김 입력한다.
6. 생성된 test004가 별도 회원이며 P004 분석을 참조하는지 확인한다. test004 로그인·마이페이지·분석·측정 이력·빈 달력, P004 일정 접근 차단, 관리자 목록, P004 기존 분석과 전체 KPI를 점검한다. 개인 정보나 비밀번호를 작업 기록에 복사하지 않는다.

중단/복구 기준은 `docs/test004-rollout.md`를 따른다. 특히 계정 생성 이후에는 test004 관련 참조 행과 세션을 조사한 뒤 **그 계정만** 처리하며 P004 또는 기존 참가자 행을 삭제하지 않는다.

### 배포 실패 시 실행 순서

1. 단계별 승인과 실행 기록에 프로젝트·환경·서비스 ID, 적용 SQL 파일의 Git 커밋, 배포 커밋, 최신 백업 시각·체크섬, 운영 확인 담당자를 남긴다. 서비스 이상이나 예상 밖의 스키마·건수 차이가 생기면 다음 단계로 넘어가지 않는다.
2. DDL 이후 새 앱 배포가 실패하면 `test004` 생성을 보류하고 기존 앱의 상태를 확인한다. 추가 컬럼은 기존 앱의 매핑에서 사용하지 않으므로 즉시 제거하지 않는다. 기존 `5496dba`의 `ddl-auto=update`는 재기동 시 자동 DDL을 시도할 수 있으므로, 이전 앱으로 재배포하기 전에는 `validate` 강제 적용 방법과 승인된 Railway 설정 변경 범위를 먼저 확정한다.
3. 운영 데이터 복원은 변경 시점 이후의 합법적인 신규 데이터도 되돌릴 수 있다. 자동으로 볼륨 Restore나 전체 SQL 복원을 수행하지 않는다. 장애 시 쓰기를 멈출지, 어느 시점까지 복원할지, 새 데이터와 P004 데이터를 어떻게 보존할지 담당자가 결정한 후 별도 승인을 받는다.
4. 새 앱 배포가 성공했으나 test004 생성이나 검증에 실패하면 새 계정의 중복·참조·연관 데이터를 먼저 조사한다. P004 및 기존 참가자의 계정, 비밀번호와 측정 데이터는 수정하지 않는다. `docs/test004-rollout.md`의 계정 생성 후 복구 절차는 별도 승인과 대상 행 확인 후에만 실행한다.
