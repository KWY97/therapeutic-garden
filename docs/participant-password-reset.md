# P001~P011 일회성 비밀번호 재설정

이 문서는 `scripts/participant-password-reset` CLI로 참가자 P001~P011의 비밀번호를 LOCAL과 Railway PRODUCTION DB에 동일하게 적용하는 운영 절차다. CLI는 웹 서버와 Spring 컨텍스트를 시작하지 않고 JDBC만 사용한다. 전용 launcher에도 `spring.jpa.hibernate.ddl-auto=none`, `spring.jpa.show-sql=false`가 고정되어 있으며, CLI에는 DDL 문장이 없다. Gradle `JavaExec`는 제어 터미널을 안정적으로 전달하지 않으므로 launcher는 Gradle로 classpath만 준비한 뒤 Java 프로세스를 로컬 터미널에서 직접 실행한다.

## 안전 특성

- `--target LOCAL|PRODUCTION`은 필수이며 LOCAL은 loopback MySQL만 허용한다. PRODUCTION은 기본적으로 원격 MySQL만 허용하고, loopback은 `--allow-production-tunnel`을 함께 준 명시적인 Railway SSH 터널 사용일 때만 허용한다.
- `--allow-production-tunnel`은 PRODUCTION의 loopback DB 주소에서만 사용할 수 있다. LOCAL에 주거나, 원격 PRODUCTION URL에 주거나, 중복하면 접속 전에 거부한다. 이 플래그는 loopback 차단을 명시적으로만 해제하며 다른 검증을 생략하지 않는다.
- 기본값은 dry-run이다. `--write`가 없으면 UPDATE를 실행하지 않는다.
- write에는 `--write`와 대화형 확인 문구 `APPLY <TARGET> P001-P011`이 모두 필요하다.
- P001~P011이 정확히 11명 존재하는지 먼저 검사한다. write 때 같은 행들을 `FOR UPDATE`로 다시 잠그고 재검사한다.
- 실제 변경 SQL은 `member.password`만 대상으로 한다. 관리자 계정, 로그인 ID, 참가자 정보, 일정 및 측정 데이터는 건드리지 않는다.
- 11개 UPDATE와 저장값 검증은 DB별 단일 트랜잭션이다. 하나라도 실패하면 rollback한다.
- 프로젝트의 기존 `BCryptPasswordEncoder`와 같은 Spring Security 구현 및 기본 설정을 사용한다.
- 생성 비밀번호는 24자이고 모두 다르다. 재사용 입력은 최소 16자와 11개 상이 여부를 검사한다.
- `--generate`는 LOCAL에서만 허용하고 PRODUCTION은 반드시 `--reuse`만 허용해 서로 다른 운영 비밀번호가 우발적으로 생성되는 것을 막는다.
- 참가자 비밀번호 및 DB 비밀번호는 명령행 인수나 환경변수로 받지 않는다. 터미널 숨김 입력만 사용하며, BCrypt 해시·원문·JDBC URL·DB 사용자명을 일반 출력에 표시하지 않는다.
- `--generate` 원문은 Gradle 로그가 아닌 로컬 제어 터미널(`/dev/tty` 또는 Java Console)에 한 번만 표시된다. 리다이렉션된 비대화형 실행은 거부한다.
- macOS Terminal 등에서 표준 bracketed paste가 전달하는 완전한 `ESC[200~ ... ESC[201~` 외곽 래퍼 한 쌍은 숨김 입력 경계에서 제거한다. 불완전하거나 중첩된 escape sequence, 탭 및 실제 CR/LF는 계속 제어 문자로 거부한다. `Console.readPassword()`와 fallback `readLine()`은 제출용 Enter의 줄 종료 문자를 원래부터 반환하지 않으므로 정상적인 한 줄 붙여넣기에는 영향이 없다. password manager에서 복사할 때 비밀번호 뒤의 실제 줄바꿈까지 선택하지 않는다.

## 환경변수 준비

JDBC URL에는 사용자명이나 비밀번호를 포함하지 않는다. DB 비밀번호는 실행 후 숨김 프롬프트에 입력한다. 아래 환경변수 값과 명령에는 비밀번호가 없다.

LOCAL:

```bash
export PASSWORD_RESET_LOCAL_JDBC_URL='jdbc:mysql://127.0.0.1:3306/manage'
export PASSWORD_RESET_LOCAL_DB_USER='로컬_DB_사용자'
export PASSWORD_RESET_LOCAL_EXPECTED_DATABASE='manage'
```

PRODUCTION:

```bash
export PASSWORD_RESET_PRODUCTION_JDBC_URL='jdbc:mysql://Railway_TCP_HOST:Railway_TCP_PORT/운영_DB명?sslMode=REQUIRED'
export PASSWORD_RESET_PRODUCTION_DB_USER='Railway_DB_사용자'
export PASSWORD_RESET_PRODUCTION_EXPECTED_DATABASE='운영_DB명'
```

현재의 Railway SSH 터널(`127.0.0.1:53326` -> Railway production MySQL, database `railway`)을 사용하는 경우:

```bash
export PASSWORD_RESET_PRODUCTION_JDBC_URL='jdbc:mysql://127.0.0.1:53326/railway?sslMode=REQUIRED'
export PASSWORD_RESET_PRODUCTION_DB_USER='Railway_DB_사용자'
export PASSWORD_RESET_PRODUCTION_EXPECTED_DATABASE='railway'
```

Railway의 credential이 포함된 통합 URL(`mysql://user:password@...`)을 그대로 사용하지 않는다. Railway dashboard의 host, public TCP port, database, user 항목으로 비밀번호 없는 JDBC URL을 구성한다. shell history, `.env`, 소스코드, 문서 또는 채팅에 DB 비밀번호를 넣지 않는다.

> **터널 대상 확인 한계:** `127.0.0.1:53326`이라는 로컬 주소와 `railway`라는 database 이름만으로는 터널의 반대편이 Railway 운영 DB인지 확정할 수 없다. CLI의 expected database 검증도 접속된 database 이름만 확인하며 Railway project/environment/service의 정체까지 증명하지는 않는다. 따라서 아래 “터널로 운영 DB 대상 확인” 절차를 dry-run 전에 반드시 수행한다.

## 권장 실행 순서

각 단계의 `P001 ... P011`과 마스킹된 login ID가 예상 계정인지 확인한다. `--dry-run`은 생략해도 기본 적용되지만 운영 절차에서는 의도를 분명히 하기 위해 표시한다.

### 1. LOCAL dry-run에서 비밀번호 11개 한 번 생성

```bash
scripts/participant-password-reset --target LOCAL --generate --dry-run
```

LOCAL DB 비밀번호를 숨김 입력한다. 이어서 로컬 터미널에만 나타난 P001~P011 비밀번호를 승인된 password manager에 즉시 저장한다. 텍스트 파일, 스크린샷, shell history, 터미널 로그, 소스코드 및 Git에는 저장하지 않는다. 이 단계가 성공한 뒤에는 `--generate`를 다시 사용하지 않고 모두 `--reuse`를 사용한다.

### 2. LOCAL write

```bash
scripts/participant-password-reset --target LOCAL --reuse --write
```

DB 비밀번호를 숨김 입력한 다음 저장해 둔 P001~P011 비밀번호를 각각 두 번 숨김 입력한다. 계정 목록을 확인하고 정확히 다음 확인 문구를 입력한다.

```text
APPLY LOCAL P001-P011
```

성공 종료는 `status=WRITE_COMMITTED, changedRows=11, target=LOCAL`이다. 이 명령은 실제 LOCAL 비밀번호를 변경하므로 승인 후에만 실행한다.

### 3. PRODUCTION dry-run

원격 Railway TCP 주소로 직접 접속하면 기존처럼 플래그 없이 실행한다.

```bash
scripts/participant-password-reset --target PRODUCTION --reuse --dry-run
```

`127.0.0.1:53326`의 검증된 Railway SSH 터널을 사용하면 반드시 명시적 승인 플래그를 추가한다.

```bash
scripts/participant-password-reset --target PRODUCTION --reuse --dry-run --allow-production-tunnel
```

Railway DB 비밀번호와 LOCAL에 사용한 동일한 11개 비밀번호를 숨김 입력한다. `status=DRY_RUN_OK, changedRows=0`과 정확한 계정 11개를 확인한다.

### 4. PRODUCTION write

아래의 백업·계정 검증·점검 시간 공지를 먼저 완료하고 별도 승인을 받은 뒤 실행한다.

```bash
scripts/participant-password-reset --target PRODUCTION --reuse --write
```

Railway SSH 터널을 사용하는 write는 다음과 같다.

```bash
scripts/participant-password-reset --target PRODUCTION --reuse --write --allow-production-tunnel
```

동일한 비밀번호 11개를 다시 숨김 입력하고 다음 문구로 확정한다.

```text
APPLY PRODUCTION P001-P011
```

성공 종료는 `status=WRITE_COMMITTED, changedRows=11, target=PRODUCTION`이다. 실패하면 재실행 전에 DB 상태와 backup을 먼저 확인한다.

## 운영 DB 실행 전 체크리스트

1. 점검 시간을 정하고 참가자 로그인을 잠시 중단한다.
2. Railway에서 대상 서비스/환경과 MySQL database 이름을 두 사람이 교차 확인한다.
3. Railway가 제공하는 backup 기능으로 복구 지점을 만들거나, 접근이 승인된 관리 단말에서 `mysqldump --single-transaction`을 사용한다. `mysqldump` 비밀번호는 `-p`의 숨김 프롬프트로만 입력하고 명령행에 붙이지 않는다. dump에는 기존 비밀번호 해시가 있으므로 `umask 077`로 파일 권한을 제한하고 승인된 암호화 저장소로 이동한다.
4. backup 파일의 생성 시각, 크기, dump 종료 코드와 복구 절차를 확인한다. PRODUCTION write 전에 복구 담당자도 지정한다.
5. read-only SQL 또는 CLI dry-run 결과로 아래 조건을 확인한다.

```sql
SELECT COUNT(*) AS participant_count,
       COUNT(DISTINCT participant_no) AS distinct_participant_count
FROM member
WHERE participant_no BETWEEN 1 AND 11;

SELECT participant_no, member_id, login_id
FROM member
WHERE participant_no BETWEEN 1 AND 11
ORDER BY participant_no;
```

두 count가 모두 11이어야 하고, 운영자 명부와 P001~P011의 login ID를 대조해야 한다. SQL client도 DB 비밀번호를 인수로 받지 말고 숨김 프롬프트를 사용한다.

6. PRODUCTION dry-run이 성공하고 `target=PRODUCTION`, P001~P011 11명을 확인한다. CLI는 실제 database가 환경변수의 expected database와 같은지 내부 검증하지만 database 이름 자체는 출력하지 않는다.
7. 변경 직후 P001과 P011을 포함한 표본 계정으로 새 비밀번호 로그인을 확인하고, 기존 비밀번호 로그인이 실패하는지 확인한다. 관리자 로그인과 측정 데이터 주요 count도 재확인한다. 새 비밀번호 자체나 BCrypt hash를 검증 기록에 복사하지 않는다.

### 터널로 운영 DB 대상 확인

1. Railway dashboard/CLI에서 현재 선택된 **project, environment=production, MySQL service**를 서로 다른 두 명이 교차 확인한다. 표시된 database가 `railway`인지도 확인한다.
2. 터널을 연 명령/세션의 원격 대상이 바로 그 production MySQL service인지 확인한다. 이전 staging/local 터널을 재사용하지 말고, 새 세션의 시작 시각과 대상을 작업 기록에 남긴다. credential은 기록하지 않는다.
3. `lsof -nP -iTCP:53326 -sTCP:LISTEN`으로 해당 포트를 예상한 SSH/Railway 터널 프로세스 하나만 듣고 있는지 확인한다. 다른 프로세스거나 확신할 수 없으면 중단하고 터널을 올바른 대상으로 다시 연다.
4. 위의 터널용 환경변수를 설정하고 `--allow-production-tunnel`을 넣은 PRODUCTION dry-run을 먼저 수행한다. 출력의 `target=PRODUCTION`, `productionTunnel=EXPLICITLY_ALLOWED`, `exactParticipants=11`, P001~P011의 마스킹된 login ID를 운영자 명부와 대조한다.
5. expected database, 계정 목록, Railway 대상 중 하나라도 일치하지 않으면 `--write`를 사용하지 않는다. 터널을 재시작하거나 local port가 바뀌면 이 확인을 처음부터 반복한다.

## 기존 로그인 세션 무효화

현재 참가자 인증은 로그인 때 HTTP session에 `loginMemberId`를 저장하고, interceptor는 그 값의 존재만 검사한다. 요청마다 비밀번호를 다시 검사하지 않으므로 DB 비밀번호를 바꿔도 이미 로그인된 참가자 세션은 자동 만료되지 않는다. 개별 사용자가 `/member/logout`을 호출하면 해당 session은 `invalidate()`되지만, 운영자가 모든 참가자 session을 일괄 폐기하는 저장소/endpoint는 현재 없다.

따라서 PRODUCTION write 직후 다음 절차가 필요하다.

1. 모든 참가자에게 로그아웃을 요청한다.
2. 확실한 일괄 무효화가 필요하면 운영 승인 하에 애플리케이션 인스턴스를 재시작한다. 이 프로젝트에는 Spring Session 같은 외부 session 저장소 의존성이 없으므로 기본 인메모리 servlet session은 인스턴스 재시작으로 제거된다. 다중 인스턴스라면 모든 인스턴스를 순차가 아니라 점검 시간 안에 모두 재시작한다.
3. 기존 browser session으로 `/member` 접근 시 `/member/login`으로 이동하는지 확인한 뒤, 새 비밀번호로 다시 로그인한다.

재시작/재배포는 이 CLI가 수행하지 않는다. Railway 운영 절차와 별도 승인을 따라 수동으로 수행한다.

## 개발 검증

실제 LOCAL/Railway DB에 연결하지 않는 격리 H2 테스트:

```bash
./gradlew test --tests 'com.example.manage.passwordreset.*'
```

테스트는 기본 dry-run 무변경, 11개 BCrypt write, 재사용 입력, P012/관리자/측정 데이터 불변, 한 건 실패 시 전체 rollback, 참가자 누락, 잘못된 확인 문구, target guard, PRODUCTION loopback의 명시적 터널 승인, LOCAL에서의 터널 플래그 거부, 비밀값·접속정보 일반 출력 비노출을 검증한다.
