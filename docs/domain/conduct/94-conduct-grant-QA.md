# QA 결과 — 이슈 #94 (상/벌점 부여)

- **테스트 일시**: 2026-08-24
- **서버**: `feat/#94-conduct-grant` 브랜치, `application-dev.yml` 기준 로컬 기동 (포트 9090)
- **DB**: `gone-mysql` Docker 컨테이너 (MySQL 8.4.8, V16 마이그레이션 적용 확인)

## Critical (데이터 유실 · 보안 우회)
발견 없음.

## High (핵심 플로우 실패)
발견 없음.

## Medium (예외 케이스 미처리 · 환경 제약)
발견 없음.

## Low (사소한 개선점)
발견 없음.

---

## 테스트 항목

| # | 시나리오 | 기대 결과 | 실제 결과 | 판정 |
|---|---|---|---|---|
| 1 | TEACHER 토큰 + 유효한 학생·카테고리로 부여 | `201` · `success: true` · 응답 필드 정확 | `201` ✅ | 통과 |
| 2 | 존재하지 않는 `studentUserId` | `404` `CONDUCT_005` | `404` `CONDUCT_005` ✅ | 통과 |
| 3 | `studentUserId`에 교사 ID 입력 (역할 불일치) | `400` `CONDUCT_006` | `400` `CONDUCT_006` ✅ | 통과 |
| 4 | 존재하지 않는 `categoryId` | `400` `CONDUCT_004` | `400` `CONDUCT_004` ✅ | 통과 |
| 5 | `active = false` 카테고리 (DB에서 직접 비활성화) | `400` `CONDUCT_004` | `400` `CONDUCT_004` ✅ | 통과 |
| 6 | STUDENT 토큰으로 요청 | `403` `COMMON_003` | `403` `COMMON_003` ✅ | 통과 |
| 7 | 인증 없이 요청 | `401` `COMMON_002` | `401` `COMMON_002` ✅ | 통과 |

## 참고
- 코드 리뷰 결과: `docs/domain/conduct/94-conduct-grant-code-review.md`
- 단위 테스트(`ConductServiceTest.GrantConduct`) 5개 모두 통과 확인.
- `contextLoads` 및 Authorization 통합 테스트 실패는 dev 베이스라인과 동일한 pre-existing 이슈(환경 설정 문제)로 이번 브랜치 변경과 무관.
