# GlobalExceptionHandler 낙관적 락 충돌 409 처리 — 기획서 (이슈 #121)

## 개요/목적

`ConductRequest`·`Outing` 엔티티는 `@Version`으로 낙관적 락을 사용한다. 동일한 엔티티를
두 요청이 동시에 수정하면 나중 트랜잭션에서
`ObjectOptimisticLockingFailureException`이 발생하는데, 이 예외를 처리하는 핸들러가 없으면
500 Internal Server Error가 반환된다. 충돌은 정상적인 동시성 상황이므로 409 Conflict가
올바른 응답이다.

**현재 구현 상태**: 이슈 #121이 생성된 이후 #122 PR 작업 중(커밋 `e075297`) 핸들러가
선제적으로 추가됐다. 현재 `GlobalExceptionHandler.handleOptimisticLockingFailure`가 존재하며
기존 `COMMON_006` (CONFLICT)를 사용해 409를 반환한다. 이 기획서는 남은 갭을 확인하고
마무리 방향을 정리한다.

- **관련 이슈**: #121
- **현재 상태**: 핸들러 구현 완료 / 단위 테스트 없음 / 메시지 검토 필요

---

## 현 구현 상태 분석

### 구현된 것

`GlobalExceptionHandler`:

```java
@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
public ResponseEntity<ApiResponse<Void>> handleOptimisticLockingFailure(
    ObjectOptimisticLockingFailureException e) {
  return ResponseEntity
      .status(CommonErrorCode.CONFLICT.getStatus())
      .body(ApiResponse.fail(
          null,
          CommonErrorCode.CONFLICT.getDefaultMessage(),
          CommonErrorCode.CONFLICT.getCode()));
}
```

`COMMON_006`(HTTP 409)을 재사용한다.

### 미구현 / 검토 필요

1. **`COMMON_006` 메시지 적합성**: `"이미 존재하는 리소스입니다."` — `DataIntegrityViolationException`
   (UNIQUE 제약 위반)과 같은 코드·메시지를 공유한다. 낙관적 락 충돌과 UNIQUE 충돌은 클라이언트
   입장에서 다른 상황이지만, 두 경우 모두 현재 `COMMON_006`으로 응답한다.

2. **단위 테스트 없음**: `GlobalExceptionHandlerTest`에 `handleOptimisticLockingFailure`를
   검증하는 테스트가 없다.

---

## 구현 방향 — 방향 A 확정

`COMMON_006` 유지, 단위 테스트만 추가한다.

**결정 근거**:
- 에러 코드는 클라이언트 계약이다. 이미 staging에 배포된 `COMMON_006` 응답을 `COMMON_008`로 바꾸면 앱 측 대응 비용이 발생한다.
- 낙관적 락 충돌 시 앱은 서버 메시지를 사용자에게 그대로 노출하지 않고 자체 문구로 덮는다 — 메시지 부정확이 UX에 직접 영향을 주지 않는다.
- 로그에서 UNIQUE 위반과 낙관적 락을 구별해야 하면 exception type 자체가 다르므로 로그로 확인 가능하다.

**알려진 한계 (다른 개발자를 위한 기록)**:
- `COMMON_006`("이미 존재하는 리소스입니다.") 메시지는 `DataIntegrityViolationException`(UNIQUE 제약 위반)과
  `ObjectOptimisticLockingFailureException`(낙관적 락 충돌) 두 경우에 모두 반환된다.
  메시지 자체는 낙관적 락 충돌을 정확히 설명하지 못한다.
  **이 메시지를 사용자에게 직접 노출해서는 안 된다** — 409를 받으면 앱 레이어에서 별도 문구("잠시 후 다시 시도해주세요." 등)로 교체해야 한다.
  로그에서 두 경우를 구별하려면 에러 코드가 아니라 exception type(`ObjectOptimisticLockingFailureException` vs `DataIntegrityViolationException`)으로 판단한다.

**작업 범위**: `GlobalExceptionHandlerTest`에 테스트 1건 추가.

---

## 에러

해당 없음 — 이 기능 자체가 에러 응답을 올바르게 반환하기 위한 예외 처리기다.

---

## 영향 범위

- `GlobalExceptionHandler` (수정)
- `CommonErrorCode` (방향 B에서만 수정)
- `GlobalExceptionHandlerTest` (테스트 추가)
- `ConductRequest`·`Outing` 낙관적 락 사용 코드 — 변경 없음, 동작만 개선됨.

---

## 리스크 및 고려사항

- **단일 책임**: `COMMON_006`이 두 가지 다른 409 상황을 동시에 담당하게 된다(방향 A).
  코드를 분리(방향 B)하면 더 명확하지만, 클라이언트 앱이 현재 `COMMON_006`으로 분기 처리를
  구현해뒀다면 `COMMON_008` 추가 시 앱 측도 함께 대응해야 한다.
- **하위 호환성**: 방향 B는 새 에러 코드 추가이므로 기존 앱이 `COMMON_006`만 409로 처리했다면
  `COMMON_008`을 알 수 없는 코드로 처리할 수 있다. 앱 팀과 합의 필요.
- **페이지네이션/확장성**: 해당 없음.
