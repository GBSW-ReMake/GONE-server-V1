# #77 브랜치 2단계 구조 재편 — QA 결과

QA 대상: `chore/#77-branch-restructure` 브랜치(기획서 "구현 순서" 4번 — CI/CD 워크플로우
변경 + 문서 갱신). 코드 리뷰 결과는
[77-chore-branch-restructure-code-review.md](./77-chore-branch-restructure-code-review.md)에
별도로 있다.

## 이미 완료된 선행 작업 (이 브랜치 밖, 기획서 "구현 순서" 1~3번)
- GitHub Environment `STAGING` 생성 + 시크릿 15개 등록 완료(옵시디언 값 그대로, 웹훅
  재사용)
- `dev` → `staging` 브랜치 rename 완료
- 새 `dev` 브랜치를 `staging` 시점에서 분기해 push 완료

## 발견 사항

### Critical / High
없음.

### Medium
1. ~~CI 파이프라인 실제 통과 여부는 이 브랜치 상태로는 확인 불가~~ → **해소됨**: PR #85
   생성 직후 Actions에서 `checkstyle`(pass, 1m52s), `build-and-test`(pass, 3m3s) 확인,
   `build-and-push-image`/`deploy-staging`은 의도대로 `skipping` 처리됨(PR 컨텍스트라
   push 조건 불충족 — 기획서 "테스트/검증" 절이 요구한 그대로 동작).
2. **`staging` 실제 배포 검증은 이 PR의 머지만으로는 불가능**: 기획서 "구현 순서" 5~6번대로,
   `deploy-staging` job의 새 조건(`environment: STAGING`, `refs/heads/staging`)과 새
   Discord 알림 포맷은 `dev` → `staging` **승격 PR**이 머지돼 `staging`에 실제로 push가
   일어나야 실행된다. 이 QA 시점(← `dev`로의 PR)에서는 해당 없음 — 승격 PR 이후 별도로
   헬스체크·Discord 알림 포맷을 확인해야 한다(기획서 "테스트/검증" 절 그대로).

### Low
없음(코드 리뷰의 Low 1건은 이미 반영해 커밋함).

## 확인 완료 항목
- 로컬 `./gradlew build`, `./gradlew test`, `./gradlew checkstyleMain checkstyleTest` 전부
  통과(코드 변경 없이 워크플로우/문서만 수정이라 `UP-TO-DATE`로 스킵된 태스크 포함, 실패
  없음).
- 신규/변경 엔드포인트 없음(기획서에 명시) — 정상/에러 케이스 검증 대상 없음. Postman/Notion
  단계(15/17)도 기획서·이슈 체크리스트에 "해당 없음"으로 이미 명시돼 있다.
- `docs/rules/branch-workflow.md`를 처음부터 끝까지 다시 읽어 "dev"/"staging"/"DEV"/"STAGING"
  지칭이 헷갈리는 문장이 남아있지 않은지 확인 — 없음(🚫 dev 직접 푸시 금지 절의 이유가
  "히스토리/리뷰 규율 유지"로 바뀌었고, 🚫 staging 직접 푸시 금지 절이 신설됐으며, 7단계는
  "dev에서 분기" 그대로 유지돼 신규 `dev`가 자연스럽게 그 역할을 잇는다).
- `docs/rules/pr-template.md`, `docs/rules/progress-tracking.md`도 기획서 "문서 갱신 대상"
  절이 요구한 내용을 전부 반영했는지 재확인 — 반영 완료(코드 리뷰 문서에서도 동일하게 확인됨).

## 남은 절차 (QA 범위 밖, 참고용)
1. 이 PR을 `dev`로 생성 → 머지 → Actions에서 CI 통과 확인(Medium 1번 해소)
2. `dev` → `staging` 승격 PR 생성 → 머지 → 헬스체크 + 새 Discord 알림 포맷 확인(Medium 2번
   해소)
3. 검증 끝나면 기존 `DEV` GitHub Environment 삭제
