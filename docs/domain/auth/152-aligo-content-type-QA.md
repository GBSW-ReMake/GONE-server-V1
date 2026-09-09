# #152 Aligo 응답 Content-Type 오탐 수정 — QA 결과

관련 기획서: [152-aligo-content-type.md](./152-aligo-content-type.md)
관련 코드 리뷰: [152-aligo-content-type-code-review.md](./152-aligo-content-type-code-review.md)
(9단계 코드 리뷰 지적 사항 — Low 1건은 QA 이전에 이미 반영·재검증 완료, 아래 결과는
반영된 코드 기준이다)

## 검증 방법/범위
이 이슈는 `AligoSmsSender` 내부 파싱 로직만 바꾼 수정이라, 아래 두 단계로 확인했다.

1. **실제 버그 재현 및 수정 확인(staging)**: 이 이슈는 애초에 staging
   (`gone-dev.gbsw.hs.kr`)에서 `POST /api/v1/auth/phone/send-code`를 실제로 호출해
   발견됐다 — SMS는 정상 수신됐는데 API 응답은 `502`(`AUTH_009`)였고, 서버 로그에서
   `UnknownContentTypeException`(Aligo가 `Content-Type: text/html`로 JSON을 보냄)을
   직접 확인했다. 이번 수정은 이 실제 관측을 근거로 작성됐다.
2. **로컬 빌드/테스트**:
   - `./gradlew checkstyleMain checkstyleTest` — 통과(경고 0건).
   - `./gradlew test --tests com.remake.gone.sms.AligoSmsSenderTest` — 10건 전부 통과
     (기존 6건 + 신규 3건, 로그 중복 수정 후 재검증 1건 포함).
   - `./gradlew checkstyleMain checkstyleTest build -x javadoc` — 전체 616건 테스트 전부
     통과(실패 0건, 에러 0건).
3. **staging 재배포 확인은 이번 QA 범위 밖**: 이 수정은 아직 PR 병합 전이라 staging에
   반영되지 않았다. 실제 Aligo API가 `text/html` Content-Type을 계속 내려주는지, 이
   수정이 staging에서 실제로 502 오탐을 없애는지는 병합 후 재배포해 실제 회원가입
   인증번호 발송으로 재확인해야 한다(이슈 #152 완료 조건 3번째 항목).

## 발견 사항
- **Critical/High**: 없음.
- **Medium**: 없음.
- **Low**: 없음(코드 리뷰에서 나온 Low 1건은 QA 전에 반영 완료).

## 결론
Content-Type과 무관하게 JSON을 파싱하도록 고쳐 이번 버그의 근본 원인(Aligo의 잘못된
`Content-Type` 헤더에 대한 과도한 신뢰)을 없앴고, 기존 동작(에러코드, `result_code` 판정,
호출부 계약)은 전혀 바꾸지 않았다. 코드 리뷰(9단계)와 로컬 빌드/테스트가 모두 통과해 이
이슈의 완료 조건 중 로컬 검증 부분은 충족했다. CI 통과와 staging 실제 재확인은 병합 후
자연히 이뤄진다.
