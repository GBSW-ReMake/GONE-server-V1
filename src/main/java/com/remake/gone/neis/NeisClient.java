package com.remake.gone.neis;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.neis.config.NeisProperties;
import com.remake.gone.neis.exception.NeisErrorCode;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 나이스(NEIS) 오픈 API 호출과 응답 파싱을 캡슐화하는 클라이언트.
 *
 * <p>NEIS 응답은 세 가지 형태로 갈린다(기획서 참고): 정상(데이터 있음), 정상(데이터 없음,
 * {@code RESULT.CODE}가 {@code INFO-}로 시작), 진짜 에러({@code RESULT.CODE}가 {@code ERROR-}
 * 로 시작). 이 세 가지를 구분하는 로직을 여기 한 곳에 모아, {@code meal}/{@code timetable}
 * 서비스는 이 복잡함을 몰라도 되게 한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NeisClient {

  private static final int PAGE_SIZE = 100;

  private final RestClient neisRestClient;
  private final NeisProperties neisProperties;
  private final ObjectMapper objectMapper;

  /**
   * NEIS API를 호출하고, 실제 데이터 행({@code row})만 지정한 타입으로 변환해 반환한다.
   *
   * @param path        NEIS API 경로(예: {@code /mealServiceDietInfo})
   * @param params      학교 코드/인증키 외에 추가로 필요한 쿼리 파라미터
   * @param resourceKey 정상 응답의 최상위 키(예: {@code mealServiceDietInfo}, {@code hisTimetable})
   * @param rowType     각 행을 변환할 대상 타입
   * @param <T>         반환 리스트 요소 타입
   * @return 조회된 행 목록. 데이터가 없는 날(주말/방학 등)은 빈 리스트
   */
  public <T> List<T> fetch(
      String path, Map<String, String> params, String resourceKey, Class<T> rowType) {
    JsonNode root;
    try {
      root = neisRestClient.get()
          .uri(uriBuilder -> buildUri(uriBuilder, path, params))
          .retrieve()
          .body(JsonNode.class);
    } catch (RestClientException e) {
      log.error("NEIS API 호출 실패: path={}", path, e);
      throw new CustomException(NeisErrorCode.EXTERNAL_API_ERROR);
    }
    return parseRows(root, resourceKey, rowType);
  }

  private URI buildUri(UriBuilder uriBuilder, String path, Map<String, String> params) {
    uriBuilder.path(path)
        .queryParam("KEY", neisProperties.apiKey())
        .queryParam("Type", "json")
        .queryParam("pIndex", 1)
        .queryParam("pSize", PAGE_SIZE)
        .queryParam("ATPT_OFCDC_SC_CODE", neisProperties.atptOfcdcScCode())
        .queryParam("SD_SCHUL_CODE", neisProperties.sdSchulCode());
    params.forEach(uriBuilder::queryParam);
    return uriBuilder.build();
  }

  private <T> List<T> parseRows(JsonNode root, String resourceKey, Class<T> rowType) {
    JsonNode resultNode = root.path("RESULT");
    if (!resultNode.isMissingNode()) {
      String code = resultNode.path("CODE").asText("");
      if (code.startsWith("INFO-")) {
        log.info(
            "NEIS API 데이터 없음(정상): resourceKey={}, code={}, message={}",
            resourceKey, code, resultNode.path("MESSAGE").asText(""));
        return List.of();
      }
      log.error(
          "NEIS API 오류 응답: code={}, message={}", code, resultNode.path("MESSAGE").asText(""));
      throw new CustomException(NeisErrorCode.EXTERNAL_API_ERROR);
    }

    List<T> rows = new ArrayList<>();
    for (JsonNode rowNode : root.path(resourceKey).path(1).path("row")) {
      rows.add(objectMapper.convertValue(rowNode, rowType));
    }
    return rows;
  }
}
