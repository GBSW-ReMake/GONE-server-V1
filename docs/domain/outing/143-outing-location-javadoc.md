# #143 OutingLocationRepository Javadoc 정밀도 설명 수정 — 기획서

관련 이슈: [#143 docs(outing): OutingLocationRepository Javadoc 정밀도 설명이 스키마와
불일치](https://github.com/GBSW-ReMake/GONE-server-V1/issues/143)
계기: PR #140 Copilot 리뷰(`OutingLocationRepository.java:20`)

## 개요/목적
`findByOutingIdOrderByRecordedAtAscIdAsc`의 Javadoc은 `id` 보조 정렬을 쓰는 이유를
"`recorded_at`이 초 단위 정밀도라 같은 초에 들어온 핑이 있을 수 있다"고 설명한다. 실제
`outing_location.recorded_at` 컬럼은 `V20260825160335__add_outing_location.sql`에서
`DATETIME(6)`(마이크로초 정밀도)로 정의돼 있어 이 설명이 스키마와 맞지 않는다. 동작에는
영향 없는 순수 문서(Javadoc) 수정이다 — 새 HTTP 엔드포인트나 로직 변경은 없다.

## 변경 사항 — 변경 전/후

**변경 전** (`OutingLocationRepository.java:12-20`):
```java
/**
 * 특정 외출증의 위치 핑을 기록된 시각 오름차순으로 조회합니다(#97, 동선 조회용). {@code
 * recorded_at}이 초 단위 정밀도라 같은 초에 들어온 핑이 있을 수 있어, {@code id}(삽입 순서와
 * 일치)를 보조 정렬 키로 둔다 — 이 서비스의 다른 목록 조회들과 같은 이유({@code
 * OutingService}의 {@code LIST_QUERY_SORT} 등 참고).
 *
 * @param outingId 조회할 외출증의 내부 PK
 * @return 조건에 맞는 위치 핑 목록, {@code recordedAt} 오름차순(동률 시 {@code id} 오름차순)
 */
```

**변경 후**: "초 단위 정밀도" 대신 "같은 timestamp로 동률이 발생할 수 있다"는 표현으로
바꿔, 정밀도 수준을 특정하지 않고도 `id` 보조 정렬의 필요성만 설명한다.
```java
/**
 * 특정 외출증의 위치 핑을 기록된 시각 오름차순으로 조회합니다(#97, 동선 조회용). {@code
 * recorded_at}이 같은 timestamp로 동률일 수 있어, {@code id}(삽입 순서와 일치)를 보조 정렬
 * 키로 둔다 — 이 서비스의 다른 목록 조회들과 같은 이유({@code OutingService}의 {@code
 * LIST_QUERY_SORT} 등 참고).
 *
 * @param outingId 조회할 외출증의 내부 PK
 * @return 조건에 맞는 위치 핑 목록, {@code recordedAt} 오름차순(동률 시 {@code id} 오름차순)
 */
```

## 영향받는 기존 코드/테스트
- `OutingLocationRepository.java`의 Javadoc 문구만 수정. 로직/시그니처 변경이 없어 기존
  테스트 동작에 영향 없고, 새 테스트도 필요 없다.

## 리스크 및 고려사항
- 순수 문서 수정이라 API 계약·DB 스키마·런타임 동작 변경이 전혀 없다 —
  [api-design.md](../../rules/api-design.md) 6원칙 검토 대상 아님.
