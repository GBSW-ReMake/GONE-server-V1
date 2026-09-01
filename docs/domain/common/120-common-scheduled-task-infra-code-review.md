# #120 범용 이벤트 스케줄링 인프라 — 코드 리뷰 결과

리뷰 범위: `git diff origin/dev...HEAD`(브랜치 `feat/#120-scheduled-task-infra`, `origin/dev`
`bcf62fc` 기준 14개 파일, 약 1382줄). `common/schedule` 패키지 전체(엔티티/리포지토리/
서비스/실행기/러너/재시도정책/핸들러 인터페이스), Flyway 마이그레이션, 대응 단위 테스트
4개 파일. 컨텍스트가 격리된 별도 에이전트(`code-review` 스킬)에게 위임해 진행했다.

## 요약
- Critical: 없음
- High: 1건(#1) — 반영 완료
- Medium: 1건(#2) — 반영 완료
- Low: 1건(#3) — 기획서에 이미 문서화된 의도된 트레이드오프, 조치 불필요

**반영 내역(2026-09-01):** #1/#2 모두 승인된 기획서의 계약(엔드포인트/스키마/정책)을 바꾸는
변경이 아니라 구현 세부사항의 결함 수정이라 별도 재승인 없이 즉시 반영했다.

---

### 1. 🟠 High — `schedule()`의 DONE/FAILED 재등록이 유니크 제약 위반으로 실패할 수 있음

**문제**: `ScheduledTaskService.schedule()`(`src/main/java/com/remake/gone/common/schedule/ScheduledTaskService.java:46-49`,
수정 전)은 기존 DONE/FAILED 행을 `delete()`한 뒤 같은 트랜잭션에서 `save(new
ScheduledTask(...))`로 새 행을 등록한다. `ScheduledTask`는
`@GeneratedValue(strategy = GenerationType.IDENTITY)`를 쓰는데, IDENTITY 전략은 생성된 PK를
`persist()` 호출 시점에 바로 알아야 하므로 Hibernate가 INSERT를 flush까지 미루지 못하고
**즉시** 실행한다. 반면 `delete()`가 큐에 넣은 DELETE는 일반적인 flush 시점(커밋 직전)까지
지연된다. 그 결과 `save()`가 실행하는 즉시 INSERT 시점에는 옛 행이 아직 DB에 남아있어,
`uq_scheduled_task_type_ref (task_type, reference_id)` 유니크 제약과 충돌해
`DataIntegrityViolationException`이 발생하고 호출자의 트랜잭션 전체가 롤백된다(예: outing의
`departOuting`/`returnOuting`이 이 메서드를 트랜잭션 안에서 호출하는 구조라, 스케줄 등록
실패가 도메인 요청 자체의 실패로 번진다).

기획서(`120-common-scheduled-task-infra.md`의 "DONE/FAILED 정리 후 재등록하는 이유" 절)가
스스로 밝히듯, 현재 유일한 소비자(#99의 `OUTING_TIMEOUT`)는 매번 새 `referenceId`만 쓰므로
지금 당장은 이 경로를 타지 않는다 — 하지만 정확히 "같은 `referenceId`에 반복 등록이
필요한 도메인이 재사용할 수 있도록" 이 정리 로직을 처음부터 넣어뒀다고 명시했기 때문에,
고쳐두지 않으면 나중에 그 재사용이 실제로 일어나는 시점에야 발견되는 잠복 결함이 된다.

**해결 방안**:
1. `delete()` 직후 `scheduledTaskRepository.flush()`를 호출해 DELETE를 즉시 실행시킨다 —
   이후 `save()`의 즉시 INSERT 시점에는 옛 행이 이미 지워진 뒤라 충돌하지 않는다. 한 줄
   추가로 끝나고, 트랜잭션 경계나 반환값은 그대로다. **채택.**
2. `ScheduledTask`의 PK 생성 전략을 `IDENTITY`에서 `SEQUENCE`(또는 애플리케이션 레벨
   ID 생성)로 바꿔 INSERT도 flush까지 지연시킨다 — 근본적으로 "즉시 실행" 자체를
   없애지만, 이 프로젝트의 다른 모든 엔티티가 `IDENTITY`를 쓰는 컨벤션과 어긋나고
   MySQL에서 시퀀스를 흉내 내는 별도 테이블/설정이 필요해 비용이 크다.
3. `delete()` 대신 기존 행을 `UPDATE`로 재사용(새 값으로 필드를 덮어쓰기)해 DELETE+INSERT
   자체를 피한다 — INSERT/DELETE 순서 문제가 원천적으로 사라지지만, `ScheduledTask`
   생성자가 `final` 성격의 불변 필드 초기화에 의존하는 현재 설계를 변경 가능한 setter
   기반으로 바꿔야 해 엔티티 설계 자체를 건드리게 된다.

**반영**: 방안 1 적용(`ScheduledTaskService.java`).

---

### 2. 🟡 Medium — `ScheduledTaskExecutor.execute()`의 조회 단계가 try/catch 밖에 있음

**문제**: 클래스 Javadoc은 "한 폴링 틱에서 처리하는 여러 건 중 한 건의 실패가 다른 건에
영향을 주지 않게 한다"고 명시하지만, 수정 전 코드는 `scheduledTaskRepository.findById(taskId)`
호출과 `handlers.get(...)` 조회가 `try` 블록 밖에 있었다. `findById`가 예외를 던지면(예:
일시적 DB 커넥션 장애) 그 예외가 `execute()` 밖으로 그대로 전파되고,
`ScheduledTaskRunner.run()`의 `forEach`(`try/catch` 없음)가 중단돼 같은 틱에서 아직 처리하지
않은 나머지 task ID들이 이번 틱에서 통째로 스킵된다 — 문서화된 "건별 격리" 보장이 실제로는
`handler.handle()` 내부 예외에만 적용되고 조회 단계 예외에는 적용되지 않았다. 실제 영향은
다음 폴링 틱(10초 뒤)에 자동으로 다시 시도되므로 데이터 손실은 없지만, 문서가 약속한
동작과 다르다.

**해결 방안**:
1. `findById` 호출을 별도 `try/catch`로 감싸, 실패 시 로그만 남기고 조용히 반환한다(해당
   task는 상태 변경 없이 남아 다음 틱에 자연히 재시도됨) — 기존 흐름/트랜잭션 구조를
   바꾸지 않는 최소 변경. **채택.**
2. `ScheduledTaskRunner.run()`의 `forEach`를 각 호출마다 `try/catch`로 감싸 `Executor` 쪽은
   그대로 두고 호출부에서 격리한다 — 마찬가지로 효과는 있지만, "건별 격리"의 책임 소재가
   `Executor`(트랜잭션 경계를 가진 쪽)가 아니라 `Runner`로 옮겨가 이후 유지보수 시 두 클래스
   중 어디를 봐야 하는지 헷갈릴 수 있다.

**반영**: 방안 1 적용(`ScheduledTaskExecutor.java`).

---

### 3. 🟢 Low — 다중 인스턴스 배포 시 같은 task가 중복 실행될 수 있음

**문제**: `ScheduledTaskRunner`/`ScheduledTaskExecutor`는 행 잠금(`SELECT ... FOR UPDATE`/
`SKIP LOCKED`)이나 분산 락 없이 `status='PENDING'` 조건만으로 due task를 가져온다. 인스턴스가
여러 개면 두 인스턴스가 같은 시각에 같은 task를 각자 due로 판단해 `handle()`을 중복
실행할 수 있다(예: 알림 중복 발송).

**조치 불필요 — 이미 기획서에 명시된 의도된 트레이드오프**: `120-common-scheduled-task-infra.md`의
"다중 인스턴스 배포" 절이 정확히 이 시나리오를 다루며, 현재 배포 환경이 단일 인스턴스
전제(보스 확인, 2026-08-28)임을 근거로 claim 로직/분산 락을 지금 도입하지 않기로(YAGNI)
이미 결정했다. 다중 인스턴스 전환이 실제로 결정되면 그 시점에 이 이슈로 돌아와
재검토하기로 문서화되어 있다.

---

## 확인한 항목 중 문제 없었던 것
- 지수 백오프 계산식(`ScheduledTask.markFailed`)을 직접 검산 — 30×2^n(최대 30분) 공식대로
  1회 실패 60초, 2회 120초, 3회 240초, 4회 480초 뒤로 미뤄지는 게 Javadoc 설명과 실제 코드
  모두 일치.
- `RetryPolicy.DEFAULT`(5회/30초/30분)가 `RetryPolicyTest`로 회귀 방지됨.
- `ScheduledTaskExecutor`가 `@Transactional(REQUIRES_NEW)`로 건별 트랜잭션을 분리해, 한 건의
  핸들러 예외가 같은 틱에서 이미 처리된 다른 건을 롤백시키지 않음(#99 v4 대비 개선, 기획서에
  명시된 개선 사항과 실제 구현 일치).
- `common/schedule` 패키지가 신규 HTTP 엔드포인트를 추가하지 않아 Notion API 명세서 반영
  대상이 아니라는 기획서의 판단과 일치.
