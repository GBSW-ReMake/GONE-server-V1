# #62 dev 배포에 nginx 리버스 프록시 추가 — 기획서

관련 이슈: [#62 dev 배포에 nginx 리버스 프록시 추가 (Cloudflare origin 연결)](https://github.com/GBSW-ReMake/GONE-server-V1/issues/62)
관련 선행 이슈: [#51 dev 서버(EC2) 배포 자동화(CI/CD) 구축](../chore/51-chore-dev-cicd.md)

## 개요/목적
`gone-dev.gbsw.hs.kr`(Cloudflare 프록시 켜진 DNS 레코드)로 접속하면 `521 Web Server Is
Down`이 발생한다. Cloudflare는 프록시가 켜진 레코드의 origin 연결을 정해진 포트 목록으로만
시도하는데(HTTP: `80`/`8080`/`8880`/`2052`/`2082`/`2086`/`2095`, HTTPS: `443`/`2053`/`2083`/
`2087`/`2096`/`8443`), 지금 `app` 컨테이너는 `9090`만 호스트에 노출하고 있어 이 목록에 없다
— Cloudflare가 origin에 연결을 시도조차 하지 않는다.

`80` 포트로 요청을 받아 내부적으로 `app:9090`으로 넘겨주는 nginx를 추가한다. 애플리케이션
코드 변경은 없다 — `deploy/docker-compose.dev.yml`, 신규 `deploy/nginx.conf`,
`.github/workflows/ci.yml`(scp 소스 목록 + 헬스체크 대상 포트)만 수정한다.

## 아키텍처 (변경 후)
```
[Cloudflare 엣지] --(80)--> [EC2: nginx:80] --(9090, 내부 docker 네트워크)--> [app:9090]
```
- `nginx`가 유일하게 호스트 포트를 여는 서비스가 된다. `app`의 호스트 포트 노출(`9090:9090`)은
  제거한다 — `mysql`/`redis`가 이미 같은 이유로 호스트 포트를 열지 않는 것과 동일한 패턴
  (#51 코드 리뷰 1번 항목 참고). `nginx`/`app`은 같은 `docker compose` 네트워크 안에서
  서비스 이름(`app:9090`)으로 계속 통신 가능하므로 기능 손실이 없다.
- Cloudflare SSL/TLS 모드(Flexible/Full)는 이번 이슈 범위 밖 — 보스가 Cloudflare
  대시보드에서 직접 설정한다. `nginx`는 평문 HTTP만 처리한다(Flexible 모드 전제). Full
  모드로 바꾸려면 origin에도 인증서가 필요해 범위가 커지므로, 필요해지면 별도 이슈로
  다룬다.

## `deploy/nginx.conf` (신규)
```nginx
server {
    listen 80;
    server_name _;

    location / {
        proxy_pass http://app:9090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $http_cf_connecting_ip;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```
- `server_name _;`(catch-all)로 둔다 — 도메인을 하드코딩하면 서브도메인이 또 바뀔 때
  이 파일도 같이 고쳐야 한다. 이 EC2에는 `app` 하나만 떠 있어 Host 헤더로 라우팅을
  분기할 이유가 없다.
- `X-Forwarded-*` 헤더는 지금 애플리케이션이 참조하지 않지만, 리버스 프록시 뒤에 두는
  일반적인 관례라 지금 추가해둔다 — 나중에 클라이언트 IP 로깅/판단이 필요해지는 기능이
  생겨도 nginx 설정을 다시 건드릴 필요가 없다.
- `X-Real-IP`는 `$remote_addr`가 아니라 `$http_cf_connecting_ip`를 쓴다(코드 리뷰 2번
  항목 반영) — `gone-dev.gbsw.hs.kr`는 Cloudflare 프록시가 켜진 레코드라 nginx에 실제로
  TCP 연결을 맺는 상대는 항상 Cloudflare 엣지다. `$remote_addr`를 그대로 쓰면 모든
  요청이 Cloudflare IP로 찍혀 클라이언트 IP로 쓸모가 없다 — Cloudflare가 실제 클라이언트
  IP를 담아 origin까지 전달해주는 `CF-Connecting-IP` 요청 헤더를 그대로 흘려보낸다.

## `deploy/docker-compose.dev.yml` 변경
```yaml
services:
  mysql:
    # (변경 없음)

  redis:
    # (변경 없음)

  app:
    image: ghcr.io/gbsw-remake/gone-server-v1:dev
    restart: always
    depends_on:
      - mysql
      - redis
    env_file: .env
    # ports 제거 — nginx를 통해서만 접근

  nginx:
    image: nginx:1.27-alpine
    restart: always
    depends_on:
      - app
    ports:
      - "80:80"
    volumes:
      - ./nginx.conf:/etc/nginx/conf.d/default.conf:ro

volumes:
  mysql-data:
  redis-data:
```
`nginx.conf`는 `docker-compose.dev.yml`과 같은 디렉터리(`/opt/gone/dev/`)에 두고
바인드 마운트한다 — 이미지를 새로 빌드하지 않고 파일만 교체해도 반영되므로, 향후 프록시
설정만 바꿀 때 이미지 재빌드/재푸시가 필요 없다.

## `.github/workflows/ci.yml` 변경 (`deploy-dev` job)
1. **scp 소스 목록에 `deploy/nginx.conf` 추가**. `appleboy/scp-action`은 `source`에
   콤마로 구분된 여러 경로를 받는다 — 기존 `docker-compose.dev.yml` 전송 스텝의
   `source`를 아래처럼 확장한다(`strip_components: 1`은 유지 — 두 파일 모두
   `deploy/` 바로 아래에 있어 같은 규칙으로 벗겨진다).
   ```yaml
   source: "deploy/docker-compose.dev.yml,deploy/nginx.conf"
   target: "/opt/gone/dev"
   strip_components: 1
   ```
2. **`app` 기동 후 `nginx`를 매 배포마다 강제 재생성**(코드 리뷰 1번 항목 반영):
   ```
   docker compose -f docker-compose.dev.yml up -d app
   docker compose -f docker-compose.dev.yml up -d --force-recreate nginx
   ```
   (`pull`은 `app` 이미지만 GHCR에서 받으면 되므로 `pull app`은 그대로 둔다 — `nginx`
   이미지는 Docker Hub 공식 이미지라 최초 1회만 받고 이후 태그 고정이라 매 배포 pull이
   불필요). `nginx`는 이미지 태그/볼륨 경로가 배포마다 바뀌지 않아 `up -d`만으로는
   재기동되지 않는다 — `--force-recreate`로 매 배포마다 강제로 재생성해야, `app`이 새
   컨테이너로 뜨며 바뀔 수 있는 내부 IP를 nginx가 다시 조회하고, `nginx.conf` 파일
   수정도 반영된다(둘 다 nginx가 프로세스 시작 시점에만 확인하는 값이라, 재기동 없이는
   바인드 마운트로 파일만 바꿔도 실행 중인 nginx에 반영되지 않는다).
3. **헬스체크 대상을 `9090`에서 `80`으로 변경**: `curl -s -o /dev/null -w
   "%{http_code}" http://localhost:9090` → `http://localhost:80`. 이러면 헬스체크가
   nginx까지 경유하는 실제 요청 경로(Cloudflare가 쓰는 것과 동일한 경로)를 검증하게
   되어, 기존보다 오히려 더 정확한 헬스체크가 된다.

## 영향 받는 기존 코드/설정
- `deploy/nginx.conf`(신규)
- `deploy/docker-compose.dev.yml`: `app` 서비스 `ports` 제거, `nginx` 서비스 추가
- `.github/workflows/ci.yml`: `deploy-dev` job의 scp 소스, `docker compose up -d` 대상,
  헬스체크 URL 수정
- 애플리케이션 코드(Java) 변경 없음 — `api-design.md` 6원칙 검토는 해당 없음
- `mysql`/`redis`는 변경 없음(계속 호스트 포트 미노출)

## 리스크 및 고려사항
- **`app`의 `9090` 호스트 포트 노출을 제거하면, EC2 안에서 직접
  `curl localhost:9090`으로 디버깅하던 방법이 막힌다.** 필요하면 `docker exec`로
  컨테이너 안에 들어가거나, `docker compose exec app curl localhost:9090`처럼 같은
  네트워크 안에서 확인해야 한다(`mysql`/`redis`가 이미 같은 트레이드오프를 가지고
  있음, #51 기획서 참고).
- **EC2 보안그룹 인바운드 `80` 허용**: 확인 완료 — dev EC2 보안그룹에 `80`(및 `443`)이
  이미 열려 있어 이 이슈에서 별도로 조치할 게 없다.
- **Cloudflare SSL/TLS 모드가 Flexible이 아니면(Full/Full Strict) 동작하지 않는다.**
  nginx가 평문 HTTP만 처리하므로, Cloudflare가 origin에 HTTPS로 연결을 시도하는 모드면
  이 구성으로는 여전히 실패한다 — 이것도 보스가 Cloudflare 대시보드에서 직접 확인한다.
- **`nginx:1.27-alpine` 이미지 버전을 고정 태그로 둔다** — `latest`처럼 움직이는 참조는
  쓰지 않는다(#51에서 세운 third-party 이미지/액션 버전 고정 원칙과 동일).
- **자동 롤백 없음**: 헬스체크 실패 시 Discord 알림만 오고 이전 이미지로 되돌리지 않는다
  (#51과 동일한 기존 정책, 이번 이슈로 바뀌지 않음).

## 완료 조건 (Definition of Done)
- `https://gone-dev.gbsw.hs.kr` 요청이 nginx를 거쳐 `app`까지 도달해 응답한다(Cloudflare
  SSL 모드/보안그룹은 보스가 별도로 맞춘 뒤 확인)
- `mysql`/`redis` 컨테이너는 이번 배포로 재시작되지 않고 데이터가 유지된다
- 로컬 `./gradlew build`/`checkstyleMain`(Java 코드 변경 없으므로 기존 상태 유지 확인),
  CI 통과
- 배포 성공/실패 Discord 알림은 기존과 동일하게 온다(변경 없음)

## 테스트 방법
- 컨트롤러/애플리케이션 코드 변경이 없어 단위 테스트 대상 없음.
- `deploy/nginx.conf` 문법은 `docker run --rm -v $(pwd)/deploy/nginx.conf:/etc/nginx/conf.d/default.conf:ro nginx:1.27-alpine nginx -t`로 로컬에서 검증.
- `.github/workflows/ci.yml`은 YAML 파싱 검증(#51 QA와 동일한 방식) + 실제 `dev` 배포
  후 위 "완료 조건"을 직접 확인.
