# #89 휴대폰 인증문자 발송에 알리고(Aligo) SMS 연동 추가 — 기획서

관련 이슈: [#89 휴대폰 인증문자 발송에 알리고(Aligo) SMS 연동 추가](https://github.com/GBSW-ReMake/GONE-server-V1/issues/89)
선행 코드: [`SmsSender`](../../../src/main/java/com/remake/gone/auth/utils/SmsSender.java)/
[`ConsoleSmsSender`](../../../src/main/java/com/remake/gone/auth/utils/ConsoleSmsSender.java)/
[`PhoneAuthService`](../../../src/main/java/com/remake/gone/auth/service/PhoneAuthService.java)/
[`NeisClient`](../../../src/main/java/com/remake/gone/neis)(공통 외부 API 패키지 분리 전례)

## 개요/목적
휴대폰 인증번호 발송은 `SmsSender` 인터페이스로 이미 추상화돼 있고, 구현체는 `dev` 프로필용
`ConsoleSmsSender`(콘솔 로그 출력) 하나뿐이다. 실제로 문자를 발송하는 구현체가 없어 운영
환경에서 회원가입 휴대폰 인증이 사실상 동작하지 않는다. 알리고(Aligo) SMS API로 실제 발송
구현체 `AligoSmsSender`를 추가하고, `dev`가 아닌 환경에서 자동으로 그 구현체가 선택되게 한다.
엔드포인트 변경은 없다 — `PhoneAuthService`는 `SmsSender` 인터페이스에만 의존하므로 구현체
교체만으로 끝난다.

**패키지 위치를 `auth.utils`가 아니라 최상위 `sms` 패키지로 정한다(범위 결정, 보스 지시).**
`conduct`(상/벌점) 마스터 기획서([1_conduct-domain.md](../conduct/1_conduct-domain.md)
"알림 트리거" 절)에 이미 "부여 시 학생에게 즉시 알림"이 요구사항으로 기록돼 있고, 이 알림도
알리고로 문자 발송하는 방식이 될 예정이다 — 즉 `SmsSender`/`AligoSmsSender`는 `auth` 전용이
아니라 여러 도메인이 공유할 발송 모듈이다. `NeisClient`가 `meal`/`timetable` 두 도메인이
공유하는 외부 API 연동이라 `common`이 아닌 최상위 독립 패키지(`neis`)로 분리되어 있는
전례와 동일한 이유로, `SmsSender`/`ConsoleSmsSender`/`AligoSmsSender`/`AligoProperties`/
`AligoConfig`를 `sms` 패키지로 옮긴다. **서브패키지 구조도 `neis`와 동일하게 맞춘다** —
`neis` 패키지는 `NeisClient`만 최상위에 두고 `NeisConfig`/`NeisProperties`는
`neis.config`에 둔다(둘 다 최상위에 두지 않는다). 그래서 `SmsSender`/`ConsoleSmsSender`/
`AligoSmsSender`는 `sms` 최상위, `AligoProperties`/`AligoConfig`는 `sms.config`로
나눈다(기존 `SmsSender`/`ConsoleSmsSender`는 `auth.utils`에서 `sms`로 이동). **다만
`conduct` 도메인이 실제로 이 모듈을 호출하는 연동 코드 자체는 이
이슈 범위에 넣지 않는다** — `conduct` 마스터 기획서의 알림 트리거는 아직 `conduct` 도메인
구현 자체가 시작되지 않아 선행 의존이 없고(YAGNI), 이번 이슈는 어디에 위치시킬지(패키지
구조)만 미리 맞춰 나중에 다시 옮기는 일이 없게 하는 데까지만 다룬다.

## 사전 조사에서 발견한 문제 — 이 이슈에 반드시 포함해야 하는 CI/CD 수정
`.github/workflows/ci.yml`의 `deploy-staging` 잡이 EC2에 써주는 `.env`에는
`SPRING_PROFILES_ACTIVE`가 **아예 없다**. `application.yml`의 기본값(`spring.profiles.active:
dev`)을 덮어쓰는 곳이 이 파이프라인 어디에도 없어서, **지금 staging 서버는 실제로 `dev`
프로필로 떠 있다** — `ConsoleSmsSender`(`@Profile("dev")`)가 staging에서도 이미 활성화된
상태라는 뜻이다(#89의 배경 설명이 정확히 이 증상이다). 그래서 `AligoSmsSender`를
`@Profile("!dev")`로 아무리 잘 만들어도, `deploy-staging` 잡의 `.env` 생성 단계에
`SPRING_PROFILES_ACTIVE=staging`을 추가하지 않으면 이 기능은 머지돼도 죽은 코드로 남는다.
그래서 이 CI/CD 수정을 이번 이슈의 작업 범위에 포함한다(코드 구현과 별개로 빠뜨리면 기능
자체가 무의미해지므로 "선택"이 아니라 "필수"로 판단했다).

## 기존 로직 수정 — 변경 전/후 동작 차이
- **변경 전**: 프로필과 무관하게 `ConsoleSmsSender`만 존재해, `dev`가 아닌 환경에서도
  인증번호가 콘솔/컨테이너 로그로만 출력되고 실제 문자는 가지 않는다.
- **변경 후**: `dev` 프로필은 그대로 `ConsoleSmsSender`(변경 없음). 그 외 모든 프로필
  (`staging`, 그리고 아직 파이프라인이 없는 향후 `prod` 포함)은 새로 추가하는
  `AligoSmsSender`가 알리고 API로 실제 문자를 발송한다. `PhoneAuthService`는 코드 변경이
  없다 — `SmsSender` 구현체가 어느 쪽이든 동일한 인터페이스로 호출한다.

### `AligoSmsSender` 구현
**Jackson 3 주의**: 이 프로젝트는 Spring Boot 4.1.0이라 `JsonNode`는
`tools.jackson.databind.JsonNode`다(`com.fasterxml.jackson...`이 아니다) —
`NeisClient`(`neis/NeisClient.java`)가 이미 이 네임스페이스를 쓰고 있으니 구현 시 그대로
따른다.
```java
package com.remake.gone.sms;

import com.remake.gone.auth.exception.AuthErrorCode;
import com.remake.gone.common.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

@Component
@Profile("!dev")
@RequiredArgsConstructor
@Slf4j
public class AligoSmsSender implements SmsSender {

  private final RestClient aligoRestClient;
  private final AligoProperties aligoProperties;

  @Override
  public void send(String phoneNumber, String message) {
    JsonNode body;
    try {
      body = aligoRestClient.post()
          .uri("/send/")
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(formBody(phoneNumber, message))
          .retrieve()
          .body(JsonNode.class);
    } catch (RestClientException e) {
      log.error("Aligo SMS API 호출 실패: phoneNumber={}", phoneNumber, e);
      throw new CustomException(AuthErrorCode.SMS_SEND_FAILED);
    }

    int resultCode = body.path("result_code").asInt();
    if (resultCode < 0) {
      log.error("Aligo SMS 발송 실패: result_code={}, message={}",
          resultCode, body.path("message").asText(""));
      throw new CustomException(AuthErrorCode.SMS_SEND_FAILED);
    }
  }

  private MultiValueMap<String, String> formBody(String phoneNumber, String message) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("key", aligoProperties.key());
    form.add("user_id", aligoProperties.userId());
    form.add("sender", aligoProperties.sender());
    form.add("receiver", phoneNumber);
    form.add("msg", message);
    return form;
  }
}
```
`ConsoleSmsSender`가 `@Profile("dev")`이므로 이 클래스는 정확히 그 여집합인 `@Profile("!dev")`
로 지정한다 — 두 구현체가 프로필 전체를 상호 배타적으로 정확히 나눠 갖는다(어느 프로필에서든
정확히 하나만 뜬다). "유예시간 지남" 판단과 달리 여기는 별도 임계값이 없어 트레이드오프가
없는 단순한 이분법이다.

`NeisClient`(`docs/domain/neis` 참고)와 동일하게 실패를 두 갈래로 나눈다 — 네트워크/HTTP
자체가 실패(`RestClientException`)하거나, 응답은 왔지만 알리고가 발송 실패를 알린 경우
(`result_code < 0`, 알리고 공식 문서 기준 음수면 실패) 모두 `AuthErrorCode.SMS_SEND_FAILED`
로 통일한다 — 호출부(`PhoneAuthService.sendVerificationCode`)가 이미 `RuntimeException`을
잡아 쿨다운을 되돌리는 공통 처리를 하고 있으므로, 실패 종류를 더 세분화해도 그 쪽 로직이
갈라지지 않는다(불필요한 세분화를 피한다).

### `AligoProperties`/`AligoConfig`
`NeisProperties`/`NeisConfig`와 동일한 패턴으로 추가한다. `sms.config` 서브패키지
소속이다(위 "개요/목적"의 패키지 위치 결정 참고) — `neis.config`가
`NeisProperties`/`NeisConfig`를 소유하는 것과 동일한 구조(`AligoSmsSender` 자체는
`sms` 최상위에 두는 것과 구분).
```java
package com.remake.gone.sms.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.client.RestClient;

@ConfigurationProperties(prefix = "aligo")
@Validated
public record AligoProperties(
    @NotBlank String key,
    @NotBlank String userId,
    @NotBlank String sender
) {}

@Configuration
public class AligoConfig {
  @Bean
  public RestClient aligoRestClient() {
    return RestClient.builder()
        .baseUrl("https://apis.aligo.in")
        .build();
  }
}
```
`@ConfigurationPropertiesScan`(애플리케이션 전역 설정)이 프로필과 무관하게 이 레코드를 항상
등록하므로(기존 `NeisProperties`/`R2Properties`/`JwtProperties`와 동일), `dev`에서 로컬
기동/CI 테스트를 돌릴 때도 유효성 검증(`@NotBlank`)을 통과할 더미 값이 필요하다 — 아래
"영향 받는 기존 코드" 절 참고.

### `SmsSender` 인터페이스/`PhoneAuthService`
`SmsSender` 인터페이스 자체는 내용 변경이 없다 — `auth.utils`에서 `sms`로 패키지만
옮긴다(위 "개요/목적" 참고). `PhoneAuthService`는 `import` 경로만
`com.remake.gone.sms.SmsSender`로 바뀔 뿐, 로직 변경은 없다. `PhoneAuthService.
sendVerificationCode`가 이미 `catch (RuntimeException e)`로 발송 실패 시 쿨다운을
되돌리는 공통 처리를 하고 있어(요청 범위의 "재시도/쿨다운 정책과의 연동" 항목이 이미
만족돼 있다), `AligoSmsSender`가 `RuntimeException`(`CustomException`)만 정확히 던지면
별도 연동 코드가 필요 없다.

`smsSender.send(...)` 호출부 바로 위에 있는 기존 인라인 주석 `// local 전용 로그에
인증번호 뿌리기`는 이 변경으로 더 이상 사실이 아니게 된다 — `dev`에서만 맞는 설명이고
`staging`/`prod`에서는 실제로 문자가 발송되기 때문이다. 이 주석은 삭제하거나 "구현체에
따라 콘솔 로그 또는 실제 SMS 발송"처럼 프로필 무관하게 맞는 설명으로 고친다(오해를
유발하는 주석을 남기지 않는다 — sentence-refinement.md 원칙 6).

## 환경변수/시크릿
이슈 요구사항대로 `staging`/`prod` 구분 없이 알리고 키 하나만 쓴다(별도 계정 발급 안 함).
- `ALIGO_KEY` → `aligo.key`
- `ALIGO_USER_ID` → `aligo.user-id`
- `ALIGO_SENDER` → `aligo.sender`(발신번호, 알리고에 사전 등록된 번호만 사용 가능)

`NEIS_*`와 동일한 방식으로 GitHub Environment "STAGING"에 시크릿 3개를 등록하고,
`deploy-staging` 잡의 `env:`/`.env` 생성 단계에 추가한다. `PRODUCTION` 배포 파이프라인은
아직 존재하지 않으므로(코드 리뷰/QA 시점에 `deploy-staging` 잡의 주석이 이미 "나중에 운영
배포 잡이 생기면"이라고 명시) 이번 범위에서 새로 만들지 않는다 — 같은 시크릿 값을 나중에
`PRODUCTION` 환경에도 그대로 등록하면 되므로 지금 선반영할 이유가 없다.

## 데이터 모델 변경
없음.

## 영향 받는 기존 코드/테스트
- 신규: `sms.AligoSmsSender`, `sms.config.AligoProperties`, `sms.config.AligoConfig`,
  `AuthErrorCode.SMS_SEND_FAILED`(`AUTH_009`, `BAD_GATEWAY`, "문자 발송에 실패했습니다.
  잠시 후 다시 시도해주세요." — `NeisErrorCode.EXTERNAL_API_ERROR`와 동일한 성격의 외부 API
  실패 코드. 패키지가 `sms`로 옮겨도 인증 도메인 관점의 에러 코드라 `AuthErrorCode` 소속은
  그대로 유지한다 — 벌점 발송처럼 다른 도메인이 나중에 이 모듈을 쓰게 되면, 그 도메인은
  자기 자신의 에러 코드로 `CustomException`을 다시 감싸거나 별도 코드를 정의하면 된다)
- 이동(패키지만 변경, 내용 변경 없음): `SmsSender`, `ConsoleSmsSender`를
  `auth.utils` → `sms`로 이동
- 수정: `PhoneAuthService`, `PhoneAuthServiceTest` — `SmsSender` import 경로만
  `com.remake.gone.sms.SmsSender`로 변경(로직 변경 없음)
- 수정: `.github/workflows/ci.yml`
  - `build-and-test` 잡의 `env:`에 `ALIGO_KEY`/`ALIGO_USER_ID`/`ALIGO_SENDER` 더미 값
    추가(`NEIS_*` 더미 값과 동일한 이유 — `contextLoads()`가 `AligoProperties` 검증을
    통과해야 하지만 실제 알리고 API를 호출하지는 않는다)
  - `deploy-staging` 잡, 총 세 지점을 **모두** 고쳐야 한다(하나라도 빠지면 다른 지점을
    고쳐도 소용없다):
    1. 잡 상단 `env:` 블록에 `ALIGO_KEY: ${{ secrets.ALIGO_KEY }}` 등 3개 추가
       (`NEIS_*`와 동일한 패턴)
    2. **`.env 생성 + 컨테이너 갱신` 스텝의 `envs:` 한 줄짜리 콤마 목록**(현재
       `MYSQL_ROOT_PASSWORD,JWT_ACCESS_TOKEN_SECRET,...,NEIS_SD_SCHUL_CODE`)에도
       `ALIGO_KEY,ALIGO_USER_ID,ALIGO_SENDER`를 반드시 추가한다 — `appleboy/ssh-action`은
       이 목록에 있는 이름만 SSH 세션으로 실제 전달한다. 1번(`env:`)에만 추가하고 이
       `envs:` 목록에 빠뜨리면, 원격 스크립트 안 `$ALIGO_KEY`가 빈 문자열이 되어
       `.env`에 빈 값이 써지고 `AligoProperties`의 `@NotBlank` 검증에 걸려 컨테이너가
       기동조차 안 된다(가장 놓치기 쉬운 지점 — `NEIS_*`를 처음 추가했을 때도 이 세 지점을
       함께 고쳐야 했을 것이므로, 그때 이미 반영된 목록 형식을 그대로 따라 이어 붙인다).
    3. `.env` 생성 heredoc 본문에 `ALIGO_KEY=$ALIGO_KEY` 등 3줄 추가, **및
       `SPRING_PROFILES_ACTIVE=staging` 신규 추가**(위 "사전 조사에서 발견한 문제" 절
       참고 — 이게 없으면 이번 이슈 전체가 무의미해진다)
- 변경 없음(내용 기준): `SmsSender`/`ConsoleSmsSender`의 코드 내용, `PhoneAuthService`의
  로직, 요청/응답 스키마, 데이터베이스 스키마, `application-dev.yml`

## 리스크 및 고려사항
- **API 설계 6원칙**: 신규/변경 엔드포인트가 없어 해당 없음.
- **`SPRING_PROFILES_ACTIVE` 신규 도입의 부수 효과**: 지금까지 "이름 없는 기본 프로필
  (사실상 dev)"로 떠 있던 staging이 이번 변경으로 명시적으로 `staging` 프로필을 갖게 된다.
  코드베이스를 확인한 결과 `@Profile("dev")`가 붙은 컴포넌트는 `ConsoleSmsSender`
  하나뿐이라, 이 전환으로 새로 꺼지거나 켜지는 다른 컴포넌트는 없다 — 이번 변경의 영향
  범위는 SMS 발송 하나로 한정된다.
- **알리고 발신번호 사전 등록**: `sender` 값은 알리고 관리자 페이지에 미리 등록된 번호여야
  발송이 성공한다(등록 안 된 번호로 보내면 `result_code`가 음수로 실패) — 실제 키 발급/등록
  주체(보스)가 사전에 완료해야 하는 전제 조건이다. 로컬 `dev`에서는 여전히
  `ConsoleSmsSender`를 쓰므로 이 이슈의 구현/단위 테스트 자체는 실제 키 없이 진행 가능하고,
  QA(실제 발송 확인)에만 필요하다.
- **비용**: 알리고는 건당 과금이다. QA 단계에서 실제 발송 검증 시 최소 횟수로 제한한다
  (알리고가 제공하는 테스트 모드(`testmode_yn`)가 있으면 우선 그걸로 API 연동 자체를
  검증하고, 과금 발송은 최종 확인 1~2건으로 줄인다).

## 테스트
- `sms.AligoSmsSenderTest`(신규, `MockRestServiceServer` 기반 — `NeisClientTest`와 동일한
  패턴):
  - 정상 응답(`result_code >= 0`)이면 예외 없이 종료
  - 실패 응답(`result_code < 0`)이면 `CustomException(AuthErrorCode.SMS_SEND_FAILED)`
  - 네트워크/서버 오류(5xx)면 동일하게 `CustomException(AuthErrorCode.SMS_SEND_FAILED)`
  - 요청 폼 바디에 `key`/`user_id`/`sender`/`receiver`/`msg`가 정확히 실리는지
- 기존 `PhoneAuthServiceTest`: `SmsSender` import 경로만 `com.remake.gone.sms.SmsSender`로
  바뀐다 — 테스트 로직 자체는 변경 없음(구현체 교체와 무관하게 `SmsSender`를 목으로 이미
  검증하고 있음 — 실제 파일 확인은 8단계 구현 시작 시점에 재확인).

## 완료 조건 (Definition of Done)
- 로컬 빌드/테스트 통과
- CI 통과(`ALIGO_*` 더미 값으로 `contextLoads()` 포함)
- Postman/Notion 반영: 해당 없음(요청/응답 스키마 변경 없음)
- `deploy-staging` CI/CD 수정(`SPRING_PROFILES_ACTIVE=staging` 포함) 반영 확인
