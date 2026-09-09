# #83 스쿨캠핑 자리나면 알림받기(대기자 알림) 기능 — 코드 리뷰 결과

리뷰 대상: `git diff dev...feat/#83-schoolcamp-waitlist`
(`SchoolCampController`, `SchoolCampWaitlist`/`SchoolCampWaitlistResponse`/
`SchoolCampWaitlistStatusResponse`, `SchoolCampWaitlistRepository`,
`SchoolCampWaitlistService`(신규), `SchoolCampService.cancelApplication`(수정),
`SchoolCampErrorCode`(014~015 추가), `V14__add_schoolcamp_waitlist.sql`, 관련 테스트 4종)

리뷰어는 구현 과정에 관여하지 않은 격리된 세션에서, 기획서
(`83-schoolcamp-waitlist-notification.md`)만 참고해 진행했다
([code-review-isolation.md](../../rules/code-review-isolation.md) 준수). 정적 코드 대조 외에
`./gradlew checkstyleMain checkstyleTest`와 `./gradlew test --tests "*SchoolCamp*"`를 실제로
실행해 스타일/테스트 통과 여부를 확인했다.

## 발견 사항

### 1. 🟠 High — `SchoolCampSessionClaimService.release`/`releaseQuietly` 경로가 알림을 보내지 않는다는 회귀 테스트가 없음 → **반영 완료**

기존 `applyToCamp` 실패 케이스 5곳(`releaseQuietly` 경로가 호출되는 지점) 전부에
`verifyNoInteractions(waitlistService)`를 추가했다(해결 방안 1번 채택).


**문제**: 기획서 "테스트" 절은 "`SchoolCampSessionClaimService.release`/`releaseQuietly` 경로는
알림을 보내지 않는지 확인하는 회귀 테스트(이번 이슈에서 가장 중요한 케이스)"를 명시적으로
요구한다. 그런데 diff에는 `SchoolCampSessionClaimService.java`도,
`SchoolCampSessionClaimServiceIntegrationTest.java`도 변경되지 않았고,
`SchoolCampServiceTest`의 기존 `applyToCamp` 실패 케이스들(claim 이후 검증 실패로
`releaseQuietly`가 호출되는 경로 — 선생님 검증 실패, 팀원 검증 실패, 월 중복 검증 실패 등
여러 곳에서 `verify(sessionClaimService).release(SESSION_ID)`로 이미 검증되고 있다)에도
`waitlistService`와 상호작용하지 않는지 확인하는 어서션이 하나도 추가되지 않았다.

현재 구현 자체는 구조적으로 안전하다 — `SchoolCampSessionClaimService`는
`SchoolCampWaitlistService`를 주입받지 않고, `SchoolCampService.releaseQuietly`도
`sessionClaimService.release(sessionId)`만 호출할 뿐 `waitlistService`를 전혀 건드리지
않는다(코드 확인 완료, 실제 알림 스팸 버그는 없음). 다만 기획서가 이 케이스를 "가장
중요"하다고 못 박은 이유는, 향후 #84(유령 점유 회수 스케줄러)가 이 이슈가 만든
`notifyForMonth`를 재사용하려 할 때 실수로 `releaseQuietly` 경로에도 알림을 연결해버리는
회귀를 미리 잡아두려는 의도로 읽힌다. 지금 그 안전장치가 비어 있어, 이 이슈가 명시적으로
요구한 테스트 커버리지 항목 하나가 구현되지 않은 상태다.

**해결 방안**:
1. `SchoolCampServiceTest`의 기존 `applyToCamp` 실패 케이스(claim 이후 검증 실패로
   `releaseQuietly`가 호출되는 경로)에 `verifyNoInteractions(waitlistService)`를 한 줄씩
   추가한다 — `cancelApplication` 테스트에 이미 같은 패턴(`verify(waitlistService)
   .notifyForMonth(...)`/`verifyNoInteractions(waitlistService)`)이 적용돼 있어 일관된
   방식이고, 기존 테스트 메서드에 검증 한 줄만 추가하면 되어 비용도 낮다. 단점은 여러
   실패 케이스마다 반복해서 넣어야 해 다소 번거롭다는 점 정도다.
2. `SchoolCampSessionClaimService`를 직접 겨냥한 단위 테스트를 새로 만들어, 이 클래스가
   `NotificationService`/`SchoolCampWaitlistService`를 아예 주입받지 않는다는 것과
   `release` 호출이 그 두 빈과 전혀 상호작용하지 않음을 클래스 레벨에서 증명한다 — 이
   클래스가 애초에 두 빈을 주입받지 않으므로 사실 컴파일 타임에 이미 보장되는 성질이라
   테스트로서의 실효성은 1번 방안보다 낮지만, "이 클래스는 알림 인프라를 절대 모른다"는
   아키텍처적 의도를 명시적으로 문서화하는 효과가 있고 #84 구현 시 참고할 앵커가 된다.

### 2. 🟡 Medium — `notifyForMonth` 알림 저장 실패가 이미 유효했던 취소 자체를 함께 롤백시킬 수 있음 → **수용(해결 방안 1번), 코드 변경 없음**

프로젝트 전역이 이미 채택한 "알림 저장 실패 = 호출자 트랜잭션과 함께 롤백" 정책과
일관되게 그대로 둔다. 이 트레이드오프를 여기 문서에 남겨 향후 동일 지적이 반복되지
않게 한다.

**문제**: `SchoolCampService.cancelApplication`(`SchoolCampService.java:290`)은
`application.setCancelledAt`/`applicationRepository.save`/`sessionRepository.release`가 모두
성공한 뒤, 같은 `@Transactional` 안에서 `waitlistService.notifyForMonth(...)`를 호출한다.
`notifyForMonth`는 그 달 대기자 전원을 순회하며 `notificationService.send(...)`를
호출하는데, `NotificationService.send`의 클래스 문서(`NotificationService.java:14-17`)는
"저장 실패는 그대로 예외로 전파한다 — 호출자 트랜잭션과 함께 롤백되는 게 맞는 동작"이라고
명시된 정책이다. 즉 대기자 중 단 한 명에게라도 알림 저장이 실패하면(일시적 DB 오류 등),
그 대기자와 아무 상관 없는 원래 취소 요청자의 정상적인 신청 취소·세션 반환까지 통째로
롤백된다.

이건 새로 만든 버그라기보다 `NotificationService`가 이미 채택한 정책(#59/#65 마스터
기획서 "정책 가정")을 그대로 물려받은 것이고, `updateApplication`의
`sendInviteNotifications`도 같은 트랜잭션 안에서 같은 방식으로 호출된다 — #83 기획서도
"같은 방식으로 호출 트랜잭션 안에서 저장되므로 패턴을 맞춘다"고 이 정합성을 명시적으로
의도했다. 다만 `cancelApplication`은 #83 이전에는 대기자 수만큼 반복 호출되는 구간이 전혀
없었으므로, 이번 변경으로 "취소 1건 처리 중 실패할 수 있는 지점의 개수"가 대기자 수만큼
늘어난 것은 사실이라 별도로 짚어둘 값어치가 있다.

**해결 방안**:
1. 지금처럼 그대로 둔다 — 프로젝트 전체가 "알림 저장 실패 = 트랜잭션 롤백"을 일관된
   정책으로 채택했고, 재학생 규모(대기자 수십 명)에서 `notificationService.send`가
   실패할 상황은 DB 자체 장애 수준이라 실무적 발생 빈도가 매우 낮다. 이 경우 원래 취소
   자체도 어차피 같은 DB 장애로 실패했을 가능성이 높아, 롤백이 "부수 피해"라기보다
   "같이 실패하는 게 자연스러운 상황"에 가깝다고 볼 수도 있다. 비용은 0이지만, 이
   트레이드오프를 문서에 남기지 않으면 다음에 유사한 지적이 반복될 수 있다.
2. `notifyForMonth` 호출부만 별도 `try/catch`로 감싸 예외를 로그로만 남기고 삼킨다
   (`releaseQuietly`가 release 실패를 삼키는 것과 같은 패턴) — 대기자 알림은 "가능하면
   보내는" 부가 기능이고 취소 자체의 성공 여부와는 독립적이어야 한다는 관점에 부합한다.
   단점은 알림 저장 실패가 조용히 묻혀 관측이 어려워지고(로그 감시 없이는 알림 유실을
   아무도 모른다), `NotificationService`가 이미 "실패 시 트랜잭션과 함께 롤백"을 공식
   정책으로 문서화해 둔 상태라 이 호출부만 예외로 두면 프로젝트 전역 정책과 어긋난다.

### 3. 🟡 Medium — `register()` 재활성화 경로는 동시 재등록 레이스에 대한 보호가 없음 → **수용(해결 방안 2번), 코드 변경 없음**

"같은 학생이 취소 직후 두 번 등록 버튼을 거의 동시에 누르는" 매우 좁은 창에서만
발생하고, 결과도 두 요청 모두 "등록됨"으로 수렴해 데이터 정합성이 깨지지 않는다.
`@Version` 낙관적 락 도입은 이 도메인에 전례가 없어 비용 대비 이득이 낮다고 판단해
지금은 그대로 둔다.

**문제**: `SchoolCampWaitlistService.register`(`SchoolCampWaitlistService.java:351-370`)에서
신규 삽입 경로(`newWaitlist`)는 `DataIntegrityViolationException`을 잡아 `SCHOOLCAMP_014`로
변환하지만, 같은 학생이 이미 취소했던 같은 달 행을 재활성화하는 경로(`existing.isPresent()
&& cancelledAt != null`)는 `(student_user_id, month)` 유니크 제약을 건드리지 않는 단순
`UPDATE`라, 두 요청이 동시에 같은 취소된 행을 재활성화하려 하면 어느 쪽도 예외를 만나지
않고 둘 다 201 응답을 받는다 — 마지막에 커밋된 값이 조용히 이긴다. 클라이언트 입장에서는
자신이 받은 응답의 `registeredAt`이 실제로 DB에 남아있는 값과 다를 수 있다는 뜻이다.
기획서는 "동시에 같은 학생이 같은 순간 두 번 등록을 시도하는 경우도 이 제약이 그대로
막아준다"고 서술했는데, 이 문장은 신규 삽입 레이스에만 해당하고 재활성화 레이스는 실제로
유니크 제약의 보호를 받지 못한다.

**해결 방안**:
1. 낙관적 락(`@Version`)을 `SchoolCampWaitlist`에 추가해 재활성화 `UPDATE`가 동시에
   충돌하면 `OptimisticLockException`이 나도록 하고, 이를 잡아 동일하게 `SCHOOLCAMP_014`로
   변환한다 — 신규 삽입 레이스와 정확히 같은 사용자 경험(409)을 준다는 장점이 있지만,
   이 프로젝트에서 `@Version`을 쓰는 전례가 `Outing` 엔티티 하나뿐이라(스쿨캠핑 도메인에는
   전례 없음) 새 패턴을 처음 들이는 비용이 든다.
2. 실무 영향이 미미하다고 보고 그대로 둔다 — 재활성화 레이스는 "같은 학생이 취소 직후 두
   번 등록 버튼을 거의 동시에 누르는" 매우 좁은 창에서만 발생하고, 결과도 기능적으로는
   두 요청 모두 "등록됨" 상태로 수렴해(단지 `registeredAt` 값만 근소하게 다를 뿐) 데이터
   정합성이 깨지는 수준은 아니다. 비용은 0이지만, 이 레이스를 인지하고 수용했다는 근거를
   기획서나 이 문서에 남겨두지 않으면 나중에 같은 지적이 반복될 수 있다.

### 4. 🟢 Low — `newWaitlist`의 `DataIntegrityViolationException` 캐치가 원인을 구분하지 않고 전부 `SCHOOLCAMP_014`로 변환함 → **수용(해결 방안 1번), 코드 변경 없음**

기존 `updateApplication`의 동일 패턴과 일관성을 유지한다.

**문제**: `SchoolCampWaitlistService.newWaitlist`(`SchoolCampWaitlistService.java:372-385`)는
`waitlistRepository.save(waitlist)`에서 나는 `DataIntegrityViolationException`을 원인과
무관하게 전부 `SCHOOLCAMP_014`(이미 대기 등록됨)로 변환한다. 실제로는
`(student_user_id, month)` 유니크 제약 위반 외에도 `student_user_id`의 FK 제약 위반(예:
요청 처리 중 해당 사용자 계정이 삭제되는 극단적 레이스) 등 다른 원인으로도 같은 예외
타입이 날 수 있는데, 이 경우에도 클라이언트는 원인과 무관한 "이미 대기 등록되어 있습니다"
메시지를 받는다.

이는 `SchoolCampService.updateApplication`이 `memberRepository.saveAll`의
`DataIntegrityViolationException`을 원인 구분 없이 `CONCURRENT_UPDATE_CONFLICT`로 변환하는
기존 컨벤션과 일치한다 — 이번 diff가 새로 도입한 패턴은 아니다.

**해결 방안**:
1. 지금처럼 둔다 — `student_user_id`는 인증된 JWT에서 추출된 값이라 FK 위반이 실무에서
   발생할 가능성이 극히 낮고, 기존 컨벤션과의 일관성이 더 중요하다고 볼 수 있다. 비용 0.
2. 예외의 근본 원인(`getMostSpecificCause().getMessage()` 등)에 유니크 제약 이름
   (`uq_waitlist_student_month`)이 포함돼 있는지 확인한 뒤에만 `SCHOOLCAMP_014`로
   변환하고, 그 외에는 원래 예외를 다시 던지거나 범용 500으로 처리한다 — 원인 진단
   정확도는 올라가지만, DB 드라이버가 노출하는 예외 메시지 포맷에 문자열 매칭으로
   의존하게 돼 깨지기 쉽고(DB 벤더/버전 변경 시 문자열이 바뀔 수 있음), 이 프로젝트에
   아직 이런 패턴의 전례가 없어 오히려 기존 컨벤션과 어긋난다.

### 5. 🟢 Low — `register()`의 `waitlist` 지역 변수가 두 분기 모두에서 대입만 되고 이후 읽히지 않음 → **반영 완료**

해결 방안 1번대로 `waitlist` 변수를 없애고, 재활성화 로직을 `reactivate` 메서드로
분리했다. `newWaitlist`도 반환값을 쓰지 않아 `void`로 바꿨다.

**문제**: `SchoolCampWaitlistService.register`에서 `if`/`else` 두 분기 모두 지역 변수
`waitlist`에 값을 대입하지만(`waitlist = existing.get()` 또는 `waitlist =
newWaitlist(...)`), 메서드의 반환값은 `thisMonth`와 매개변수 `now`만으로 만들어져
`waitlist`를 전혀 읽지 않는다. 죽은 대입(dead store)이라 동작에는 영향이 없지만, 다음에
이 코드를 보는 사람이 "이 변수가 응답에 쓰이나?"를 확인하려고 시간을 쓰게 만든다.

**해결 방안**:
1. `waitlist` 변수를 없애고 각 분기 안에서 필요한 호출만 남긴다(예: 재활성화 분기는
   `existing.get()`의 결과를 지역 변수로 받아 그 블록 스코프 안에서만 쓰고, 신규 삽입
   분기는 `newWaitlist(...)`의 반환값을 아예 버린다) — 가장 단순하고 위험이 없는 정리다.
2. 지금처럼 두되, 향후 응답에 대기 등록의 PK나 상태를 더 담아야 할 때를 대비해 일부러
   남겨둔 것이라면 그 의도를 주석으로 남긴다 — 실질적으로 지금 당장 쓰이지 않는 코드를
   정당화하는 방식이라 1번보다 권장하지 않지만, "곧 필드가 늘어날 예정"이라는 확실한
   근거가 있다면 선택지가 될 수 있다.

## 확인했지만 문제 없었던 항목 (Critical 없음)

- **데이터 모델**: `SchoolCampWaitlist` 엔티티와 `V14__add_schoolcamp_waitlist.sql`이
  기획서와 정확히 일치한다 — `(student_user_id, month)` 유니크 제약(`uq_waitlist_student_
  month`), `month`를 `YearMonth.atDay(1)`로 그 달의 1일에 저장, `cancelled_at` nullable로
  soft-cancel(등록 시점의 `@CreationTimestamp` 대신 재활성화 때 `registeredAt`을 직접
  갱신하는 이유까지 엔티티 javadoc에 명시). `SchoolCampApplication` 엔티티와 Lombok
  어노테이션 조합(`@Getter @Setter @NoArgsConstructor(PROTECTED) @AllArgsConstructor
  @Builder`), 필드 스타일까지 나란히 비교해 일치를 확인했다.
- **엔드포인트 3개 무파라미터**: `POST/DELETE /api/v1/school-camps/waitlist`,
  `GET /api/v1/school-camps/waitlist/me` 모두 `@AuthenticationPrincipal`만 받고
  `@RequestBody`/`@RequestParam`이 전혀 없으며, "이번 달"은 컨트롤러에서
  `LocalDateTime.now(KST)`로 매 요청마다 계산해 서비스에 넘긴다(같은 컨트롤러의
  `cancelApplication`/`updateApplication`과 동일한 패턴).
- **재등록 시 같은 행 재활성화**: `register()`가 `findByStudentUserIdAndMonth`로 취소
  여부와 무관하게 기존 행을 먼저 찾고, 있으면 `cancelledAt = null`/`registeredAt = now`로
  갱신 후 저장(새 행 생성 없음)하는 것을 코드와
  `SchoolCampWaitlistServiceTest.Register.reactivatesCancelledRow` 테스트 양쪽에서
  확인했다.
- **신규 삽입 레이스 처리**: 동시에 같은 학생이 같은 순간 처음 등록을 시도하는 케이스는
  `newWaitlist`의 `try/catch(DataIntegrityViolationException) → SCHOOLCAMP_014` 변환으로
  막힌다. `updateApplication`의 `memberRepository.saveAll` 예외 변환과 동일한 패턴이고,
  `throwsOnConcurrentRegisterRace` 테스트로 커버된다(재활성화 경로의 레이스는 별도
  finding 3 참고).
- **알림 트리거의 기준 달**: `SchoolCampService.cancelApplication`이
  `waitlistService.notifyForMonth(YearMonth.from(application.getSession().getCampDate()))`를
  호출해, 취소 시점의 "이번 달"이 아니라 세션의 캠핑 날짜가 속한 달로 정확히 조회한다.
  `SchoolCampServiceTest`에 `verify(waitlistService).notifyForMonth(YearMonth.from(
  application.getSession().getCampDate()))` 어서션이 추가돼 회귀도 잡는다.
- **`releaseQuietly` 경로에 알림 미호출**: `SchoolCampSessionClaimService.java`는 diff에서
  전혀 수정되지 않았고 `SchoolCampWaitlistService`를 주입받지 않는다.
  `SchoolCampService.releaseQuietly`도 `sessionClaimService.release(sessionId)`만 호출할
  뿐 `waitlistService`를 건드리지 않는다 — 다만 이를 검증하는 자동화된 회귀 테스트가
  없다는 점은 finding 1로 별도 지적했다.
- **월 중복 참여자 필터링 없음**: `register()`는 기존 참여 여부를 조회하지 않고,
  `notifyForMonth`도 `findByMonthAndCancelledAtIsNull` 결과 전원에게 예외 없이 발송한다 —
  기획서의 "확정" 사항과 일치.
- **에러 코드**: `SCHOOLCAMP_014`(409 CONFLICT)/`SCHOOLCAMP_015`(404 NOT_FOUND) 모두 기획서
  명세와 일치. `grep -n "SCHOOLCAMP_0"`로 001~015까지 순번 중복 없이 이어지는 것을
  확인했다.
- **`@PreAuthorize`**: 등록/취소/상태 조회 3개 엔드포인트 모두 `hasRole('STUDENT')`이고,
  `SchoolCampAuthorizationTest`에 401(미인증)/403(TEACHER 역할) 케이스가 6개 추가돼
  검증된다.
- **N+1 가능성**: `notifyForMonth`의 순회는 `waitlist.getStudentUser().getId()`만 접근하고,
  이는 지연 로딩된 프록시라도 식별자 접근만으로는 추가 SELECT를 유발하지 않는 Hibernate의
  최적화 대상이라 N+1이 발생하지 않는다. 대기자 수 자체를 반복문으로 순회하며 알림을
  저장하는 설계는 기획서가 "재학생 규모(300명)에서 문제 없다고 판단"해 의도적으로 수용한
  사항이라 결함으로 잡지 않았다.
- **트랜잭션 경계**: `register`/`cancel`/`notifyForMonth`는 `@Transactional`,
  `getStatus`는 `@Transactional(readOnly = true)`로 적절히 구분돼 있다. `notifyForMonth`가
  별도 빈(`SchoolCampWaitlistService`)의 메서드라 `cancelApplication`의 트랜잭션에
  `REQUIRED`(기본값)로 합류하는 것도 자기 자신을 호출하는(self-invocation) 프록시 우회
  문제 없이 정상 동작한다(트랜잭션 결합 자체의 리스크는 finding 2 참고).
- **마이그레이션 스타일**: `V14`의 `KEY idx_waitlist_month_cancelled`/`CONSTRAINT
  uq_waitlist_student_month` 네이밍, 이슈 번호를 단 Korean 헤더 주석, FK 선언 방식이
  `V11`~`V13`과 일관된다.
- **checkstyle/테스트 실행**: `./gradlew checkstyleMain checkstyleTest`가
  `maxWarnings=0`(Google 스타일) 조건에서 경고 없이 통과했고, `./gradlew test --tests
  "*SchoolCamp*"`도 전부 통과했다(Flyway `V14` 마이그레이션이 테스트 컨텍스트에서 정상
  적용됨을 함께 확인).

## 반영 시점

코드 리뷰 직후(9단계) 작성. QA(10단계) 시작 전 이 문서가 먼저 존재해야 한다는
[code-review-template.md](../../rules/code-review-template.md) 규칙을 따랐다.
