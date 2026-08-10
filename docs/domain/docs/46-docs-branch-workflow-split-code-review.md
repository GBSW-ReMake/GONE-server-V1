# #46 branch-workflow.md 세부 규칙 분리 — 코드 리뷰 결과

[code-review-template.md](../../rules/code-review-template.md) 형식을 따른다.
파일 위치 규칙상 코드가 아닌 문서 재구성 브랜치이므로 도메인은 `docs`로 취급했다.

## 발견 사항

내용 누락은 없음. 별도로 띄운 독립 `code-review` 에이전트(구현 맥락 없이 diff/기획서만 보고
전체 코드 리뷰 관점에서 점검)가 아래 1건을 지적해 직접 재검증했다. 그 결과 실제 문제로
남는 것은 1건(Low)뿐이다.

### 1. 🟢 Low — 기획서 커밋이 구현 커밋보다 나중에 만들어짐 (기획서→승인→구현 순서 위반)

**문제**: `git log --format="%h %ad %s" --date=iso-strict dev..HEAD`로 확인한 결과,
이 브랜치의 커밋 순서는 다음과 같다.

```
4439ebc 2026-08-10T15:08:02+09:00 docs: branch-workflow.md 세부 규칙을 별도 rules 파일 3개로 분리
bc9d2f6 2026-08-10T15:08:16+09:00 docs(chore): #46 branch-workflow.md 분리 기획서 추가
```

실제 분리 구현(`4439ebc`)이 기획서 추가(`bc9d2f6`)보다 14초 먼저 커밋됐다. 이 PR이
다루는 문서(`branch-workflow.md`) 자체와 `CLAUDE.md`가 요구하는 순서는 "기획서 작성(2단계)
→ 검토 요청(3) → 검토(4) → 수정(5) → 검토 종료/승인(6) → 브랜치 생성(7) → 구현(8)"인데,
이번 작업은 그 반대로 구현 커밋이 먼저 만들어졌다. 코드/문서 내용 자체에는 영향이 없지만,
"워크플로우 문서를 정리하는 작업이 그 워크플로우 순서를 어겼다"는 점에서 이 PR의 신뢰성에
흠이 된다.

**해결 방안**:
1. 커밋을 그대로 두고 PR 설명에 "기획서 커밋이 구현 커밋보다 늦게 생성된 것은 기록상의
   순서일 뿐, 실제로는 기획서 검토·승인이 구현 착수보다 먼저 있었다"는 사실 관계를 명시한다
   — 히스토리를 다시 쓰지 않아 안전하지만, git log만 보는 사람에게는 여전히 위반처럼
   보인다는 한계가 있다.
2. `git rebase`로 두 커밋의 순서를 바꿔 기획서 커밋이 구현 커밋보다 먼저 오도록 재정렬한다
   — 기록상으로도 워크플로우 순서와 일치하게 되지만, 이미 원격에 푸시된 상태라면 히스토리
   재작성에 따른 위험(강제 푸시 필요, 다른 협업자 영향)이 있어 신중해야 한다.

## 검증했지만 실제 문제가 아닌 것으로 판단한 지적

독립 `code-review` 에이전트는 기획서 6번째 줄의 "187줄"이라는 서술이 `git show
dev:docs/rules/branch-workflow.md | wc -l` 결과(186)와 다르다고 지적했다. 직접 재현한 결과
원본 파일이 **줄 끝 개행 문자(trailing newline) 없이 끝나서** `wc -l`이 마지막 줄을 세지
않아 186으로 나온 것이었다(`awk 'END{print NR}'` 및 Read 도구로 확인한 실제 마지막 줄
번호는 187, 내용은 "형식만 Notion 규칙에 맞게 다듬는다 — 내용을 새로 지어내지 않는다."로
원본 파일의 끝과 일치). 즉 기획서의 "187줄"은 실제 내용 줄 수와 맞고, `wc -l`을 그대로
믿은 자동 리뷰 쪽의 오탐이다. 그래서 이 항목은 위 발견 사항에 포함하지 않았다.

## 리뷰 범위

이 브랜치는 애플리케이션 코드 변경이 없는 순수 문서 재구성(`docs/rules/`,
`docs/domain/docs/`)이라, 일반적인 코드 품질 관점 대신 기획서
(`docs/domain/docs/46-docs-branch-workflow-split.md`)에서 확정한 목적 —
**"내용 누락 없이 이전됐는가"** — 를 검증하는 데 집중했다.

## 리뷰 방법

1. `git diff dev...HEAD -- docs/rules/ docs/domain/docs/`로 이번 브랜치의 전체 diff 확인
   (신규 파일 3개: `progress-tracking.md`, `code-review-isolation.md`,
   `postman-collection-structure.md`, 축소된 `branch-workflow.md` 1개).
2. `git show dev:docs/rules/branch-workflow.md`로 분리 전 원본(187줄) 전체를 확보하고,
   현재 `docs/rules/branch-workflow.md`(143줄) 및 신규 3개 파일과 **문장 단위로 대조**:
   - 원본 8~19줄("🚨 한 번에 하나의 기능만") + 21~28줄("📋 진행 상태 추적") →
     `progress-tracking.md`로 문장 그대로 이전됨을 확인. `branch-workflow.md`에는 두 섹션을
     합친 3줄 요약 + 링크만 남음.
   - 원본 102~114줄(9단계 코드 리뷰의 컨텍스트 격리 세부 절차) → `code-review-isolation.md`로
     문장 그대로 이전됨을 확인(소제목으로 재구성됐을 뿐 문장 삭제 없음). `branch-workflow.md`
     9단계에는 핵심 요약 + 링크만 남음.
   - 원본 145~167줄(15단계 Postman 컬렉션 구조 규칙) → `postman-collection-structure.md`로
     구조 규칙(최상위 1엔드포인트-1항목, 기능별 검증 폴더) 문장이 그대로 이전됨을 확인.
     워크스페이스/API 키 위치(원본 164~165줄)와 PR 리뷰 재확인 규칙(166~167줄)은 기획서
     계획대로 `branch-workflow.md` 15단계 본문에 그대로 남아있음.
   - 원본의 나머지 부분(제목/서두, v2 변경 요약, 0~8단계 전문, 10~14단계, 16~17단계)은
     `branch-workflow.md`에 축약 없이 원문 그대로 남아있음을 확인.
   - 대조 결과 원본에 있던 문장 중 어디로도 이전되지 않고 사라진 내용은 없음.
3. 기획서의 "분리 대상 확정" 절에 적힌 4개 분리 대상(줄 번호 포함: 21~28줄, 8~19줄,
   102~114줄, 145~167줄)과 실제 diff에서 이동한 줄 범위가 정확히 일치함을 확인.
   "분리하지 않고 그대로 두는 것"(2단계 경로/문서명 규칙, 8단계 기획서/코드 불일치 분기)도
   실제로 `branch-workflow.md`에 그대로 남아있음을 확인.
4. 신규 파일 3개 안의 상대 링크를 `docs/rules/` 실제 파일 목록(`ls docs/rules/`, 13개 파일)과
   대조:
   - `progress-tracking.md` → `./branch-workflow.md` (존재)
   - `code-review-isolation.md` → `./branch-workflow.md`, `./code-review-template.md` (모두 존재)
   - `postman-collection-structure.md` → `./branch-workflow.md` (존재)
   - `branch-workflow.md` → `./progress-tracking.md`, `./code-review-isolation.md`,
     `./code-review-template.md`, `./postman-collection-structure.md`,
     `./issue-template.md`, `./api-design.md`, `./sentence-refinement.md`,
     `./commit-convention.md`, `./code-style.md`, `./test-convention.md`,
     `./pr-template.md`, `./notion-api-spec.md` (모두 존재)
   - 깨진 링크 없음.
5. `grep -rn "branch-workflow.md" CLAUDE.md docs/rules/*.md`로 다른 문서가
   branch-workflow.md를 참조하는 곳을 전수 확인. `CLAUDE.md`(2단계/8단계),
   `api-design.md`(2단계, 2~6단계), `commit-convention.md`(7단계), `pr-template.md`
   (14~16단계), `notion-api-spec.md`(17단계), `code-review-template.md`(9단계) 등 모든
   참조가 **단계 번호**만 사용하고 있고, 이번 변경으로 단계 번호 자체는 하나도 바뀌지
   않았으므로 깨지는 참조가 없음을 확인. 섹션 제목이나 원본 줄 번호를 직접 앵커
   (`branch-workflow.md#...`)로 참조하는 곳은 저장소 전체에 없음(`grep -rn
   "branch-workflow.md#"` 결과 0건).

## 적용 결과

- **1번(Low, 커밋 순서)**: 해결 방안 2(재정렬)를 적용함. 아직 원격에 푸시하지 않은 상태라
  히스토리 재작성 위험 없이 로컬에서 안전하게 재정렬 가능했다 — `git reset --hard`로
  두 커밋 이전 지점으로 되돌린 뒤 기획서 커밋 → 구현 커밋 순으로 `cherry-pick`해
  워크플로우 순서(기획서 먼저, 구현 나중)와 커밋 히스토리를 일치시켰다. 결과 트리는
  재정렬 전과 동일함을 `git diff --stat`으로 확인.

## 참고: 리뷰 대상에서 제외한 것

일반적인 마크다운 스타일/오탈자 검토는 이번 리뷰의 핵심 목적(내용 누락 여부)이 아니라고
판단해 별도로 다루지 않았다. 훑어본 범위에서 눈에 띄는 오탈자나 깨진 마크다운 문법은
없었다.
