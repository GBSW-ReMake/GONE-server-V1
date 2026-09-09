# 교사·선도부·관리자 상/벌점 조회 QA 결과 (이슈 #117)

> 환경: `application-dev.yml` (MySQL localhost:3307, Redis localhost:6379, server:9090)
> 로컬 빌드: `./gradlew build` — BUILD SUCCESSFUL
> 단위 테스트: 전체 통과
> CI: PR 생성 시 트리거 예정 (브랜치 push 완료, PR 미생성 상태)

---

## QA 시나리오 결과

| # | 시나리오 | 기대 결과 | 실제 결과 | 판정 |
|---|---|---|---|---|
| 1 | `GET /summary?studentUserId=1` — TEACHER 토큰 | `200` studentNickname·점수 포함 | `200` 동일 | ✅ |
| 2 | `GET /` — 파라미터 없음 (전체 학생) | `200` 전체 이력 3건 | `200` 3건 | ✅ |
| 3 | `GET /?studentUserId=4` — 특정 학생 | `200` 해당 학생 기록 2건 | `200` 2건 | ✅ |
| 4 | `GET /?type=MERIT` — 종류 필터 | `200` MERIT 1건 | `200` 1건 | ✅ |
| 5 | `GET /summary?studentUserId=999` — 존재하지 않는 학생 | `404` CONDUCT_005 | `404` CONDUCT_005 | ✅ |
| 6 | `GET /summary?studentUserId=2` — TEACHER 역할 사용자 | `400` CONDUCT_006 | `400` CONDUCT_006 | ✅ |
| 7 | `GET /summary` — studentUserId 미전달 | `400` COMMON_001 | `400` COMMON_001 | ✅ |
| 8 | `GET /?page=-1` | `400` CONDUCT_007 | `400` CONDUCT_007 | ✅ |
| 9 | `GET /?size=101` | `400` CONDUCT_007 | `400` CONDUCT_007 | ✅ |
| 10 | `GET /?dateFrom=20260801` (dateTo 누락) | `400` CONDUCT_008 | `400` CONDUCT_008 | ✅ |
| 11 | `GET /?dateFrom=20260831&dateTo=20260801` (역순) | `400` CONDUCT_008 | `400` CONDUCT_008 | ✅ |
| 12 | `GET /summary?studentUserId=1` — 인증 없음 | `401` COMMON_002 | `401` COMMON_002 | ✅ |
| 13 | `GET /?dateFrom=20260824&dateTo=20260824` — 단일 날짜 | `200` 해당 일 기록 | `200` 3건 반환 | ✅ |
| 14 | `GET /?page=0&size=1` — 페이지네이션 | `200` 1건, hasNext=true, totalElements=3 | `200` 동일 | ✅ |
| 15 | `GET /` — 인증 없음 | `401` COMMON_002 | `401` COMMON_002 | ✅ |

---

## 발견 문제

없음. Critical/High/Medium/Low 전 항목 통과.

---

## 비고

- 취소된 기록(status: CANCELED)이 이력 조회에 포함됨 — 기획서 명시 동작 확인
- `studentUserId` 생략 시 전체 학생 이력 조회 정상 동작 확인
- TEACHER·ADMIN·DISCIPLINE 외 역할은 `403 COMMON_003` 반환 (Spring Security `@PreAuthorize` 공통 처리)
