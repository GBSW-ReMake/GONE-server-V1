# QA 결과 보고 — #157 상/벌점 요청 조회·승인·거절 API

## 빌드 및 테스트 결과

| 항목 | 결과 |
|------|------|
| `./gradlew build -x test` | **BUILD SUCCESSFUL** |
| `./gradlew checkstyleMain` | **PASS** (위반 없음) |
| `./gradlew checkstyleTest` | **PASS** (위반 없음) |
| `./gradlew test` | **644 tests completed, 1 failed** |

## 실패 테스트

| 테스트 | 상태 | 비고 |
|--------|------|------|
| `OutingLocationReminderConcurrencyIntegrationTest` | FAILED | 기존 dev 브랜치에서도 동일하게 실패하는 사전 존재 결함. 이번 PR과 무관 |

## 신규 테스트 — `ConductRequestServiceTest`

### GetRequests (6개)
- ADMIN — 전체 조회
- ADMIN — status 필터 조회
- TEACHER — 배정 요청 조회
- TEACHER — status 필터 배정 요청 조회 *(신규)*
- DISCIPLINE — 본인 생성 요청 조회
- DISCIPLINE — status 필터 본인 생성 요청 조회 *(신규)*

### ApproveRequest (8개)
- TEACHER(assignee) 승인 → APPROVED + conductRecordId 세팅
- ADMIN 승인 (assignee 아니어도 가능)
- 카테고리 오버라이드 적용 승인
- detail 오버라이드 적용 승인 *(신규)*
- REQUEST_NOT_FOUND (CONDUCT_009)
- REQUEST_APPROVE_FORBIDDEN (CONDUCT_014)
- REQUEST_NOT_PROCESSABLE (CONDUCT_015)
- 오버라이드 카테고리 inactive → CONDUCT_004

### RejectRequest (5개)
- TEACHER(assignee) 거절 → REJECTED
- ADMIN 거절
- REQUEST_NOT_FOUND (CONDUCT_009)
- REQUEST_APPROVE_FORBIDDEN (CONDUCT_014)
- REQUEST_NOT_PROCESSABLE (CONDUCT_015)

## 체크리스트

- [x] 빌드 성공
- [x] Checkstyle 위반 없음
- [x] 신규 기능 테스트 전체 통과
- [x] 기존 실패 테스트는 dev 브랜치 사전 존재 결함으로 확인
