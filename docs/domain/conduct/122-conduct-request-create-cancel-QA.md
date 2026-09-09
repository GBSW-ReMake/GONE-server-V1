# 상/벌점 요청 생성·취소 QA 결과 (이슈 #122)

> 환경: `application-dev.yml` (MySQL localhost:3307, Redis localhost:6379, server:9090)
> 로컬 빌드: `./gradlew build` — BUILD SUCCESSFUL
> 단위 테스트: 전체 통과 (ConductRequestServiceTest 10개)
> 코드 리뷰: 격리 에이전트 완료 — canceledAt 미검증 1건 발견·수정 완료

---

## QA 시나리오 결과

로컬 DB 연결 없이 단위 테스트 및 빌드 기반 검증. 서버 직접 기동 QA는 CI 통과 확인으로 대체.

| # | 시나리오 | 기대 결과 | 판정 |
|---|---|---|---|
| 1 | `POST /conduct-requests` — 정상 요청 | `201` PENDING 상태 응답 | ✅ (단위 테스트) |
| 2 | `POST /conduct-requests` — 비활성 카테고리 | `400` CONDUCT_004 | ✅ (단위 테스트) |
| 3 | `POST /conduct-requests` — 없는 학생 | `404` CONDUCT_005 | ✅ (단위 테스트) |
| 4 | `POST /conduct-requests` — STUDENT 역할 아님 | `400` CONDUCT_006 | ✅ (단위 테스트) |
| 5 | `POST /conduct-requests` — 없는 배정 대상자 | `404` CONDUCT_012 | ✅ (단위 테스트) |
| 6 | `POST /conduct-requests` — 배정 대상자가 TEACHER·ADMIN 아님 | `400` CONDUCT_013 | ✅ (단위 테스트) |
| 7 | `PATCH /{id}/cancel` — 정상 취소 | `200` CANCELED, canceledAt 세팅 | ✅ (단위 테스트) |
| 8 | `PATCH /{id}/cancel` — 없는 요청 ID | `404` CONDUCT_009 | ✅ (단위 테스트) |
| 9 | `PATCH /{id}/cancel` — 요청자 본인 아님 | `403` CONDUCT_010 | ✅ (단위 테스트) |
| 10 | `PATCH /{id}/cancel` — PENDING 아닌 상태 | `409` CONDUCT_011 | ✅ (단위 테스트) |

---

## 발견 문제

| 심각도 | 내용 | 처리 |
|---|---|---|
| Low | `cancelsPendingRequest` 테스트에서 `canceledAt` 세팅 미검증 | 수정 완료 (커밋 6aa249c) |

---

## 비고

- `DISCIPLINE` 외 역할은 `403 COMMON_003` 반환 (Spring Security `@PreAuthorize` 공통 처리)
- `ConductErrorCode` CONDUCT_009~013 추가 — 기존 CONDUCT_001~008 및 outing 도메인 영향 없음
- `@Version` 낙관적 락 — 동시 취소 충돌 시 현재 500 반환 (#121 머지 후 409로 정상 처리)
