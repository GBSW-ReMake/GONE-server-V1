# #51 dev 서버(EC2) 배포 자동화(CI/CD) 구축 — 코드 리뷰 결과

관련 기획서: [51-chore-dev-cicd.md](./51-chore-dev-cicd.md)
형식 규칙: [code-review-template.md](../../rules/code-review-template.md)

## 리뷰 범위/방법

- 대상: `git diff dev..chore/#51-dev-cicd` 전체. 애플리케이션 Java 코드 변경은 없고
  `Dockerfile`(신규), `deploy/docker-compose.dev.yml`(신규), `.github/workflows/ci.yml`
  (`build-and-test`에 아티팩트 업로드 스텝 추가, `build-and-push-image`/`deploy-dev`
  job 신규)만 대상이다. 문서 커밋(`77051bc` 기획서 추가, `d3fe4cd` 기획서 정정)은
  기획서 자체를 기준선으로 삼기 위해 참고만 하고 리뷰 대상 코드로는 다루지 않았다.
- 기획서(`51-chore-dev-cicd.md`) 대비 범위 초과 변경은 없음을 확인했다 — 신규
  엔드포인트 없음, 기존 `checkstyle`/`build-and-test` job 동작 자체는 그대로.
- `appleboy/scp-action`의 `strip_components: 1` + `source: "deploy/docker-compose.dev.yml"`
  조합이 실제로 `/opt/gone/dev/docker-compose.dev.yml`로 귀결되는지(파일명 유지 여부),
  그리고 `actions/upload-artifact`(`path: build/libs/*.jar`)가 남긴 산출물이
  `actions/download-artifact`(`path: build/libs`) 이후 `Dockerfile`의
  `COPY build/libs/*.jar app.jar`가 기대하는 경로와 맞는지를 각 액션의 공식 문서로
  직접 확인했다 — 둘 다 기획서가 의도한 대로 동작한다(별도 항목으로 기록하지 않음).
- GitHub Actions의 `needs` + 커스텀 `if` 조합, 스킵된 job의 상태 전파 규칙을 공식
  문서로 확인해 `deploy-dev`가 `build-and-push-image`의 `if` 조건에 무임승차해
  PR/‌`main` push에서 실행되지 않는지도 검증했다 — 이 경로는 안전하다(아래 2번 항목은
  반대로 `success()` 누락이 실제 문제가 되는 다른 지점을 다룬다).
- 시크릿 취급 원칙(기획서 최우선 절)을 기준으로 `ci.yml`의 `env:`/`envs:` 전달 경로,
  `set -x` 등 디버그 출력 여부, heredoc의 quoting(치환 필요 여부와 인젝션 가능성)을
  줄 단위로 검토했다.
- Critical 없음 — 저장소/문서/커밋에 시크릿 값이 평문으로 남는 경로, 워크플로우 로그에
  시크릿이 그대로 찍히는 경로(예: `set -x`, `echo $SECRET`)는 없음을 확인했다. 아래
  발견 사항은 모두 "설계된 대로는 동작하지만 잠재 위험/모범 사례 이탈"에 해당한다.

---

## 1. 🟠 High — `deploy/docker-compose.dev.yml`이 mysql/redis를 호스트 전체에 불필요하게 노출하고, redis는 인증이 전혀 없음

**문제**: `deploy/docker-compose.dev.yml:5,11`(mysql)과 `:19`(redis)에서 각각
`"3306:3306"`, `"6379:6379"`로 포트를 매핑한다. 이 매핑은 호스트의 모든 인터페이스
(`0.0.0.0`)에 바인딩되며, `app` 컨테이너는 이미 같은 compose 네트워크 안에서 서비스
이름으로 접근하므로(`.env`의 `SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/...`,
`SPRING_DATA_REDIS_HOST=redis` — `ci.yml:385,388` 참고) 이 호스트 포트 노출은 애플리케이션
동작에 전혀 필요하지 않다. 더 심각한 건 redis 서비스에 인증이 전혀 설정되어 있지
않다는 점이다 — `docker-compose.dev.yml` 어디에도 `requirepass`/`REDIS_PASSWORD`가
없고, `ci.yml`이 생성하는 `.env`(371-401)에도 `SPRING_DATA_REDIS_PASSWORD` 같은 항목이
없다(애초에 `application.yml`/`application-dev.yml`도 redis 비밀번호를 쓰지 않아 이
자체는 기존 컨벤션이지만, 이번 PR이 그 redis를 처음으로 호스트 포트에 노출시킨다).

재현 시나리오: EC2 보안그룹에 3306 또는 6379 인바운드가(디버깅 목적 등으로) 한 번이라도
열리면, 외부에서 `redis-cli -h <EC2 IP> ping`이 인증 없이 즉시 성공해 세션/토큰 관련
데이터를 자유롭게 읽고 쓸 수 있고, mysql은 root 비밀번호 하나로 무차별 대입 공격
표면이 생긴다. 이 diff 자체만으로 확정적으로 뚫리는 것은 아니지만(보안그룹 설정은
기획서상 보스가 EC2에서 별도로 관리), compose 파일이 기능적으로 불필요한 노출을
스스로 만들어 그 위험을 도입한다.

**해결 방안**:
1. mysql/redis 서비스에서 `ports:` 자체를 제거한다 — `app`이 이미 내부 도커 네트워크로
   접근하므로 기능 손실이 없고, 외부 노출을 원천 차단하는 가장 안전한 방법이다. 단점은
   로컬 디버깅 시 EC2에 SSH 터널(`ssh -L 3306:localhost:3306 deploy@ec2`)을 뚫어야
   접속 가능해져 운영 편의성이 약간 떨어진다.
2. `ports:`를 유지하되 `"127.0.0.1:3306:3306"`, `"127.0.0.1:6379:6379"`처럼 루프백에만
   바인딩한다 — EC2에 SSH로 들어간 사람만 접근 가능해지고 보안그룹 설정 실수의 영향을
   받지 않는다. 단, EC2 로컬의 다른 프로세스/컨테이너가 손상되면 여전히 접근 가능해
   옵션 1보다는 신뢰 경계가 조금 넓다.

   두 옵션 중 무엇을 택하든 redis 무인증은 별개 문제이므로, `command: redis-server
   --requirepass ${REDIS_PASSWORD}` 추가(+ 앱 쪽 `SPRING_DATA_REDIS_PASSWORD` 설정)를
   병행하는 것을 권장한다 — 이번 PR 범위를 넘어선다면 별도 이슈로 분리해도 된다.

---

## 2. 🟡 Medium — `build-and-push-image`의 `if`에 `success()`가 없어, 선행 job 실패 시 "건너뜀"이 아니라 혼란스러운 "실패"로 표시됨

**문제**: `ci.yml:306` `if: github.event_name == 'push' && github.ref ==
'refs/heads/dev'`. GitHub Actions는 job에 커스텀 `if:`를 직접 지정하면, `needs`로
지정한 선행 job들이 성공했는지(`success()`)를 자동으로 함께 검사하지 않는다(공식
문서: "선행 job이 실패하거나 스킵되면 그 job이 필요로 하는 job들도 스킵되지만,
계속 진행시키는 조건식을 쓰면 예외"). 즉 이 job처럼 커스텀 `if`를 쓰면 기본
cascading-skip이 사라진다.

재현 시나리오: `dev` 브랜치에 push했는데 `checkstyle` 또는 `build-and-test`에서
실패하면(테스트 깨짐 등), 두 job 모두 실패/스킵되어 `build/libs`에 jar 아티팩트가
업로드되지 않는다(`build-and-test`의 "빌드 아티팩트(jar) 업로드" 스텝은
`ci.yml:174-180`, 자체 `if` 없이 기본 `success()`로만 동작해 직전 스텝이 실패하면
스킵된다). 그런데도 `build-and-push-image`는 `if` 조건만으로 실행을 시작하고,
"빌드 아티팩트(jar) 다운로드"(`ci.yml:316-320`) 스텝에서 "app-jar 아티팩트를 찾을 수
없음" 오류로 실패한다. 실제로 깨진 이미지가 배포되지는 않는다 — `deploy-dev`는
자체 `if`가 없어 기본 `success()` 규칙이 그대로 적용되므로, `build-and-push-image`가
(스킵이 아니라) 진짜로 실패하면 정상적으로 건너뛴다. 다만 CI 실행 결과가
"build-and-test 실패로 건너뜀"이 아니라 "build-and-push-image가 아티팩트를 못 찾아
실패"로 표시돼, 실패 원인을 진단할 때 한 단계 더 거슬러 올라가야 하는 혼란을 준다.

**해결 방안**:
1. `if: success() && github.event_name == 'push' && github.ref ==
   'refs/heads/dev'`로 `success()`를 명시적으로 추가한다 — 표준 권장 패턴이고
   한 줄만 바뀌어 비용이 가장 낮다.
2. 그대로 두고 감수한다 — 아티팩트 다운로드 실패로 어차피 안전하게 막히므로 배포
   안전성에는 영향이 없다. 다만 실패 로그가 매번 "아티팩트 없음"으로만 나와, 담당자가
   매번 진짜 원인(테스트/체크스타일 실패)까지 추적하는 데 드는 비용이 계속 남는다.

---

## 3. 🟡 Medium — `build-and-push-image`에 동시성 제어가 없어, 연속 push 시 최신 커밋의 이미지가 구버전에 덮어써질 수 있음

**문제**: `ci.yml:302-334`(`build-and-push-image` job)에는 `concurrency` 블록이 없다.
반면 `deploy-dev`는 `ci.yml:341-343`에서 `group: deploy-dev, cancel-in-progress:
false`로 실행 순서를 보장한다. 이미지 태그는 기획서대로 `ghcr.io/gbsw-remake/
gone-server-v1:dev` 고정 태그 하나뿐이다(`ci.yml:334`).

재현 시나리오: `dev` 브랜치에 짧은 간격으로 두 번 push하면(예: 오탈자 수정 커밋을
바로 이어서 push), 두 `build-and-push-image` 실행이 동시에 진행될 수 있다. 빌드
소요 시간 차이로 먼저 push한 실행이 나중에 완료되면, 나중 push(최신 커밋)의 이미지가
먼저 push(구 커밋)의 이미지로 덮어써진다 — "최신 커밋 = 최신 이미지"가 보장되지
않는다. `deploy-dev`의 `concurrency`는 "deploy-dev 실행 순서"만 보장할 뿐, 그 시점에
GHCR에 실제로 어떤 이미지가 올라가 있는지는 보장하지 못한다. 결과적으로 최신 push의
`deploy-dev`가 실행돼도 GHCR엔 구 이미지가 남아 있어 구버전이 배포될 수 있고, 헬스체크는
통과하므로(2번 항목과 별개로) 아무 에러 없이 "✅ dev 배포 성공" 알림이 간다.

**해결 방안**:
1. `build-and-push-image`에도 `concurrency: { group: build-and-push-image,
   cancel-in-progress: true }`를 추가해, 뒤이은 push가 들어오면 진행 중이던 이전
   빌드를 취소하고 항상 최신 커밋만 이미지로 남게 한다 — 구현 비용이 낮고 기획서의
   "고정 태그 하나만 쓴다"는 설계를 그대로 유지한다. 단점은 취소된 실행이 CI 로그에
   "cancelled"로 표시돼 다소 어수선해 보일 수 있다는 점 정도다.
2. 이미지 태그에 커밋 SHA를 추가로 붙이고(`:dev-<sha>`), `build-and-push-image`의
   출력값을 `deploy-dev`가 받아 그 SHA 태그를 명시적으로 pull하도록 바꾼다 —
   "배포된 이미지 = 트리거한 커밋"이 항상 보장되고 롤백도 쉬워진다. 다만 기획서가
   명시한 "고정 태그 하나만 쓴다"는 태그 전략을 벗어나므로 기획 변경이 필요하고,
   `deploy-dev` 스크립트도 SHA를 넘겨받는 구조로 다시 손봐야 해 옵션 1보다 구현
   범위가 크다.

---

## 4. 🟡 Medium — 헬스체크가 HTTP 상태 코드를 확인하지 않아, 앱이 500을 반환해도 "배포 성공"으로 알림이 감

**문제**: `ci.yml:414` `curl -s -o /dev/null http://localhost:9091`. curl은
`--fail`(`-f`) 옵션이 없으면 서버가 TCP 연결에 응답하고 어떤 HTTP 상태 코드든(404,
500 등) 반환하기만 하면 종료 코드 0을 반환한다.

재현 시나리오: 새 이미지의 `app` 컨테이너가 기동은 됐지만(포트는 열림) `.env`의 DB
접속 정보가 잘못됐거나 Flyway 마이그레이션이 실패해 모든 요청에 500을 반환하는
상태라도, 이 헬스체크는 여전히 "healthy"로 판정하고 30회 재시도 루프를 통과한다
(`ci.yml:412-421`). 그 결과 "✅ dev 배포 성공" Discord 알림(`ci.yml:434-451`)이
발송된다 — 실제로는 배포가 사실상 실패한 상태인데도 성공으로 오인된다. 기획서도
"TCP/포트 응답 확인" 수준으로 하겠다고 명시했지만(`51-chore-dev-cicd.md` 219줄,
actuator 부재가 근거), 이미 curl로 HTTP 요청을 보내는 이상 상태 코드까지 확인하는
데 드는 추가 비용은 사실상 없다.

**해결 방안**:
1. `curl -sf -o /dev/null http://localhost:9091`처럼 `-f`(`--fail`) 옵션을 추가한다
   — HTTP 상태 코드가 2xx/3xx가 아니면 curl 자체가 실패로 처리돼 재시도 루프가 계속
   돈다. 구현 비용이 거의 0이다(플래그 한 글자). 다만 루트 경로(`/`)가 시큐리티
   설정상 401/403을 반환하도록 되어 있다면 오탐(false negative)이 날 수 있어, 실제
   응답 코드를 배포 전 1회 확인해야 한다.
2. 기획서가 언급한 "actuator 부재"를 이번에 해소한다 — `spring-boot-starter-actuator`를
   추가하고 `/actuator/health`로 확인하도록 바꾼다 — DB/Redis 커넥션 상태까지 반영하는
   가장 정확한 헬스 신호를 얻을 수 있지만, 의존성 추가와 시큐리티 설정 변경(이 경로만
   인증 없이 열어야 함)이 필요해 이번 #51 범위를 넘어서는 별도 작업이 된다.

---

## 5. 🟢 Low — `deploy-dev` job에 명시적 `permissions`가 없음

**문제**: `ci.yml:336-339`(`deploy-dev` job 헤더)에는 `permissions:` 블록이 없다.
바로 위 `build-and-push-image`(`ci.yml:308-310`)는 `contents: read, packages:
write`로 최소 권한을 명시했지만, `deploy-dev`는 저장소/조직의 기본 `GITHUB_TOKEN`
권한을 그대로 물려받는다. `deploy-dev`가 실제로 쓰는 인증 수단은 SSH 키/SCP용
GitHub Secrets뿐이고, `GITHUB_TOKEN`은 `actions/checkout@v4`(`ci.yml:358-359`) 하나에만
암묵적으로 쓰인다 — 즉 이 job이 실제로 필요로 하는 권한은 `contents: read`뿐이다.

**해결 방안**:
1. `deploy-dev`에도 `permissions: contents: read`를 명시적으로 추가한다 — 다른
   job과 일관되고, 저장소 기본값이 나중에 더 넓게 바뀌어도 이 job의 권한은 항상
   최소로 고정된다. 세 줄 추가로 끝난다.
2. 워크플로우 최상단에 `permissions: contents: read`를 한 번만 선언해 모든 job에
   공통 기본값을 적용하고, `packages: write`가 필요한 `build-and-push-image`에서만
   job 단위로 덮어쓴다 — 매 job마다 반복 선언할 필요가 없고 일관성이 강제된다. 다만
   기존 `checkstyle`/`build-and-test` job까지 함께 건드리게 되어, "신규 job만
   추가한다"는 이번 #51의 변경 범위를 넘어설 수 있다.

---

## 6. 🟢 Low — mysql 컨테이너가 `env_file`로 무관한 앱 시크릿(JWT/R2/NEIS)까지 함께 주입받음

**문제**: `deploy/docker-compose.dev.yml:5` mysql 서비스가 `env_file: .env`를 쓴다.
`ci.yml`이 생성하는 `.env`(371-401)에는 `MYSQL_ROOT_PASSWORD`뿐 아니라 `JWT_SECRET`,
`R2_*`, `NEIS_*` 등 mysql과 무관한 모든 애플리케이션 시크릿이 함께 들어있다.
`env_file`은 파일 전체를 그 서비스의 프로세스 환경변수로 주입하므로, mysql 컨테이너
안에서도 `printenv`로 JWT_SECRET/R2 키/NEIS 키를 그대로 읽을 수 있다. mysql 이미지
자체는 이 값들을 쓰지 않지만, mysql 컨테이너가 침해되는 경우(예: MySQL 자체 취약점)
공격자가 얻는 정보 범위가 앱 시크릿 전체로 넓어진다 — 기획서의 "시크릿 취급 원칙"이
강조하는 "필요한 곳에만 값이 존재해야 한다"는 취지와 어긋난다.

**해결 방안**:
1. mysql 서비스는 `env_file` 대신 `environment: MYSQL_ROOT_PASSWORD:
   ${MYSQL_ROOT_PASSWORD}`처럼 필요한 변수만 명시적으로 지정한다 — 노출 범위를
   정확히 필요한 만큼으로 줄인다. 다만 compose가 셸 환경변수를 보간하려면
   `docker compose` 실행 시점에 해당 변수가 셸에 있어야 하므로, ssh-action
   script에서 `export MYSQL_ROOT_PASSWORD` 후 compose를 실행하는 등 스크립트가
   조금 더 복잡해진다.
2. `.env`를 `mysql.env`/`app.env` 두 파일로 분리 생성하고, 각 서비스가 자신에게
   필요한 파일만 `env_file`로 참조한다 — 구조가 명확하고 이후 서비스가 늘어나도
   확장하기 쉽다. 다만 ssh-action 스크립트에서 heredoc을 두 번 작성해야 해 스크립트
   길이가 늘고, `docker-compose.dev.yml`도 함께 수정해야 한다.

---

## 7. 🟢 Low — Dockerfile이 root로 실행되고, 저장소에 `.dockerignore`가 없음

**문제**: `Dockerfile:1-5`에 `USER` 지시자가 없어 컨테이너 프로세스가 기본값인
root로 실행된다. 또한 저장소 루트에 `.dockerignore`가 없어(`git ls-tree` 확인
결과 없음) `docker/build-push-action`의 `context: .`(`ci.yml:332`)가 저장소 루트
전체(`.git`, `.gradle`, gradle wrapper 캐시 등)를 Docker 데몬으로 전송한다. 지금
Dockerfile은 `COPY build/libs/*.jar app.jar` 한 줄뿐이라 당장 원치 않는 파일이
이미지 레이어에 들어가지는 않지만(COPY 대상이 명시적), 빌드 컨텍스트 전송 자체가
느려지고(특히 `.git` 히스토리가 큰 경우), 이후 누군가 Dockerfile에 `COPY . .`류를
추가하면 바로 시크릿/설정 파일 유출로 이어질 수 있는 잠재 위험을 남긴다.

**해결 방안**:
1. Dockerfile에 `RUN useradd -r appuser` + `USER appuser`를 추가하고, 저장소 루트에
   `.dockerignore`(`.git`, `.gradle`, `build/reports`, `*.md` 등 제외)를 추가한다 —
   둘 다 표준 Docker 모범 사례이고 이번 PR에서 바로 적용 가능한 저비용 개선이다.
   다만 non-root 전환 시, 지금은 stdout 로깅만 쓰는 것으로 보여 영향이 적지만
   컨테이너 내부 파일 쓰기가 필요한 동작이 추가되면 권한 조정이 별도로 필요할 수
   있다.
2. 이번 PR 범위에서는 넘기고 그대로 둔다 — 기획서의 "리스크 및 고려사항"이 이미
   "`deploy` 계정의 docker 그룹 권한 자체가 사실상 루트와 동급"이라는 더 큰
   트레이드오프를 감수하기로 했으므로, 컨테이너 내부 root 실행 하나가 추가하는
   한계 위험은 상대적으로 작다고 보고 후속 이슈로 미룬다. 단점은 개선이 계속
   미뤄질 수 있다는 점이다.

---

## 요약

Critical 없음(위 "리뷰 범위/방법" 참고 — 시크릿 평문 노출/로그 유출 경로는 확인되지
않음). High 1건(mysql/redis 호스트 포트 노출 + redis 무인증). Medium 3건
(`build-and-push-image` if의 success() 누락, 동시성 제어 부재로 인한 이미지 태그
레이스, 헬스체크가 HTTP 상태 코드를 확인하지 않음). Low 3건(deploy-dev permissions
미명시, mysql의 불필요한 시크릿 공유, Dockerfile 모범 사례 갭).

## 반영 결과 (9단계 자체 점검 직후 즉시 반영)
- 1. 🟠 High — **반영**: 해결 방안 1(포트 완전 제거) 채택. `deploy/docker-compose.dev.yml`
  에서 mysql/redis `ports:` 삭제. redis 인증(`requirepass`) 추가는 채택하지 않음 —
  호스트 노출 자체가 사라져 이 diff 범위의 실제 위험(재현 시나리오)이 해소됐고, redis
  비밀번호 도입은 `application.yml` 전반의 기존 컨벤션(redis 비밀번호 미사용)을 함께
  바꿔야 해 범위가 커진다고 판단해 후속 검토로 미룸(아래 "아직 결정 안 된 것"에 추가).
- 2. 🟡 Medium — **반영**: 해결 방안 1(`success()` 추가) 채택.
- 3. 🟡 Medium — **반영**: 해결 방안 1(`concurrency` + `cancel-in-progress: true`) 채택.
- 4. 🟡 Medium — **반영**: 해결 방안 1의 취지(상태 코드 확인)를 채택하되 `--fail` 대신
  "500 미만이면 정상"으로 판정하도록 조정 — 이 프로젝트는 actuator가 없어 루트 경로가
  정상 상태에서도 404를 반환할 수 있어 `--fail`을 그대로 쓰면 오탐이 난다는 점을
  구현 시 추가로 확인해 반영.
- 5. 🟢 Low — **반영**: 해결 방안 1(`deploy-dev`에 `permissions: contents: read` 추가) 채택.
- 6. 🟢 Low — **반영**: 해결 방안 1(mysql을 `environment:`로 전환) 채택.
- 7. 🟢 Low — **반영**: 해결 방안 1(non-root `USER` + `.dockerignore`) 채택.

모든 항목을 이 리뷰 직후(같은 브랜치, 후속 커밋)에 반영했다 — 10단계(QA) 시작 전에
전부 완료.
