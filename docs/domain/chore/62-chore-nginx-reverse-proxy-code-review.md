# #62 dev 배포에 nginx 리버스 프록시 추가 — 코드 리뷰 결과

관련 기획서: [62-chore-nginx-reverse-proxy.md](./62-chore-nginx-reverse-proxy.md)
형식 규칙: [code-review-template.md](../../rules/code-review-template.md)

## 리뷰 범위/방법

- 대상: `git diff dev...chore/#62-nginx-reverse-proxy` 전체. 신규 `deploy/nginx.conf`,
  `deploy/docker-compose.dev.yml`(`app`의 `ports` 제거 + `nginx` 서비스 추가),
  `.github/workflows/ci.yml`(`deploy-dev` job의 scp 소스/`up -d` 대상/헬스체크 URL)만
  대상이다. 애플리케이션 Java 코드 변경 없음(`api-design.md` 6원칙 검토는 해당 없음).
- 기획서(`62-chore-nginx-reverse-proxy.md`) 대비 범위 초과 변경은 없음을 확인했다 —
  `deploy/nginx.conf` 본문, `docker-compose.dev.yml`의 `nginx` 서비스 정의,
  `ci.yml`의 세 지점(scp source, `up -d` 대상, 헬스체크 URL) 모두 기획서에 적힌 내용과
  글자 단위로 일치한다.
- `appleboy/scp-action`(`ci.yml:381-388`)의 `source:
  "deploy/docker-compose.dev.yml,deploy/nginx.conf"` + `strip_components: 1` 조합이
  두 파일을 실제로 `/opt/gone/dev/docker-compose.dev.yml`, `/opt/gone/dev/nginx.conf`로
  올바르게 풀어내는지 의심스러워, `appleboy/drone-scp`(scp-action이 실제로 실행하는
  바이너리) 소스코드(`plugin.go`)를 GitHub API로 직접 읽어 확인했다 — `buildTarArgs`가
  콤마로 나뉜 모든 source를 **하나의 tar 아카이브**로 묶고(`tar -zcf archive.tar.gz
  deploy/docker-compose.dev.yml deploy/nginx.conf`), `buildUnTarArgs`가 그 아카이브
  전체에 `--strip-components 1`을 한 번만 적용한다. 두 파일 모두 `deploy/` 한 단계
  아래에 있어 각각 `deploy/`가 제거되고 `docker-compose.dev.yml`, `nginx.conf`가 목표
  디렉터리 바로 아래에 떨어진다 — 의도대로 동작함을 코드 레벨에서 확인했다(별도 항목으로
  기록하지 않음).
- `application.yml:26`의 `server.port: 9090`이 `deploy/nginx.conf`의 `proxy_pass
  http://app:9090`과 일치하는지 확인했다 — 일치한다.
- `.github/workflows/ci.yml` 전체에서 `9090`을 검색해 헬스체크 외에 놓친 참조가 없는지
  확인했다 — `deploy-dev` job에는 더 이상 `9090`이 등장하지 않는다(의도대로).
- `deploy/nginx.conf`에 `client_max_body_size` 지시자가 없어 기본값(1m)으로 큰 요청이
  막힐 가능성을 확인했다 — 파일 업로드는 `FileController`/`R2FileService`가 R2 presigned
  URL로 클라이언트가 R2에 직접 업로드하는 구조라 이 nginx를 거치지 않는다. 이 diff
  범위에서는 문제가 되지 않아 별도 항목으로 기록하지 않았다.
- **독립 교차 검증**: 이 문서 작성과 별개로, `code-review` 스킬을 등록한 별도 에이전트가
  같은 diff를 독립적으로(이 문서의 추론 과정을 공유하지 않고) 리뷰했고, 그 결과를 다시
  포크한 에이전트가 재검증(CONFIRMED)했다. 그 결과가 아래 1번 항목(`nginx` 컨테이너
  미재기동으로 인한 업스트림 IP 낡음)과 동일한 근본 원인을 독립적으로 지목했다 — 같은
  결론에 두 개의 독립된 경로로 도달했다는 점에서 이 항목의 신뢰도가 높다고 판단했다.
- Critical 없음 — 이번 배포(최초 `nginx` 서비스 생성) 자체가 즉시 깨지는 경로, 시크릿
  노출 경로는 확인되지 않았다. 아래 발견 사항은 "이번 배포는 통과하지만 다음 배포부터
  또는 향후 확장 시 문제가 되는" 경로들이다.

---

## 1. 🟠 High — `nginx` 컨테이너가 이후 배포에서 재기동되지 않아, `nginx.conf` 수정이 반영되지 않고 `app` 컨테이너 재생성 시 프록시 대상 IP가 낡아질 수 있음

**문제**: `.github/workflows/ci.yml:423-424`는 매 배포마다 `docker compose -f
docker-compose.dev.yml pull app`로 `app` 이미지만 새로 받고, `up -d app nginx`를
실행한다. Docker Compose는 서비스를 재생성할지 여부를 그 서비스의 **compose 파일 상
정의**(이미지 태그, `environment`, `volumes` 등)가 이전 실행과 달라졌는지로 판단하고,
바인드 마운트된 호스트 파일의 **내용**이 바뀐 것은 재생성 트리거로 보지 않는다. `nginx`
서비스 정의(`deploy/docker-compose.dev.yml:24-32`)는 이미지 태그(`nginx:1.27-alpine`,
고정)도 볼륨 경로(`./nginx.conf:/etc/nginx/conf.d/default.conf:ro`)도 배포마다 바뀌지
않으므로, `nginx` 컨테이너는 한 번 만들어진 뒤로는 이 워크플로우로 다시 재기동되지 않는다.
이 하나의 원인이 서로 다른 두 가지 증상으로 나타난다.

1. **`deploy/nginx.conf`를 고쳐서 배포해도 nginx에 반영되지 않는다.** 기획서는 이
   지점을 `docker-compose.dev.yml`과 같은 디렉터리에 바인드 마운트한 이유로 "이미지를
   새로 빌드하지 않고 파일만 교체해도 반영되므로"(`62-chore-nginx-reverse-proxy.md`
   "`deploy/docker-compose.dev.yml` 변경" 절)를 명시적으로 든다. 하지만 nginx는 설정
   파일을 프로세스 시작 시점 또는 `nginx -s reload` 시점에만 읽어 들인다 — 바인드
   마운트로 컨테이너 안의 파일 내용이 즉시 바뀌어도, 이미 떠 있는 nginx worker
   프로세스는 그 변경을 감지하지 않는다. 이 워크플로우는 재기동도 reload도 트리거하지
   않으므로, `nginx.conf`를 고쳐 push해도 scp로 EC2에는 새 파일이 올라가지만 실행
   중인 nginx는 이전 설정을 계속 쓴다. 이 증상은 100% 재현된다(신뢰할 수 있는
   Docker Compose 재생성 판단 기준을 근거로 함 — 확률적 요소 없음).
2. **`app` 컨테이너가 재생성되며 얻는 새 내부 IP를 nginx가 못 따라갈 수 있다.**
   `deploy/nginx.conf:7`의 `proxy_pass http://app:9090;`은 변수를 쓰지 않는 정적
   호스트명이라, nginx 공식 문서 기준으로 **시작(또는 reload) 시점에 단 한 번만**
   DNS를 조회해 그 결과를 워커 프로세스 수명 동안 그대로 재사용한다(요청마다 재조회하지
   않음 — 요청마다 재조회하려면 `resolver` 지시자 + 변수 조합이 필요). `app`은 `:dev`
   태그 이미지가 배포마다 다시 빌드/푸시되므로 사실상 매 배포마다 새 이미지로 재생성되고,
   Docker의 기본 브리지 네트워크는 컨테이너 재생성 시 이전과 다른 내부 IP를 새로 할당할
   수 있다(항상 그런 것은 아니고 IPAM 할당 상황에 따라 같은 IP가 재사용될 수도 있음 —
   이 부분만 확정적이지 않음). `nginx`는 위 1번 증상과 같은 이유로 이 워크플로우 안에서
   재기동되지 않으므로, `app`이 새 IP를 받으면 nginx는 죽은 옛 IP로 계속 프록시를
   시도해 502를 반환하게 된다. 다행히 `헬스체크` 스텝(`ci.yml:436-446`)이
   `http://localhost:80`(nginx 경유)을 확인하고 502(5xx)는 "정상 아님"으로 판정하므로
   이 상태는 배포 실패(❌ Discord 알림)로 시끄럽게 드러나긴 한다 — 다만 실패 원인이
   "이번에 배포된 코드 문제"가 아니라 "nginx가 재기동되지 않아 생긴 인프라 문제"인데도
   똑같이 "dev 배포 실패"로만 보고돼, 다음 배포부터 원인 파악에 매번 혼란을 준다.

**해결 방안**:
1. `.github/workflows/ci.yml:424` 줄을 `docker compose -f docker-compose.dev.yml up
   -d app && docker compose -f docker-compose.dev.yml up -d --force-recreate nginx`
   (또는 `restart nginx`)로 바꿔, `app`이 먼저 새 컨테이너로 뜬 뒤 `nginx`를 항상
   강제로 재생성한다. 두 증상을 한 번에 해결한다 — `nginx.conf` 파일 내용이 바뀌었으면
   재생성 시 새로 읽고, `app`의 IP가 바뀌었어도 재생성된 nginx가 그 시점의 최신 IP로
   다시 DNS를 조회한다. 배포마다 nginx가 짧게(수백ms) 재시작되어 그 사이 요청이 순간
   끊길 수 있다는 트레이드오프가 있지만, `app` 자체도 매 배포마다 재생성되며 이미 같은
   수준의 순단이 발생하고 있어 새로 추가되는 리스크는 아니다.
2. `deploy/nginx.conf`를 `resolver 127.0.0.11 valid=10s; set $upstream_app app:9090;`
   + `proxy_pass http://$upstream_app;` 형태로 바꿔, Docker의 내장 DNS(`127.0.0.11`)로
   10초 TTL마다 재조회하게 한다 — nginx 컨테이너 자체는 재기동하지 않아도 `app`의
   최신 IP를 따라간다. 다만 이 방법은 "①증상: `nginx.conf` 파일 수정 자체가 반영 안 됨"은
   해결하지 못한다(그 문제는 여전히 남아 방안 1과 함께 적용해야 한다) — 즉 방안 1
   단독으로 두 증상을 모두 해결하는 데 비해, 이 방안은 nginx 설정에 Docker 내부 DNS에
   대한 암묵적 의존을 추가하면서도 문제를 완전히 없애지 못해 상대적으로 이득이 적다.

---

## 2. 🟡 Medium — `X-Real-IP`/`X-Forwarded-For`가 실제 클라이언트 IP가 아니라 Cloudflare 엣지 IP를 담게 됨

**문제**: `deploy/nginx.conf:8-9`는 `proxy_set_header X-Real-IP $remote_addr;`,
`proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`로 nginx 시점의
`$remote_addr`(= nginx에 실제로 TCP 연결을 맺은 상대의 IP)를 그대로 클라이언트 IP로
간주한다. 그런데 `gone-dev.gbsw.hs.kr`는 Cloudflare 프록시(오렌지 클라우드)가 켜진
DNS 레코드다(기획서 개요 절) — 즉 이 EC2에 실제로 TCP 연결을 맺는 상대는 최종 사용자가
아니라 **Cloudflare 엣지 서버**다. 따라서 `$remote_addr`는 항상 Cloudflare가 쓰는
IP 대역 중 하나이고, 실제 사용자 IP가 아니다.

기획서는 이 헤더를 지금 당장 쓰지는 않지만 "나중에 클라이언트 IP 로깅/판단이 필요해지는
기능이 생겨도 nginx 설정을 다시 건드릴 필요가 없다"는 이유로 미리 추가해둔다고 명시한다
(`62-chore-nginx-reverse-proxy.md` "`deploy/nginx.conf`(신규)" 절). 하지만 지금 값 그대로는
그 미래 기능이 생겼을 때 오히려 잘못된 IP(모든 요청이 Cloudflare IP로 찍혀 사실상
쓸모없는 로그)를 얻게 되어, 정작 필요한 시점에 nginx 설정을 다시 고쳐야 한다 — 기획서가
피하려던 상황이 그대로 재현된다.

**해결 방안**:
1. `proxy_set_header X-Real-IP $http_cf_connecting_ip;`로 바꾼다. Cloudflare는 프록시된
   모든 요청에 실제 클라이언트 IP를 `CF-Connecting-IP` 헤더로 origin까지 전달하므로,
   이 값을 그대로 전달하면 된다. 구현 비용이 가장 낮다(한 줄 교체). 단점은 Cloudflare를
   거치지 않고 직접 EC2 IP/포트 80으로 접근하는 요청이 있으면(보안그룹이 열려 있으므로
   이론상 가능) 그 헤더를 요청자가 임의로 위조해 보낼 수 있다는 점이다 — Cloudflare
   IP 대역에서 온 요청만 신뢰하도록 방안 2와 함께 적용하는 것이 더 안전하다.
2. `ngx_http_realip_module`(nginx 표준 빌드에 포함, alpine 이미지도 포함)로
   `set_real_ip_from <Cloudflare IP 대역>;` + `real_ip_header CF-Connecting-IP;`를
   추가해, nginx 자체의 `$remote_addr`을 Cloudflare가 보고한 실제 클라이언트 IP로
   치환한다. 이렇게 하면 지금 코드의 `$remote_addr`/`$proxy_add_x_forwarded_for` 그대로
   두어도 올바른 값이 들어간다(코드 변경 최소화). Cloudflare IP 대역 목록을
   (`https://www.cloudflare.com/ips/`)에서 가져와 nginx 설정에 고정 값으로 넣어야 하고,
   Cloudflare가 대역을 바꾸면 이 목록도 갱신해야 하는 유지보수 비용이 생긴다.

이번 diff에서 애플리케이션이 아직 이 헤더를 전혀 참조하지 않아(기획서 명시) 지금 당장
기능이 깨지는 것은 아니라 Medium으로 표시했다.

---

## 3. 🟢 Low — `nginx.conf`에 업스트림 연결 재사용/타임아웃 설정이 없음

**문제**: `deploy/nginx.conf`에 `proxy_http_version`이 없어 nginx→`app` 구간은 기본값인
HTTP/1.0으로 통신하고, 응답마다 업스트림 연결을 새로 맺는다(keep-alive 없음). 또
`proxy_connect_timeout`/`proxy_read_timeout` 등이 없어 모두 nginx 기본값(각 60초)을
그대로 쓴다. dev 환경 트래픽 규모에서는 성능 차이가 체감되지 않지만, 나중에 운영(prod)
환경에 같은 패턴을 복제할 때는 짚고 넘어갈 만하다.

**해결 방안**:
1. `location /` 블록에 `proxy_http_version 1.1;` 한 줄을 추가한다 — 구현 비용이 가장
   낮고, 업스트림 연결 재사용의 전제 조건을 갖춘다(완전한 keep-alive를 쓰려면
   `upstream` 블록에 `keepalive` 지시자도 필요해 이 한 줄만으로 재사용이 즉시 켜지지는
   않는다).
2. 이번 dev 전용 PR 범위에서는 넘기고, 운영 배포를 설계하는 후속 이슈에서 함께
   정리한다 — 기획서도 이번 이슈 범위를 "Cloudflare 521 해소"로 명확히 좁혔으므로,
   성능 튜닝 성격의 항목을 지금 끼워 넣지 않는 편이 범위 관리에는 더 맞는다.

---

## 요약

Critical 없음(이번 최초 배포 자체가 즉시 깨지는 경로, 시크릿 노출 경로는 확인되지
않음 — 위 "리뷰 범위/방법" 참고). High 1건(`nginx` 컨테이너가 이후 배포에서 재기동되지
않아 설정 반영 누락 + 업스트림 IP 낡음 위험). Medium 1건(`X-Real-IP`/`X-Forwarded-For`가
Cloudflare 엣지 IP를 담아, 기획서가 명시한 향후 활용 목적을 달성하지 못함). Low 1건
(업스트림 연결 재사용/타임아웃 미설정, 성능 튜닝 성격이라 이번 범위에서는 보류 가능).

기획서 범위를 벗어난 변경, 기존 컨벤션(이미지 태그 고정, 시크릿 취급 원칙, `mysql`/
`redis` 호스트 포트 미노출 패턴, `concurrency`/`environment: dev`)과 어긋나는 지점은
없었다.

## 반영 결과

- **High 1**: 해결 방안 1번을 적용했다. `ci.yml`의 `docker compose ... up -d app nginx`를
  `up -d app` → `up -d --force-recreate nginx` 두 단계로 나눠, 매 배포마다 `nginx`를
  강제로 재생성한다. `nginx.conf` 수정 미반영과 `app` IP 낡음 문제를 한 번에 해결한다.
- **Medium 1**: 해결 방안 1번을 적용했다. `X-Real-IP`를 `$http_cf_connecting_ip`로
  바꿨다. 방안 2(`ngx_http_realip_module` + Cloudflare IP 대역 등록)는 유지보수
  비용(Cloudflare가 대역을 바꿀 때마다 갱신 필요) 대비 dev 환경에서의 이득이 낮아
  보류 — Cloudflare를 우회해 직접 EC2:80으로 오는 위조 요청 가능성은 dev 환경 특성상
  감수하고, 실제 위험이 되면(예: 이 헤더로 접근 제어를 하게 되는 시점) 그때 방안 2를
  추가한다.
- **Low 1**: 보류(해결 방안 2번 채택) — 성능 튜닝 성격이라 이번 이슈 범위 밖. 운영
  배포 설계 후속 이슈에서 함께 정리한다.
- 위 변경 후 `docker compose -f docker-compose.dev.yml config`(더미 `.env`)와 Python
  `yaml.safe_load`로 `ci.yml` 문법 재확인.
