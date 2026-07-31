package com.remake.gone.common.redis;

import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Repository;

/**
 * Redis 저장/조회/삭제를 감싸는 간단한 래퍼.
 *
 * <p>키 접두사와 TTL은 {@link RedisKeyType}에 정의된 값을 사용하며,
 * 값은 어떤 타입의 DTO든 JSON으로 저장하고 원하는 타입으로 꺼내올 수 있다.
 */
@Repository
@RequiredArgsConstructor
public class RedisRepository {

  private static final RedisSerializer<String> KEY_SERIALIZER = new StringRedisSerializer();

  private final RedisTemplate<String, Object> redisTemplate;
  private final GenericJacksonJsonRedisSerializer valueSerializer;

  /**
   * 값을 저장하고 {@link RedisKeyType}에 정의된 TTL을 적용한다.
   *
   * @param keyType    키 타입
   * @param identifier 키를 구성할 식별자
   * @param value      저장할 값 (임의의 DTO 가능)
   */
  public void save(RedisKeyType keyType, String identifier, Object value) {
    redisTemplate.opsForValue().set(keyType.key(identifier), value, keyType.ttl());
  }

  /**
   * 저장된 값을 지정한 타입으로 조회한다.
   *
   * @param keyType    키 타입
   * @param identifier 키를 구성할 식별자
   * @param type       반환받을 값의 타입
   * @param <T>        반환받을 값의 타입
   * @return 저장된 값, 없으면 {@code null}
   */
  public <T> T find(RedisKeyType keyType, String identifier, Class<T> type) {
    byte[] rawKey = KEY_SERIALIZER.serialize(keyType.key(identifier));
    byte[] rawValue = redisTemplate.execute(
        (RedisCallback<byte[]>) connection -> connection.stringCommands().get(rawKey));
    return rawValue == null ? null : valueSerializer.deserialize(rawValue, type);
  }

  /**
   * 저장된 값을 삭제한다.
   *
   * @param keyType    키 타입
   * @param identifier 키를 구성할 식별자
   */
  public void delete(RedisKeyType keyType, String identifier) {
    redisTemplate.delete(keyType.key(identifier));
  }

  /**
   * 값을 1 증가시키고, 최초 생성 시에만 TTL을 적용한다.
   *
   * @param keyType    키 타입
   * @param identifier 키를 구성할 식별자
   * @return 증가된 이후의 값
   */
  public Long increment(RedisKeyType keyType, String identifier) {
    String key = keyType.key(identifier);
    Long count = redisTemplate.opsForValue().increment(key);
    if (count != null && count == 1L) {
      redisTemplate.expire(key, keyType.ttl());
    }
    return count;
  }

  /**
   * 키가 존재하지 않을 때만 저장한다 (원자적 연산). 쿨다운, 락 등에 사용.
   *
   * @param keyType    키 타입
   * @param identifier 키를 구성할 식별자
   * @return 저장에 성공하면 true, 이미 존재해서 실패하면 false
   */
  public boolean saveIfAbsent(RedisKeyType keyType, String identifier) {
    Boolean success = redisTemplate.opsForValue()
        .setIfAbsent(keyType.key(identifier), "", keyType.ttl());
    return Boolean.TRUE.equals(success);
  }

  /**
   * 키의 남은 TTL을 초 단위로 조회한다.
   *
   * @param keyType    키 타입
   * @param identifier 키를 구성할 식별자
   * @return 남은 TTL(초), 키가 없거나 TTL이 없으면 0
   */
  public long getExpire(RedisKeyType keyType, String identifier) {
    Long seconds = redisTemplate.getExpire(keyType.key(identifier), TimeUnit.SECONDS);
    return seconds == null || seconds < 0 ? 0 : seconds;
  }
}