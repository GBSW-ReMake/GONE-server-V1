package com.remake.gone.common.response;

import java.util.List;

/**
 * 페이지네이션된 목록 응답 포맷(#41에서 최초 도입).
 *
 * <p>{@link ApiResponse}의 {@code data} 자리에 담겨서, 목록을 반환하는 엔드포인트 전반에서
 * 재사용한다. Spring Data의 {@code Page}를 API 응답으로 직접 노출하지 않고 이 프로젝트
 * 전용의 얇은 래퍼를 쓴다 — {@code Page}는 정렬/필터 메타데이터 등 이 프로젝트 API 계약에
 * 불필요한 필드가 섞여 있고, 응답 스키마를 Spring Data 내부 타입에 결합시키지 않기 위함이다.
 *
 * @param <T>            목록 항목의 타입
 * @param content        이번 페이지에 해당하는 항목 목록
 * @param page           요청한 페이지 번호(0부터 시작)
 * @param size           요청한 페이지 크기
 * @param totalElements  전체 항목 수(페이지네이션 적용 전 기준)
 * @param totalPages     전체 페이지 수
 * @param hasNext        다음 페이지가 있는지 여부
 */
public record PageResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean hasNext
) {

  /**
   * 이미 메모리에 있는 전체 목록을 주어진 페이지 번호/크기로 잘라 {@link PageResponse}를
   * 만듭니다. {@code page}가 실제 존재하는 페이지 범위를 넘으면 빈 {@code content}를
   * 반환합니다(에러 아님 — 마지막 페이지 다음 페이지를 요청하는 건 흔한 상황).
   *
   * @param <T>        목록 항목의 타입
   * @param allContent 전체 목록(페이지네이션 적용 전)
   * @param page       요청한 페이지 번호(0부터 시작, 음수 불가는 호출부에서 검증)
   * @param size       요청한 페이지 크기(1 이상, 호출부에서 검증)
   * @return 계산된 {@link PageResponse}
   */
  public static <T> PageResponse<T> of(List<T> allContent, int page, int size) {
    int totalElements = allContent.size();
    int totalPages = (int) Math.ceil((double) totalElements / size);
    // page/size는 long으로 곱해 int 오버플로를 피한다 — page가 매우 큰 값으로 와도
    // (예: Integer.MAX_VALUE 근처) fromIndex는 항상 [0, totalElements] 안으로 clamp된다.
    long fromIndexLong = (long) page * size;
    int fromIndex = (int) Math.min(fromIndexLong, totalElements);
    int toIndex = Math.min(fromIndex + size, totalElements);
    List<T> content = allContent.subList(fromIndex, toIndex);
    boolean hasNext = (page + 1L) < totalPages;
    return new PageResponse<>(content, page, size, totalElements, totalPages, hasNext);
  }
}
