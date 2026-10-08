# Therapeutic Garden

현대건설 공동주택 현장에서 진행되는 헬스케어 실증의 운영 및 데이터 관리를 지원하기 위해 개발 중인  
**Spring Boot 기반 웹 애플리케이션**입니다.

관리자와 참가자의 일정 관리 기능을 기반으로  
실증 장소인 **Site**, Site 내부의 **Healing Course(HC)**, <strong>Healing Spot(HS)</strong>을을 관리하고  
Kakao Map을 통해 공간 정보를 확인할 수 있는 서비스로 확장하고 있습니다.

---

## 기술 스택

### Backend
- Java 21
- Spring Boot 4.1.0
- Spring MVC
- Spring Data JPA
- Spring Security
- MySQL

### Frontend
- Thymeleaf
- HTML
- CSS
- JavaScript
- FullCalendar
- Kakao Maps API

### Build
- Gradle

---

## 주요 도메인

현재 프로젝트의 주요 데이터 구조는 다음과 같습니다.
<img width="2136" height="992" alt="ERD" src="https://github.com/user-attachments/assets/cddbf38f-7ecb-4ab6-b724-43059e669d17" />
ScheduleSpot은 HealingSpot을 참조하며, 일정의 코스와 사이트는 HealingCourse / Site 관계에서 조회합니다.

개발 DB 적용 절차와 변경 내용은 [일정 마이그레이션 안내](docs/schedule-healing-spot-migration.md)를 참고해 주세요.

---

## 주요 기능

### 관리자

- 관리자 로그인 및 접근 권한 관리
- 참가자 등록 / 조회 / 수정 / 삭제
- 참가자별 일정 등록 / 조회 / 수정 / 삭제
- 전체 일정을 FullCalendar 기반 월간 달력으로 조회
- 일정별 날씨 정보 조회 및 저장

### 참가자

- 참가자 로그인
- 본인 일정만 조회할 수 있도록 접근 제한
- FullCalendar 기반 개인 일정 조회
- 일정 및 방문 Spot 상세 정보 조회

### Therapeutic Garden

- Site 정보 DB 관리 및 조회
- Site별 Healing Course / Healing Spot 조회
- 선택한 Site에 따라 지도 중심 및 확대 레벨 변경
- Kakao Map에 Healing Course 영역 표시
- Kakao Map에 Healing Spot 마커 표시
- Healing Spot 선택 시 상세 정보 표시

---

## 현재 개발 구조

```text
src/main/java/com/example/manage

├── config
├── controller
├── domain
├── dto
├── interceptor
├── repository
└── service
```

Spring Boot + Thymeleaf 기반으로 개발하고 있으며,  
Healing Course / Healing Spot 데이터는 REST API를 통해 조회하여 Kakao Map에 표시합니다.

---

## 향후 개발

- 관리자 Site / HealingCourse / HealingSpot 관리 기능
- Alpha Power 데이터 구조 설계 및 지도 연동
- Healing Spot 상세 데이터 확장
- 참가자 기능 및 역할별 권한 구조 확장
- Validation 및 예외 처리 보완
- 운영 환경 배포 및 테스트

---

## 설계 검토 사항

다음 항목은 실제 운영 방식과 데이터 규격을 확인한 후 결정할 예정입니다.

- Alpha Power 저장 구조 및 단위
- Site / Healing Spot 이미지 저장 방식
- Healing Course / Healing Spot 중복 검증 기준

---

## 실행 환경

- Java 21
- Spring Boot 4.1.0
- MySQL
- Gradle

애플리케이션 실행 후 로컬 환경에서 확인할 수 있습니다.

```text
http://localhost:8080
```

P001~P011 일회성 비밀번호 변경은 [안전 실행 절차](docs/participant-password-reset.md)를 따르세요.
