package com.remake.gone.common.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PageResponse}에 대한 단위 테스트.
 */
class PageResponseTest {

  @Test
  @DisplayName("size보다 결과가 많으면 해당 페이지분만 자르고 hasNext는 true다")
  void slicesFirstPageAndReportsHasNext() {
    PageResponse<Integer> response = PageResponse.of(List.of(1, 2, 3), 0, 2);

    assertThat(response.content()).containsExactly(1, 2);
    assertThat(response.totalElements()).isEqualTo(3);
    assertThat(response.totalPages()).isEqualTo(2);
    assertThat(response.hasNext()).isTrue();
  }

  @Test
  @DisplayName("마지막 페이지는 남은 항목만 반환하고 hasNext는 false다")
  void slicesLastPage() {
    PageResponse<Integer> response = PageResponse.of(List.of(1, 2, 3), 1, 2);

    assertThat(response.content()).containsExactly(3);
    assertThat(response.hasNext()).isFalse();
  }

  @Test
  @DisplayName("전체 결과가 없으면 content는 빈 리스트, totalPages는 0이다")
  void emptyAllContent() {
    PageResponse<Integer> response = PageResponse.of(List.of(), 0, 20);

    assertThat(response.content()).isEmpty();
    assertThat(response.totalElements()).isZero();
    assertThat(response.totalPages()).isZero();
    assertThat(response.hasNext()).isFalse();
  }

  @Test
  @DisplayName("마지막 페이지 다음 페이지를 요청하면 에러 없이 빈 content를 반환한다")
  void pageBeyondLastPageReturnsEmptyContent() {
    PageResponse<Integer> response = PageResponse.of(List.of(1, 2, 3), 5, 2);

    assertThat(response.content()).isEmpty();
    assertThat(response.hasNext()).isFalse();
    assertThat(response.totalElements()).isEqualTo(3);
    assertThat(response.totalPages()).isEqualTo(2);
  }

  @Test
  @DisplayName("page가 매우 커서 page*size가 int 오버플로를 일으켜도 예외 없이 빈 content를 반환한다")
  void veryLargePageDoesNotOverflow() {
    PageResponse<Integer> response = PageResponse.of(List.of(1, 2, 3), Integer.MAX_VALUE, 100);

    assertThat(response.content()).isEmpty();
    assertThat(response.hasNext()).isFalse();
    assertThat(response.totalElements()).isEqualTo(3);
  }
}
