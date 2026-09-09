# 상/벌점 학생 본인 조회 QA 결과 (이슈 #111)

> 환경: `application-dev.yml` (MySQL localhost:3307, Redis localhost:6379, server:9090)
> 로컬 빌드: `./gradlew build` — BUILD SUCCESSFUL
> 단위 테스트: 전체 통과
> CI: PR 생성 시 트리거 예정 (브랜치 push 완료, PR 미생성 상태)

---

## QA 시나리오 결과

| # | 시나리오 | 기대 결과 | 실제 결과 | 판정 |
|---|---|---|---|---|
| 1 | `GET /me/summary` — STUDENT 토큰 | `200` totalMeritPoints=0, totalDemeritPoints=-5, netScore=-5 | `200` 동일 | ✅ |
| 2 | `GET /me` — 파라미터 없음 | `200` ACTIVE + CANCELED 기록 모두 포함 | `200` 2건 (ACTIVE·CANCELED 각 1건) | ✅ |
| 3 | `GET /me?type=MERIT` | `200` 빈 목록 (상점 기록 없음) | `200` content: [] | ✅ |
| 4 | `GET /me?type=DEMERIT` | `200` 벌점 2건 | `200` 2건 반환 | ✅ |
| 5 | `GET /me?dateFrom=20260824&dateTo=20260824` | `200` 해당 일 기록 2건 | `200` 2건 반환 | ✅ |
| 6 | `GET /me?page=-1` | `400` CONDUCT_007 | `400` CONDUCT_007 | ✅ |
| 7 | `GET /me?size=0` | `400` CONDUCT_007 | `400` CONDUCT_007 | ✅ |
| 8 | `GET /me?size=101` | `400` CONDUCT_007 | `400` CONDUCT_007 | ✅ |
| 9 | `GET /me?dateFrom=20260824` (dateTo 누락) | `400` CONDUCT_008 | `400` CONDUCT_008 | ✅ |
| 10 | `GET /me?dateFrom=20260831&dateTo=20260801` (역순) | `400` CONDUCT_008 | `400` CONDUCT_008 | ✅ |
| 11 | `GET /me/summary` — 인증 없음 | `401` COMMON_002 | `401` COMMON_002 | ✅ |
| 12 | `GET /me/summary` — TEACHER 토큰 | `403` COMMON_003 | `403` COMMON_003 | ✅ |
| 13 | `GET /me?page=0&size=1` — 페이지네이션 | `200` 1건, hasNext=true, totalElements=2 | `200` 동일 | ✅ |

---

## 발견 문제

없음. Critical/High/Medium/Low 전 항목 통과.

코드 리뷰(9단계)에서 발견된 N+1 쿼리 수정(`LEFT JOIN FETCH r.teacher LEFT JOIN FETCH r.category`) 후 서버 기동 정상 확인.

---

## 비고

- `overDemeritThreshold`: 현재 테스트 학생 벌점 절댓값 5 < 임계치 10 → `false` 정상 반환
- 취소된 기록(status: CANCELED)이 이력 조회에 포함됨 — 기획서 명시 동작 확인
- `dateTo`만 제공한 경우(dateFrom 누락)도 CONDUCT_008 반환 확인 — ✅ 통과
