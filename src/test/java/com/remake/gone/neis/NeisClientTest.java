package com.remake.gone.neis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.neis.config.NeisProperties;
import com.remake.gone.neis.dto.NeisMealRow;
import com.remake.gone.neis.exception.NeisErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link NeisClient}에 대한 단위 테스트. NEIS 응답의 세 가지 형태(정상/빈 데이터/에러)를
 * {@link MockRestServiceServer}로 흉내 내어 검증한다.
 */
class NeisClientTest {

  private MockRestServiceServer mockServer;
  private NeisClient neisClient;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("https://open.neis.go.kr/hub");
    mockServer = MockRestServiceServer.bindTo(builder).build();
    NeisProperties properties = new NeisProperties("test-key", "R10", "8750829");
    neisClient = new NeisClient(builder.build(), properties, new ObjectMapper());
  }

  @Test
  @DisplayName("정상 응답(데이터 있음)이면 row를 지정한 타입으로 변환해 반환한다")
  void returnsRowsOnSuccess() {
    String body = """
        {"mealServiceDietInfo":[
          {"head":[{"list_total_count":1},{"RESULT":{"CODE":"INFO-000","MESSAGE":"정상 처리되었습니다."}}]},
          {"row":[{"MMEAL_SC_NM":"중식","DDISH_NM":"밥<br/>국","CAL_INFO":"500 Kcal"}]}
        ]}
        """;
    mockServer.expect(requestTo(containsString("/mealServiceDietInfo")))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

    List<NeisMealRow> rows = neisClient.fetch(
        "/mealServiceDietInfo", Map.of("MLSV_YMD", "20260810"),
        "mealServiceDietInfo", NeisMealRow.class);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).mealTypeName()).isEqualTo("중식");
    assertThat(rows.get(0).dishName()).isEqualTo("밥<br/>국");
  }

  @Test
  @DisplayName("데이터 없음(INFO-200)이면 에러가 아니라 빈 리스트를 반환한다")
  void returnsEmptyListWhenNoData() {
    String body = """
        {"RESULT":{"CODE":"INFO-200","MESSAGE":"해당하는 데이터가 없습니다."}}
        """;
    mockServer.expect(requestTo(containsString("/mealServiceDietInfo")))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

    List<NeisMealRow> rows = neisClient.fetch(
        "/mealServiceDietInfo", Map.of("MLSV_YMD", "20260805"),
        "mealServiceDietInfo", NeisMealRow.class);

    assertThat(rows).isEmpty();
  }

  @Test
  @DisplayName("진짜 에러(ERROR-*)면 NeisErrorCode.EXTERNAL_API_ERROR를 던진다")
  void throwsOnRealError() {
    String body = """
        {"RESULT":{"CODE":"ERROR-290","MESSAGE":"인증키가 유효하지 않습니다."}}
        """;
    mockServer.expect(requestTo(containsString("/mealServiceDietInfo")))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> neisClient.fetch(
        "/mealServiceDietInfo", Map.of("MLSV_YMD", "20260810"),
        "mealServiceDietInfo", NeisMealRow.class))
        .isInstanceOf(CustomException.class)
        .extracting(e -> ((CustomException) e).getErrorCode())
        .isEqualTo(NeisErrorCode.EXTERNAL_API_ERROR);
  }

  @Test
  @DisplayName("네트워크/서버 오류면 NeisErrorCode.EXTERNAL_API_ERROR를 던진다")
  void throwsOnServerError() {
    mockServer.expect(requestTo(containsString("/mealServiceDietInfo")))
        .andRespond(withServerError());

    assertThatThrownBy(() -> neisClient.fetch(
        "/mealServiceDietInfo", Map.of("MLSV_YMD", "20260810"),
        "mealServiceDietInfo", NeisMealRow.class))
        .isInstanceOf(CustomException.class)
        .extracting(e -> ((CustomException) e).getErrorCode())
        .isEqualTo(NeisErrorCode.EXTERNAL_API_ERROR);
  }
}
