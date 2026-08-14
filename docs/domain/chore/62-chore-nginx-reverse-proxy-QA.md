# #62 dev 배포에 nginx 리버스 프록시 추가 — QA 결과

관련 기획서: [62-chore-nginx-reverse-proxy.md](./62-chore-nginx-reverse-proxy.md)
관련 코드 리뷰: [62-chore-nginx-reverse-proxy-code-review.md](./62-chore-nginx-reverse-proxy-code-review.md)
(9단계 코드 리뷰 지적 사항 High 1건/Medium 1건은 QA 이전에 반영 완료 — 이 문서는 그
이후 로컬 검증 결과만 다룬다)

## 검증 방법/범위
- 애플리케이션(Java) 코드 변경이 없어 `./gradlew checkstyleMain checkstyleTest build`로
  기존 상태 유지만 확인(변경 없음 재확인 목적).
- `deploy/docker-compose.dev.yml`: 더미 `.env`(`MYSQL_ROOT_PASSWORD=dummy`)로
  `docker compose -f docker-compose.dev.yml config`를 실행해 문법 오류 없이 해석되고,
  `nginx` 서비스가 의도대로(포트 `80:80`, `nginx.conf` 바인드 마운트, `app` 의존)
  구성됨을 확인.
- `.github/workflows/ci.yml`: Python `yaml.safe_load`로 문법 검증(#51 QA와 동일한
  방식).
- `deploy/nginx.conf`: 이 로컬 환경에 Docker 데몬이 떠 있지 않아(`docker version` 시
  데몬 연결 실패, #51 QA 때와 동일한 환경 제약) `nginx -t`로 실제 문법 검증은 못
  했다 — 파일이 짧고 표준적인 `proxy_pass`/`proxy_set_header` 구성이라 코드 리뷰
  단계에서 수동 검토로 대체했다(아래 "Medium" 참고).
- `appleboy/scp-action`의 다중 `source` + `strip_components` 동작은 코드 리뷰에서
  실제 `drone-scp` 소스코드를 읽어 확인 완료(재검증 불필요).

## 발견 사항

### Critical / High
없음(9단계 코드 리뷰의 High 1건은 그 단계에서 이미 반영 완료).

### Medium
1. **`nginx.conf` 문법 검증(`nginx -t`)과 실제 배포 후 동작(Cloudflare → nginx → app
   전체 경로)을 이 세션에서 실행해보지 못함(환경 제약)** — Docker 데몬 부재로
   `nginx -t` 자체를 못 돌렸고, EC2 실배포는 이 브랜치가 `dev`에 머지되어
   `deploy-dev` job이 실행돼야 확인 가능하다. `docker compose config`로 구성 자체의
   구조적 정합성은 확인했지만, nginx 설정 파일 내부 문법과 "실제로
   `https://gone-dev.gbsw.hs.kr`가 응답하는지"는 머지 후 첫 배포에서 확인해야 한다.
2. **`--force-recreate nginx`가 실제로 매 배포마다 502 없이 매끄럽게 재기동되는지는
   실제 반복 배포로만 확인 가능(환경 제약)** — 코드 리뷰에서 논리적으로는 검증했으나
   (Docker Compose 재생성 판단 기준, nginx의 DNS 캐시 동작에 대한 공식 문서 근거),
   실제 EC2에서 연속 배포 2회 이상을 돌려봐야 100% 확인된다.

### Low
없음.

## 결론
Critical/High 없음. 로컬에서 검증 가능한 범위(compose 구조, ci.yml 문법, Java 빌드
무영향)는 전부 통과했다. **다만 이 이슈의 핵심 가치(Cloudflare를 통한 실제 접속 성공
여부)는 `dev` 머지 후 실제 배포로만 최종 확인된다**(Medium 2건). 머지 후 첫 배포
결과를 보스에게 별도로 확인받아야 한다.
