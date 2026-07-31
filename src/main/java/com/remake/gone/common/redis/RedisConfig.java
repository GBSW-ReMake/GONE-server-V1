package com.remake.gone.common.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 연동 설정.
 *
 * <p>키와 값을 모두 사람이 읽을 수 있는 문자열/JSON 형태로 직렬화하는 {@link RedisTemplate}을 등록한다.
 */
@Configuration
public class RedisConfig {

  /**
   * 값 직렬화에 사용할 JSON 직렬화기를 생성한다.
   *
   * <p>{@link RedisRepository}에서 타입 지정 조회 시에도 동일한 직렬화기를 재사용한다.
   *
   * @return JSON 기반 값 직렬화기
   */
  @Bean
  public GenericJacksonJsonRedisSerializer redisValueSerializer() {
    return GenericJacksonJsonRedisSerializer.builder().build();
  }

  /**
   * 키는 문자열로, 값은 JSON으로 직렬화하는 {@link RedisTemplate}을 생성한다.
   *
   * @param connectionFactory Redis 커넥션 팩토리
   * @param valueSerializer   값 직렬화에 사용할 JSON 직렬화기
   * @return 설정이 완료된 {@link RedisTemplate}
   */
  @Bean
  public RedisTemplate<String, Object> redisTemplate(
      RedisConnectionFactory connectionFactory,
      GenericJacksonJsonRedisSerializer valueSerializer) {
    StringRedisSerializer keySerializer = new StringRedisSerializer();

    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(keySerializer);
    template.setHashKeySerializer(keySerializer);
    template.setValueSerializer(valueSerializer);
    template.setHashValueSerializer(valueSerializer);

    return template;
  }
}
