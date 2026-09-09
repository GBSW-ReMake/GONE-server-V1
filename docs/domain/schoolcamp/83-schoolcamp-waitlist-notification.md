# #83 스쿨캠핑 자리나면 알림받기(대기자 알림) 기능 — 기획서

관련 이슈: [#83 스쿨캠핑 자리나면 알림받기(대기자 알림) 기능](https://github.com/GBSW-ReMake/GONE-server-V1/issues/83)
마스터 기획서: [1_schoolcamp-domain.md](./1_schoolcamp-domain.md)의 "아직 결정 안 된 것" 절에서
별도 이슈로 분리하기로 확정한 항목.
선행 이슈: [#70](./70-schoolcamp-cancel-update.md)(취소, 완료·머지됨) — 이 이슈의 알림 발송
트리거가 #70의 `cancelApplication`에 붙는다. `notification` 인프라(#59/#65)도 이미
구현되어 있어 추가 선행 조건 없음.

> **갱신(2026-08-21, 리뷰 반영)**: 초안은 대기 등록을 `month`(yyyyMM) 파라미터로 받아 특정
> 미래 달을 미리 등록할 수 있게 설계했으나, 보스 리뷰에서 **"미래 달 미리 등록" 개념 자체를
> 없애고 세 엔드포인트(등록/취소/조회) 전부 파라미터 없이 서버가 "지금 이 순간의 이번 달"만
> 다루도록 구조를 단순화**하기로 확정했다. 아래 본문은 이 확정 구조를 기준으로 다시 썼다.

## 개요/목적
학생이 마감된(CLOSED) 날짜에 "자리나면 알림받기"를 등록해두면, **이번 달 안에서 어느
세션이든** 취소가 발생할 때마다 알림을 받는 기능. 확정된 핵심 설계:

- **대기 등록은 세션(날짜)도 아니고 임의의 달도 아니고, 항상 "지금 이 순간의 이번 달"
  하나뿐이다.** 등록/취소/조회 세 엔드포인트 모두 `month` 같은 파라미터를 받지 않는다 —
  서버가 요청 시점에 `YearMonth.now(KST)`로 계산한 달을 그대로 쓴다. UI는 마스터 기획서가
  구상한 대로 캘린더의 마감된 날짜 카드에 "자리나면 알림받기" 버튼을 슬라이딩으로 붙이지만,
  실제로 등록되는 건 그 버튼을 누른 시점의 "이번 달" 전체다 — 같은 달의 다른 마감된 카드도
  동시에 "등록됨" 상태로 보인다(프론트 구현 시 참고). 다음 달 캘린더를 미리 보면서 다음 달
  대기를 등록하는 시나리오는 이번 범위에 없다.
- **월 중복 참여자(이미 이번 달 확정 참여 중인 학생)도 대기 등록 자체는 막지 않는다.**
  실제로 신청을 시도하는 시점에 기존 `SCHOOLCAMP_003`이 알아서 막아준다 — 대기 등록
  단계에서 미리 차단하는 추가 검증을 넣지 않는다.
- **월 중복 참여자에게도 취소 알림은 그대로 보낸다(확정).** 그 학생이 신청을 시도하면
  결국 `SCHOOLCAMP_003`으로 막히더라도, 알림 발송 시점에 "이미 참여 확정된 사람인지"를
  걸러내는 별도 필터링은 넣지 않는다 — 단순하게 그 달 대기자 전원에게 동일하게 보낸다.
- **알림은 그 달에 대기 등록한 사람 전원에게 동시에 간다**(선착순 1명에게만 보내는
  방식 아님). 실제 신청 성사는 기존 세션 원자적 점유(`SchoolCampSessionClaimService.
  claim`, #68)가 이미 레이스를 정리해주므로, 알림 발송 단계에서 순서를 가릴 필요가 없다.
- **대기 등록은 알림을 한 번 보냈다고 자동으로 사라지지 않는다.** 본인이 직접 취소하기
  전까지 계속 유효해서, 같은 달 안에 취소가 여러 번(다른 날짜에서도) 발생하면 그때마다
  매번 알림을 받는다. "이번 달이 끝나면 자동 소멸"은 별도 배치로 구현하지 않는다 — 아래
  "데이터 모델"의 조회 방식 자체가 달이 바뀌면 자연히 예전 등록을 더 이상 찾지 않게
  되어 있어(항상 "지금 이 순간의 이번 달"로만 조회), 실질적으로 같은 효과를 낸다(상세는
  아래 참고).
- **유령 점유 회수 스케줄러(#84, 아직 미구현)로 세션이 반환될 때는 이번 이슈 범위에서
  알림을 보내지 않는다.** #84가 실제로 구현될 때 이 이슈가 만드는
  `SchoolCampWaitlistService.notifyForMonth(YearMonth)`를 그대로 재사용할지 검토하라는
  메모를 남겨둔다.

## 권한 모델
- 등록/취소/상태 조회: `STUDENT`(마스터 기획서의 신청 권한과 동일 — 대기 개념도 학생
  전용 기능이다)

## 데이터 모델

### `SchoolCampWaitlist` 엔티티 (`school_camp_waitlist` 테이블, 신규, `V14__add_
schoolcamp_waitlist.sql`)
한 학생의 "이번 달 자리나면 알림받기" 구독 1건.

- `id`
- `student_user_id` — 대기 등록한 학생(`User` FK)
- `month` — 등록 시점에 계산한 "그 순간의 이번 달"(`DATE`, 항상 그 달의 1일로 저장 —
  예: 2026년 4월에 등록 → `2026-04-01`). API 요청/응답에서 클라이언트가 이 값을 직접
  지정하는 일은 없다 — 오직 서버가 등록 시점에 채워 넣고, 이후 조회/취소 시 "지금 이
  순간의 이번 달"과 비교하는 내부 값이다
- `registered_at` — 등록(또는 재등록) 시각
- `cancelled_at` — 취소 시각(nullable). `null`이면 유효한 대기 등록, 값이 있으면 취소됨
- **유니크 제약**: `(student_user_id, month)` — 학생 1명당 달 1개에 대해 행이 **딱 하나만
  영구히 존재**한다(soft-delete로 매번 새 행을 쌓는 `SchoolCampApplication` 패턴과 다름).
  같은 달 안에서 취소 후 재등록하면 새 행을 만들지 않고 **기존 행을 재활성화**한다
  (`cancelled_at = null`, `registered_at`을 현재 시각으로 갱신) — 동시에 같은 학생이 같은
  순간 두 번 등록을 시도하는 경우도 이 제약이 그대로 막아준다(#70의 `(application_id,
  student_user_id)` 유니크 제약과 같은 이유 — `DataIntegrityViolationException`을 잡아
  `SCHOOLCAMP_014`로 변환).

**"달이 바뀌면 자연히 안 보이는" 이유**: 등록/취소/조회 세 엔드포인트가 전부 조회 조건에
`month = YearMonth.now(KST)`를 그대로 쓴다. 4월에 등록한 행(`month = 2026-04-01`)은
5월이 되는 순간부터 세 엔드포인트 중 어느 것도 그 행을 다시 찾지 않는다(5월 요청은
`month = 2026-05-01`로 조회하므로) — 그래서 "이번 달이 끝나면 자동으로 해제된 것처럼
보이는" 효과가 별도 배치 없이 자연히 나온다. 다만 이건 **조회가 안 될 뿐 DB 행 자체는
`cancelled_at = null`인 채로 영구히 남는다**는 뜻이다(아래 "리스크" 절의 잔여 위험 참고).

## 엔드포인트

### 1. `POST /api/v1/school-camps/waitlist` — 이번 달 대기 등록
**권한**: `STUDENT`

**요청**: 바디 없음(파라미터 없음 — 서버가 요청 시점을 "이번 달"로 계산)

**응답** (`201 Created`)
```json
{
  "success": true,
  "data": { "month": "202604", "registeredAt": "2026-04-03T09:12:00" },
  "message": "자리나면 알림받기를 등록했습니다.",
  "code": null
}
```

**구현 로직**
1. `thisMonth = YearMonth.now(KST)`
2. `(student_user_id, thisMonth)`로 기존 행 조회
   - 없으면 새 행 삽입(`cancelled_at = null`)
   - 있고 `cancelled_at IS NULL`이면 이미 등록된 상태 → `409` `SCHOOLCAMP_014`
   - 있고 `cancelled_at IS NOT NULL`이면 재활성화(`cancelled_at = null`,
     `registered_at = now`)
3. 삽입 시 유니크 제약 위반(`DataIntegrityViolationException`, 동시 등록 레이스) →
   `409` `SCHOOLCAMP_014`로 변환

**에러**
- 이번 달에 이미 등록됨(동시 요청 포함) → `409` `SCHOOLCAMP_014`

---

### 2. `DELETE /api/v1/school-camps/waitlist` — 이번 달 대기 취소
**권한**: `STUDENT`(본인 것만)

**요청**: 바디/파라미터 없음

**응답** (`200 OK`)
```json
{ "success": true, "data": null, "message": "자리나면 알림받기를 취소했습니다.", "code": null }
```

**구현 로직**
1. `thisMonth = YearMonth.now(KST)`
2. `(student_user_id, thisMonth)` + `cancelled_at IS NULL`로 조회, 없으면 `404`
   `SCHOOLCAMP_015`
3. `cancelled_at = now`로 갱신

**에러**
- 이번 달에 유효한 대기 등록이 없음 → `404` `SCHOOLCAMP_015`

---

### 3. `GET /api/v1/school-camps/waitlist/me` — 이번 달 대기 등록 상태 조회
**권한**: `STUDENT`

**요청**: 파라미터 없음 — 캘린더에서 "이번 달"을 보고 있는 프론트가 버튼을 "등록됨"/
"등록 안 됨" 어느 상태로 그릴지 판단하는 용도

**응답** (`200 OK`)
```json
{
  "success": true,
  "data": { "registered": true, "registeredAt": "2026-04-03T09:12:00" },
  "message": "대기 등록 상태를 조회했습니다.",
  "code": null
}
```
등록 안 된 상태면 `{ "registered": false, "registeredAt": null }`.

**구현 로직**
1. `thisMonth = YearMonth.now(KST)`
2. `(student_user_id, thisMonth)` + `cancelled_at IS NULL`로 조회
3. 있으면 `registered: true` + `registeredAt`, 없으면 `registered: false` +
   `registeredAt: null`

---

## 기존 로직 수정 — 취소 시 알림 발송 트리거

### 트리거 위치: `SchoolCampService.cancelApplication`(#70)만
`sessionRepository.release(sessionId)`를 호출하는 지점은 이 코드베이스에 **두 곳**이다.
이번 이슈는 그중 하나에만 알림을 붙인다.

| 호출 위치 | 의미 | 알림 발송 |
|---|---|---|
| `SchoolCampService.cancelApplication`(#70, 엔드포인트 4) | **실제로 확정됐던 신청**이 취소돼 날짜가 다시 열림 — 대기 중이던 학생 입장에서 진짜 "자리가 났다" | **O** (이번 이슈) |
| `SchoolCampSessionClaimService.release`(#68, `releaseQuietly`를 통해 claim 직후 검증 실패 시 호출) | 새 신청이 세션을 막 점유했다가 선생님 검증 등 후속 검증에 실패해 **자기 자신의 점유를 되돌리는** 것 — 그 날짜는 애초에 다른 사람에게 "마감"으로 보인 적조차 없다(같은 요청 안에서 수 ms 만에 점유·해제가 끝남) | **X** |

두 번째 경로에서 알림을 보내면, 실패한 신청 시도마다 대기자 전원에게 스팸성 알림이 갈 수
있다 — 실제로는 아무도 그 날짜를 "잃었다"고 느낀 적이 없는데 "자리 났어요"라는 알림만
반복해서 받는 상황이 된다. 그래서 `cancelApplication`에만 명시적으로 붙인다.

`#84`(유령 점유 회수 스케줄러, 아직 미구현)가 다루는 `SchoolCampSessionClaimService.
release` 호출도 마찬가지로 이번 범위에서 제외한다 — 다만 그 스케줄러가 실제로 세션을
반환하는 시점은 "정말로 아무도 신청하지 않은 채 방치된 자리가 다시 열리는" 순간이라
`cancelApplication`과 실질적으로 같은 성격의 이벤트다. #84 구현 시 이 이슈가 만드는
`SchoolCampWaitlistService.notifyForMonth(YearMonth)`를 그대로 재사용할지 검토하라는
메모를 남겨둔다(재사용 여부는 #84 기획 단계에서 다시 판단).

### 변경 후 `cancelApplication` 동작
```java
@Transactional
public void cancelApplication(Long applicantUserId, Long applicationId, LocalDateTime now) {
  // ... 기존 소유권 확인, 당일 취소 금지 확인, cancelledAt 갱신 ...
  sessionRepository.release(application.getSession().getId());
  waitlistService.notifyForMonth(YearMonth.from(application.getSession().getCampDate()));
}
```
`notifyForMonth(month)`는 **그 세션이 실제로 속한 달**(취소 시점의 "이번 달"이 아니라
`session.campDate` 기준)로 대기자를 조회해, `cancelled_at IS NULL`인 `SchoolCampWaitlist`
전원에게 `NotificationService.send(studentUserId, title, body,
NotificationType.SCHOOLCAMP)`를 반복 호출한다. `cancelApplication`과 같은 트랜잭션 안에서
실행한다 — 기존 신청 시 팀원 초대 알림(#68 엔드포인트 2의 9번)도 같은 방식으로 호출
트랜잭션 안에서 저장되므로 패턴을 맞춘다.

> ⚠️ **잔여 위험(인지, 수용)**: #70의 당일 취소 금지 검증은 `campDate == 오늘`만 막고,
> `campDate`가 과거인 경우는 막지 않는다(코드 확인 완료) — 즉 이론상 학생이 지난달 세션의
> 신청을 뒤늦게 취소할 수 있고, 그러면 `notifyForMonth`가 그 지난달로 조회를 시도한다. 이
> 시점에 그 지난달에 등록됐다가 한 번도 취소되지 않은 오래된 `SchoolCampWaitlist` 행이
> 남아있다면(위 "데이터 모델"의 "조회가 안 될 뿐 행은 남는다" 참고) 그 학생에게 뒤늦은
> 알림이 갈 수 있다. 등록 자체가 항상 "요청 시점의 이번 달"로만 이뤄지고 클라이언트가
> 임의의 과거/미래 달을 지정할 방법이 없어 실무에서 거의 발생하지 않을 극히 드문 경로로
> 보고, 이번 범위에서 별도 가드(예: `session.campDate`의 달이 오늘의 달보다 과거면
> 알림 생략)를 추가하지 않는다.

**알림 문구**: `title = "스쿨캠핑 자리가 났어요!"`, `body = "{year}년 {month}월 스쿨캠핑에
취소로 빈 자리가 생겼어요. 캘린더에서 확인하고 신청해보세요!"`(예: "2026년 4월 스쿨캠핑에
취소로 빈 자리가 생겼어요. 캘린더에서 확인하고 신청해보세요!")

## 신규 DTO
```java
public record SchoolCampWaitlistResponse(
    String month,          // yyyyMM, 서버가 계산한 값(응답 전용, 요청 파라미터 아님)
    LocalDateTime registeredAt
) {}

public record SchoolCampWaitlistStatusResponse(
    boolean registered,
    LocalDateTime registeredAt  // registered == false면 null
) {}
```

## 신규 컴포넌트
- `SchoolCampWaitlistService`(신규, `schoolcamp/service` 패키지): 등록/취소/상태 조회 +
  `notifyForMonth(YearMonth)`. `SchoolCampSessionClaimService`처럼 관심사를 분리한
  전용 서비스로 둔다 — `SchoolCampService`가 이미 700줄을 넘어 더 이상 책임을 늘리지
  않는다.
- `SchoolCampWaitlistRepository`(신규): `findByStudentUserIdAndMonth`,
  `findByStudentUserIdAndMonthAndCancelledAtIsNull`,
  `findByMonthAndCancelledAtIsNull`(알림 발송 대상 조회용)
- 엔드포인트 3개는 기존 `SchoolCampController`에 추가한다 — 스쿨캠핑 전체를 다루는
  컨트롤러가 이미 있어 별도 컨트롤러를 새로 만들 이유가 없다.

## 에러 코드 (`SchoolCampErrorCode`에 추가, 014~015)
14. `SCHOOLCAMP_014` (409) — 이미 대기 등록되어 있습니다
15. `SCHOOLCAMP_015` (404) — 유효한 대기 등록을 찾을 수 없습니다

## 영향 받는 기존 코드
- 신규: `SchoolCampWaitlist`(엔티티), `SchoolCampWaitlistRepository`,
  `SchoolCampWaitlistService`, `SchoolCampWaitlistResponse`/
  `SchoolCampWaitlistStatusResponse`(DTO), `V14__add_schoolcamp_waitlist.sql`(마이그레이션)
- 수정: `SchoolCampController`(엔드포인트 3개 추가), `SchoolCampService.cancelApplication`
  (알림 트리거 1줄 추가, `SchoolCampWaitlistService` 주입), `SchoolCampErrorCode`(014~015
  추가)
- 변경 없음: `SchoolCampSessionClaimService`(`releaseQuietly` 경로는 건드리지 않음),
  기존 엔드포인트 1~6번의 요청/응답 스키마

## 리스크 및 고려사항
- **API 설계 6원칙 체크**:
  - 1번(한 가지를 잘하기): 등록/취소/조회 3개로 나눠 각각 한 가지 책임만 지게 했다.
  - 2번(빠른 시작): 세 엔드포인트 모두 파라미터가 없어 요청/응답 예시만으로 바로 호출
    가능하다.
  - 5번(확장성): `notifyForMonth`가 그 달 대기자 전원을 순회하며 알림을 저장한다 —
    재학생 규모(300명)에서 한 달에 대기자가 수십 명 수준일 걸로 예상돼 별도 배치/비동기
    처리 없이 반복 저장으로 충분하다고 본다.
  - 6번(하위 호환성): 새 엔드포인트만 추가하고 기존 엔드포인트는 건드리지 않아(트리거
    추가는 `cancelApplication`의 응답 스키마에 영향 없음) 해당 없음.
- **동시성**: 대기 등록의 유일한 경합 지점은 "같은 학생이 같은 순간 두 번 등록"뿐이고,
  `(student_user_id, month)` 유니크 제약 + `DataIntegrityViolationException` 캐치로
  #70의 `SCHOOLCAMP_011`과 동일한 패턴으로 처리한다.
- **알림 스팸 가능성(인지, 수용)**: 한 달에 취소가 여러 번 발생하면 같은 학생이 여러 번
  알림을 받는다 — 확정된 동작이다(대기 항목이 자동 소멸하지 않으므로).
- **월 중복 참여자 필터링 없음(확정)**: 이미 이번 달 참여가 확정된 학생도 대기 등록·알림
  수신 양쪽 다 막지 않는다. 신청을 시도하면 결국 `SCHOOLCAMP_003`으로 막힌다.
- **과거 달 지연 취소로 인한 잔여 알림 위험(인지, 수용)**: 위 "기존 로직 수정" 절의 경고
  박스 참고 — 별도 가드를 추가하지 않는다.
- **오래된 대기 행이 DB에 영구히 남음(인지, 수용)**: 조회 조건이 항상 "이번 달"이라 지난
  달 행은 다시 조회되지 않지만, 실제로 `cancelled_at`을 채워 정리하는 배치는 없다 —
  단순히 안 보일 뿐 테이블에는 계속 쌓인다. 재학생 규모에서 몇 년 누적돼도 문제 될 양은
  아니라고 보고 이번 범위에서 정리 배치를 만들지 않는다.
- **FCM 등 실제 푸시 인프라는 여전히 없다**: `NotificationService.send`가 하는 일은
  DB에 알림 레코드를 저장하는 것뿐이다(#59/#65와 동일) — 실시간 푸시가 아니라 앱 내
  알림함에 쌓이는 방식이다. 이 이슈도 같은 한계를 그대로 물려받는다.
- **Notion API 명세서**: 머지 후(17단계) 새 엔드포인트 3개를 반영해야 한다.

## 테스트
- `SchoolCampWaitlistService`:
  - 등록: 첫 등록 성공, 이미 등록된 상태에서 재등록 시 `SCHOOLCAMP_014`, 취소했던 달
    안에서 재등록 시 기존 행이 재활성화되는지(`cancelled_at`이 다시 `null`이 되는지)
  - 취소: 정상 취소, 등록된 적 없는 상태에서 취소 시 `SCHOOLCAMP_015`
  - 상태 조회: 등록됨/등록 안 됨/취소됨(등록 안 됨과 동일하게 취급되는지) 각각 확인
  - `notifyForMonth`: 그 달 활성 대기자 전원에게 `NotificationService.send`가 호출되는지,
    취소된(비활성) 대기 항목은 대상에서 빠지는지, 다른 달 대기자는 영향받지 않는지,
    이미 이번 달 참여 확정된 학생도 대상에서 빠지지 않는지(필터링 없음 확인)
- `SchoolCampService.cancelApplication`(#70 회귀 + 신규 검증):
  - 취소 성공 시 `notifyForMonth`가 정확히 그 세션이 속한 달로 호출되는지
- **`SchoolCampSessionClaimService.release`/`releaseQuietly` 경로는 알림을 보내지 않는지
  확인하는 회귀 테스트**(이번 이슈에서 가장 중요한 케이스) — #68의 클레임 실패 롤백
  시나리오를 재현해 `notifyForMonth`가 호출되지 않음을 검증한다.
