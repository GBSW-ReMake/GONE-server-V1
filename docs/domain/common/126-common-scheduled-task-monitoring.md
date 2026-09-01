# #126 scheduled_task 모니터링/재시도 API — 기획서

관련 이슈: [#126 scheduled_task 모니터링/재시도 API](https://github.com/GBSW-ReMake/GONE-server-V1/issues/126)
선행 이슈: [#120 범용 이벤트 스케줄링 인프라](./120-common-scheduled-task-infra.md) — "향후
모니터링과의 관계" 절에서 이 API가 필요할 때를 대비해 최소한의 실행 이력 컬럼
(`last_attempted_at`/`failure_count`/`last_error`/`status=FAILED`)을 이미 스키마에
포함해뒀고, "FAILED 이후 자동 복구 없음... 재활성화는 수동 개입(향후 관리 API)이 필요하다"고
이 재시도 기능의 필요성을 이미 예고해뒀다.

## 개요/목적
JobRunr 같은 잡 스케줄러 대시보드처럼, 관리자가 `scheduled_task`의 상태를 조회하고 FAILED로
격리된 task를 수동으로 재시도시키거나 필요 없는 task를 지울 수 있게 한다(보스 지시,
2026-09-01). JobRunr 대시보드 공식 문서가 명시하는 job 관리 기능이 정확히 "조회 +
Requeue(재시도) + Delete(삭제)" 세 가지라는 걸 확인하고 이 세 엔드포인트로 범위를
정했다(아래 각 엔드포인트 절 참고). 새 마이그레이션은 없다(#120이 이미 필요한 컬럼을
포함해뒀다). 관리자 화면(프론트)은 이 이슈 범위 밖이다 — 그 화면이 쓸 API만 만든다.

"성공한 잡/실패한 잡/전체 잡을 보고 싶고 메트릭도 보고 싶다"(보스, 2026-09-01)는 요청은
두 갈래로 나눠 반영한다: 성공(`DONE`)/실패(`FAILED`)/전체(필터 없음) 구분은 이미 목록
조회의 `status` 쿼리 파라미터로 되는 것이라 별도 엔드포인트가 필요 없고, "메트릭"에 해당하는
**상태별 개수 요약**은 목록 조회와 별개의 정보(페이지 단위가 아니라 테이블 전체 집계)라
엔드포인트를 하나 추가한다(JobRunr 대시보드 홈 화면의 상태별 카운트 배지와 같은 역할).

## 엔드포인트

### `GET /api/v1/scheduled-tasks`
`scheduled_task` 목록을 페이지네이션 조회한다.

**쿼리 파라미터**

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `status` | `ScheduledTaskStatus`(`PENDING`/`DONE`/`FAILED`) | N | 지정하면 해당 상태만 |
| `taskType` | `String` | N | 지정하면 해당 task_type만(예: `OUTING_TIMEOUT`) |
| `page` | `int` | N | 기본값 0 |
| `size` | `int` | N | 기본값 20, 1~100 |

정렬은 `next_attempt_at` 오름차순으로 고정한다 — "다음에 무엇이 실행될지/무엇이 밀려있는지"가
운영 중 가장 먼저 보고 싶은 정보라고 판단했다(요구사항이 아직 없는 화면을 위한 설계라
추측이 섞이는 건 감수한다 — 아래 "리스크 및 고려사항" 참고).

**인증/권한**: `ADMIN`만 허용한다(`@PreAuthorize("hasRole('ADMIN')")`). 이 API는 운영자용
디버깅 도구라 다른 역할(TEACHER/DISCIPLINE 등)에게 열어줄 이유가 없다 — #120이 다룬 도메인
로직과 달리 특정 학생/교사 데이터가 아니라 시스템 내부 상태라서, 기존 도메인들의
"담당자만"/"관련자만" 같은 세분화된 접근 제어가 필요 없다.

**응답 (200)**
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 1,
        "taskType": "OUTING_TIMEOUT",
        "referenceId": 42,
        "scheduledAt": "2026-09-01T15:00:00",
        "intervalSeconds": 60,
        "endAt": "2026-09-01T18:00:00",
        "nextAttemptAt": "2026-09-01T15:01:05",
        "lastExecutedAt": null,
        "lastAttemptedAt": "2026-09-01T15:00:05",
        "failureCount": 1,
        "lastError": "NotificationService 순간 오류",
        "status": "PENDING",
        "createdAt": "2026-09-01T15:00:00"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "hasNext": false
  },
  "message": "스케줄 작업 목록을 조회했습니다."
}
```

**에러**

| 상태 코드 | `ErrorCode` | 상황 |
|---|---|---|
| 401 | `COMMON_002` | 인증 없음 |
| 403 | `COMMON_003` | ADMIN이 아님 |
| 400 | `SCHEDULE_001` | `page<0` 또는 `size`가 1~100 범위 밖 |

### `GET /api/v1/scheduled-tasks/stats`
상태별(`PENDING`/`DONE`/`FAILED`) 개수와 전체 개수를 반환한다. 쿼리 파라미터 없음.

**응답 (200)**
```json
{
  "success": true,
  "data": {
    "pending": 3,
    "done": 128,
    "failed": 2,
    "total": 133
  },
  "message": "스케줄 작업 통계를 조회했습니다."
}
```

**인증/권한**: 목록 조회와 동일하게 `ADMIN`만 허용한다.

**에러**

| 상태 코드 | `ErrorCode` | 상황 |
|---|---|---|
| 401 | `COMMON_002` | 인증 없음 |
| 403 | `COMMON_003` | ADMIN이 아님 |

### `POST /api/v1/scheduled-tasks/{id}/retry`
FAILED로 격리된 task 하나를 다시 `PENDING`으로 되돌려 즉시 재시도 대상이 되게 한다. 요청
바디는 없다.

**동작**: `status`를 `PENDING`으로, `failureCount`를 0으로, `lastError`를 `null`로,
`nextAttemptAt`을 지금 시각으로 되돌린다. 다음 폴링 틱(최대 10초 뒤)에 자동으로 다시
실행된다 — 이 엔드포인트 자체가 handler를 직접 호출하지 않는다(관리자 요청 처리 시간이
handler 실행 시간에 좌우되지 않게 하려는 것 — 폴링 루프가 이미 그 역할을 한다).

**응답 (200)**: `ScheduledTaskResponse`(재시도 반영 후 최신 상태)를 그대로 반환한다.

**에러**

| 상태 코드 | `ErrorCode` | 상황 |
|---|---|---|
| 401 | `COMMON_002` | 인증 없음 |
| 403 | `COMMON_003` | ADMIN이 아님 |
| 404 | `SCHEDULE_002` | 해당 id의 task 없음 |
| 409 | `SCHEDULE_003` | task 상태가 `FAILED`가 아님(PENDING은 이미 재시도 중, DONE은 끝난 작업이라 되돌릴 대상이 아님) |

### `DELETE /api/v1/scheduled-tasks/{id}`
JobRunr 대시보드 공식 문서(`jobrunr.io/en/documentation/background-methods/dashboard/`)가
명시하는 job 관리 기능이 **Requeue(재시도) + Delete(삭제)** 두 가지라는 걸 확인하고 추가했다
— retry만으로는 "더 이상 필요 없는/잘못 등록된 task를 정리"하는 수단이 없다.

상태와 무관하게(PENDING/DONE/FAILED 모두) 삭제할 수 있다 — JobRunr도 job 상태를 가리지
않고 삭제를 허용하고, 관리자가 잘못 등록된 PENDING task를 당장 지워야 할 상황(예: 오작동
handler가 계속 알림을 보내는 중이라 급히 멈춰야 함)도 있을 수 있다. `ScheduledTaskService.
cancel(taskType, referenceId)`(#120)와 달리 이건 `id` 하나로 지운다 — 도메인 코드는
(taskType, referenceId)만 알고 있어 그 시그니처를 쓰지만, 관리자는 목록 화면에서 행을
보고 지우는 것이라 `id` 기반이 자연스럽다. 기존 `ScheduledTaskRepository.deleteById`
(JpaRepository가 기본 제공)를 그대로 쓰면 된다 — 새 리포지토리 메서드가 필요 없다.

**응답 (200)**: `ApiResponse<Void>`(`SchoolCampController.cancelApplication`과 동일한
DELETE 응답 컨벤션 — 이 프로젝트는 DELETE도 204가 아니라 `ApiResponse` 래퍼로 감싼
200을 쓴다).

**에러**

| 상태 코드 | `ErrorCode` | 상황 |
|---|---|---|
| 401 | `COMMON_002` | 인증 없음 |
| 403 | `COMMON_003` | ADMIN이 아님 |
| 404 | `SCHEDULE_002` | 해당 id의 task 없음 |

### `ScheduleErrorCode`(신규)
```java
package com.remake.gone.common.schedule.exception;

public enum ScheduleErrorCode implements ErrorCode {
  INVALID_PAGE(HttpStatus.BAD_REQUEST, "SCHEDULE_001", "페이지 요청이 올바르지 않습니다."),
  TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "SCHEDULE_002", "해당 스케줄 작업을 찾을 수 없습니다."),
  NOT_FAILED(HttpStatus.CONFLICT, "SCHEDULE_003", "FAILED 상태인 작업만 재시도할 수 있습니다.");
}
```
다른 도메인(`OutingErrorCode`/`ConductErrorCode` 등)과 동일하게, 도메인 전용 원인은 별도
`ErrorCode` enum으로 분리하는 기존 컨벤션을 따른다. `CommonErrorCode.INVALID_REQUEST`/
`NOT_FOUND`를 재사용하지 않는 이유: 그 메시지들은 무엇이 잘못됐는지 구체적으로 드러나지
않아 api-design.md 4번 원칙("원인이 다르면 코드를 분리")과 어긋난다.

## 응답 DTO
```java
package com.remake.gone.common.schedule.dto;

public record ScheduledTaskResponse(
    Long id,
    String taskType,
    Long referenceId,
    LocalDateTime scheduledAt,
    Integer intervalSeconds,
    LocalDateTime endAt,
    LocalDateTime nextAttemptAt,
    LocalDateTime lastExecutedAt,
    LocalDateTime lastAttemptedAt,
    int failureCount,
    String lastError,
    ScheduledTaskStatus status,
    LocalDateTime createdAt
) {

  public static ScheduledTaskResponse from(ScheduledTask task) {
    return new ScheduledTaskResponse(
        task.getId(), task.getTaskType(), task.getReferenceId(), task.getScheduledAt(),
        task.getIntervalSeconds(), task.getEndAt(), task.getNextAttemptAt(),
        task.getLastExecutedAt(), task.getLastAttemptedAt(), task.getFailureCount(),
        task.getLastError(), task.getStatus(), task.getCreatedAt());
  }
}
```
엔티티 컬럼을 그대로 노출하고 별도 가공/재해석을 하지 않는다 — 이슈 범위("테이블 컬럼을
그대로 노출") 그대로다.

```java
package com.remake.gone.common.schedule.dto;

public record ScheduledTaskStatsResponse(long pending, long done, long failed, long total) {}
```

## 서비스/리포지토리

### `ScheduledTaskRepository`에 추가
```java
@Query("select t from ScheduledTask t "
    + "where (:status is null or t.status = :status) "
    + "and (:taskType is null or t.taskType = :taskType) "
    + "order by t.nextAttemptAt asc, t.id asc")
Page<ScheduledTask> findWithFilters(
    @Param("status") ScheduledTaskStatus status,
    @Param("taskType") String taskType,
    Pageable pageable);

long countByStatus(ScheduledTaskStatus status);
```
`ConductRecordRepository.findWithFilters`와 동일한 패턴(옵셔널 파라미터를 `:param is null or`
조건으로 처리)을 그대로 따른다. `next_attempt_at`만으로는 동률(같은 시각) 시 정렬 순서가
보장되지 않아 페이지 경계가 흔들릴 수 있어(#97 코드 리뷰가 동일한 문제를 지적한 선례),
`ConductRecordRepository`가 `createdAt DESC, id DESC`로 보조 정렬 키를 쓰는 것과 같은
이유로 `id asc`를 보조 키로 추가한다.

`countByStatus`는 상태 3개를 각각 세 번 호출하는 파생 쿼리다(`GROUP BY` 프로젝션 대신) —
이 테이블은 도메인당 하루 수십~수백 건 규모라(#120 기획서 근거 동일) 쿼리 3번 정도는
비용이 무시할 수준이고, `count(t) group by t.status` 프로젝션을 Spring Data로 매핑하는
것보다 코드가 훨씬 단순하다.

### `ScheduledTask`에 추가 — `retry()`
```java
/**
 * 관리자가 FAILED task를 수동으로 재시도시킬 때 호출한다. failureCount/lastError를 지우는
 * 이유는 markSucceeded()와 동일하다 — 이번이 새로운 시도이므로 이전 실패 이력이 이후
 * backoff 계산에 영향을 주면 안 된다.
 */
public void retry(LocalDateTime now) {
  this.status = ScheduledTaskStatus.PENDING;
  this.failureCount = 0;
  this.lastError = null;
  this.nextAttemptAt = now;
}
```

### `ScheduledTaskAdminService`(신규, 컨트롤러가 직접 쓰는 서비스)
`ScheduledTaskService`(#120, 등록/취소 전용)에 메서드를 추가하지 않고 별도 서비스로
분리한다 — `ScheduledTaskService`는 도메인 코드가 스케줄을 등록/취소할 때 쓰는 내부 API이고,
이 조회/재시도는 관리자가 쓰는 별개의 소비자라 책임이 다르다(api-design.md 1번 원칙, "한
가지를 잘하기").
```java
@Service
@RequiredArgsConstructor
public class ScheduledTaskAdminService {

  private static final int MAX_PAGE_SIZE = 100;

  private final ScheduledTaskRepository scheduledTaskRepository;

  public PageResponse<ScheduledTaskResponse> getTasks(
      ScheduledTaskStatus status, String taskType, int page, int size) {
    if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
      throw new CustomException(ScheduleErrorCode.INVALID_PAGE);
    }
    Page<ScheduledTask> taskPage = scheduledTaskRepository.findWithFilters(
        status, taskType, PageRequest.of(page, size));
    return PageResponse.of(taskPage.map(ScheduledTaskResponse::from));
  }

  public ScheduledTaskStatsResponse getStats() {
    long pending = scheduledTaskRepository.countByStatus(ScheduledTaskStatus.PENDING);
    long done = scheduledTaskRepository.countByStatus(ScheduledTaskStatus.DONE);
    long failed = scheduledTaskRepository.countByStatus(ScheduledTaskStatus.FAILED);
    return new ScheduledTaskStatsResponse(pending, done, failed, pending + done + failed);
  }

  @Transactional
  public ScheduledTaskResponse retry(Long id) {
    ScheduledTask task = scheduledTaskRepository.findById(id)
        .orElseThrow(() -> new CustomException(ScheduleErrorCode.TASK_NOT_FOUND));
    if (task.getStatus() != ScheduledTaskStatus.FAILED) {
      throw new CustomException(ScheduleErrorCode.NOT_FAILED);
    }
    task.retry(LocalDateTime.now(ZoneId.of("Asia/Seoul")));
    return ScheduledTaskResponse.from(task);
  }

  // 이 프로젝트가 쓰는 Spring Data JPA 버전에서 deleteById는 대상이 없어도 예외 없이
  // 조용히 성공한다(10단계 QA에서 실서버로 확인 — 코드 리뷰 반영 시 가정했던
  // "Spring Data 표준 동작"이 이 버전에는 해당하지 않았다). findById로 먼저 존재를
  // 확인한 뒤 그 엔티티를 지운다 — 확인과 삭제 사이 다른 요청이 먼저 지우는 좁은
  // 레이스가 있어도, 엔티티 기준 삭제는 대상이 이미 없으면 조용히 0행으로 끝나 안전하다.
  @Transactional
  public void delete(Long id) {
    ScheduledTask task = scheduledTaskRepository.findById(id)
        .orElseThrow(() -> new CustomException(ScheduleErrorCode.TASK_NOT_FOUND));
    scheduledTaskRepository.delete(task);
  }
}
```
`delete`는 상태와 무관하게 지운다(별도 상태 검증 없음) — JobRunr도 job 상태를 가리지 않고
삭제를 허용한다.

## 영향 받는 기존 코드
- 신규: `common/schedule/dto/ScheduledTaskResponse`,
  `common/schedule/dto/ScheduledTaskStatsResponse`,
  `common/schedule/exception/ScheduleErrorCode`,
  `common/schedule/service/ScheduledTaskAdminService`,
  `common/schedule/controller/ScheduledTaskController`
- 변경: `ScheduledTaskRepository`에 `findWithFilters`/`countByStatus` 추가(기존 메서드는
  그대로 둔다, `deleteById`/`existsById`는 `JpaRepository`가 이미 제공해 추가 메서드
  불필요), `ScheduledTask`에 `retry(LocalDateTime)` 메서드 추가
- 신규 마이그레이션 없음(#120의 `scheduled_task` 스키마 그대로 사용)
- 테스트: `ScheduledTaskAdminServiceTest`(신규) — 페이지 파라미터 검증, 필터 위임 확인,
  상태별 개수 집계 확인, FAILED task 재시도 시 상태 전이, PENDING/DONE task 재시도 시도
  시 `NOT_FAILED` 확인, 존재하지 않는 id 재시도/삭제 시 `TASK_NOT_FOUND` 확인, 상태 무관
  삭제 확인. `ScheduledTaskTest`(#120에서 추가된 기존 클래스)에 `retry()` 단위 테스트 추가.

## API 설계 6원칙 체크
1. **한 가지를 잘하기**: 목록 조회 + 통계 + 재시도 + 삭제 4개 엔드포인트, JobRunr
   대시보드가 제공하는 job 관리 기능 범위와 일치시켰다(추측 아님) — 통계는 "메트릭을 보고
   싶다"는 요청을 목록 조회와 분리된 별도 엔드포인트로 반영한 것이다(페이지 단위 목록과
   테이블 전체 집계는 응답 성격이 다르다). 재시도/삭제는 조회와 성격이 다른 행위
   (mutation)라 별도 엔드포인트로 분리했지, 조회 응답에 끼워 넣지 않았다. 단건 조회는
   지금 요구사항이 없어 범위에서 뺐다(필요해지면 별도로 추가).
2. **빠르게 시작하기**: 위 요청/응답 예시로 바로 호출 가능.
3. **일관성**: `PageResponse`/`ApiResponse`/`ErrorCode` 패턴을 `ConductController`와
   동일하게 재사용. 재시도처럼 "행위" 성격 엔드포인트를 `POST /{리소스}/{id}/{동사}` 형태로
   두는 것도 outing의 `POST /outings/{code}/depart` 등 기존 패턴과 동일하다.
4. **의미 있는 오류**: `SCHEDULE_001`/`SCHEDULE_002`/`SCHEDULE_003`으로 원인별 코드를
   분리했다.
5. **확장성/성능**: 페이지네이션 적용. `idx_scheduled_task_due (status, next_attempt_at)`
   인덱스가 `status` 필터 + `next_attempt_at` 정렬을 그대로 타서 추가 인덱스가 필요 없다
   (`taskType` 단독 필터 시에는 풀스캔이지만, 이 테이블은 도메인당 하루 수십~수백 건
   규모라 문제되지 않는다 — #120 기획서의 "확장성/성능" 판단과 동일 근거).
6. **하위 호환성**: 신규 엔드포인트만 추가, 기존 API에 영향 없음.

## 리스크 및 고려사항
- **정렬 기준(`next_attempt_at` 오름차순)은 실제 관리자 화면 요구사항이 없는 상태에서 정한
  추측성 설계다** — #120이 "화면 요구사항이 아직 없어 API 자체를 만들지 않는다"고 결정한
  것과 같은 이유로 이 API의 정렬도 나중에 화면 요구사항이 정해지면 바뀔 수 있다. 응답
  스키마에 필드를 추가하는 방향의 변경은 하위 호환이지만, 정렬 기준 변경은 클라이언트가
  순서에 의존했다면 영향이 있을 수 있다 — 지금은 소비자(프론트)가 없어 리스크가 낮다.
- `taskType` 필터가 자유 문자열이라 오타를 내도 400이 아니라 빈 목록으로 조용히 반환된다
  (예: `"OUTNG_TIMEOUT"`). `taskType`을 enum으로 강제하려면 도메인마다 새 값이 추가될 때마다
  이 파일도 같이 고쳐야 해서 결합도가 생긴다 — 지금은 자유 문자열로 두고, 관리자 화면이
  실제로 붙을 때 드롭다운 등으로 UX 차원에서 막는 게 낫다고 판단했다.
- ADMIN 역할 자체의 부여/관리는 이 이슈 범위 밖이다(#15가 다룬다).
- **재시도 중복 클릭 보호 없음**: 같은 task에 대해 재시도 요청 두 개가 거의 동시에 들어오면
  (예: 관리자가 버튼을 두 번 누름) 결과는 여전히 "PENDING 하나"로 수렴해 데이터가 깨지지는
  않지만, 두 번째 요청이 낙관적 락 충돌 없이 조용히 같은 값을 한 번 더 쓸 수 있다. 사람이
  누르는 관리자 액션이라 발생 빈도가 낮고 결과도 안전(idempotent에 가까움)해서 지금은
  막지 않는다 — 필요해지면 프론트에서 버튼을 비활성화하는 정도로 충분하다고 판단했다.
- **삭제는 되돌릴 수 없다(soft delete 아님)**: `deleteById`로 물리 삭제한다. `scheduled_task`
  행 자체가 도메인 데이터가 아니라 "예약 상태" 메타데이터라(실제 도메인 데이터인 `outing`
  등은 그대로 남는다) 잘못 지워도 도메인 데이터 유실은 아니지만, PENDING 상태인 task를
  실수로 지우면 그 리마인더/타임아웃 체크가 다시는 실행되지 않는다(재등록하지 않는 한). 이
  이슈는 API만 만들고 실수 방지(확인 다이얼로그 등)는 관리자 화면 몫으로 남긴다.
