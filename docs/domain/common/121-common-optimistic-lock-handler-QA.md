# #121 낙관적 락 409 핸들러 — QA 결과

기획서: [121-common-optimistic-lock-handler.md](./121-common-optimistic-lock-handler.md)

## QA 범위

변경 내용: `GlobalExceptionHandlerTest`에 `handleOptimisticLockingFailure` 테스트 1건 추가.
프로덕션 코드 변경 없음 → 실서버 동작 검증 불필요.

## 빌드 및 테스트 결과

| 항목 | 명령 | 결과 |
|------|------|------|
| 컴파일 + Checkstyle | `./gradlew build -x test` | BUILD SUCCESSFUL ✅ |
| 신규 테스트 | `./gradlew test --tests "com.remake.gone.common.exception.GlobalExceptionHandlerTest"` | BUILD SUCCESSFUL ✅ |
| 전체 테스트 | `./gradlew test` | 625 tests, 1 failed ⚠️ |

## 전체 테스트 실패 1건 — 기존 결함, #121 무관

**실패 테스트**: `OutingLocationReminderConcurrencyIntegrationTest > 같은 외출증에 학교 반경 안 위치 핑을 동시에 여러 번 보내도 도착 확인 알림은 정확히 한 건만 발송된다`

**원인**: `dev` 브랜치에서도 동일하게 실패함을 확인(`./gradlew test --tests ...` → `FAILED`).
→ #121 작업 이전부터 존재한 실패. 이 브랜치에서 도입한 회귀 아님.

## 신규 테스트 검증

`handleOptimisticLockingFailure` 테스트:
- `ObjectOptimisticLockingFailureException` 발생 → HTTP 409 CONFLICT 반환 ✅
- `success()` = false ✅
- `code()` = "COMMON_006" ✅

## QA 결론

**통과.** 신규 테스트 정상 동작, 회귀 없음.
전체 테스트 실패 1건은 기존 결함으로 #121과 무관.
