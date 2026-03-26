package com.lending.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caching layer using Redis for loan products and credit scores.
 * Falls back to in-memory cache when Redis is unavailable.
 */
@Service
@Slf4j
public class LoanCacheService {

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper;

    @Value("${lending.redis.enabled:false}")
    private boolean redisEnabled;

    @Value("${lending.redis.ttl-minutes:30}")
    private int ttlMinutes;

    private final Map<String, String> localCache = new ConcurrentHashMap<>();

    public LoanCacheService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void put(String key, Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            if (redisEnabled && redisTemplate != null) {
                redisTemplate.opsForValue().set(key, json, Duration.ofMinutes(ttlMinutes));
            } else {
                localCache.put(key, json);
            }
        } catch (Exception e) {
            log.warn("Cache put failed for key {}: {}", key, e.getMessage());
        }
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        try {
            String json;
            if (redisEnabled && redisTemplate != null) {
                json = redisTemplate.opsForValue().get(key);
            } else {
                json = localCache.get(key);
            }
            if (json != null) {
                return Optional.of(objectMapper.readValue(json, type));
            }
        } catch (Exception e) {
            log.warn("Cache get failed for key {}: {}", key, e.getMessage());
        }
        return Optional.empty();
    }

    public void evict(String key) {
        if (redisEnabled && redisTemplate != null) {
            redisTemplate.delete(key);
        } else {
            localCache.remove(key);
        }
    }

    public void putCreditScore(String customerId, int score) {
        put("credit_score:" + customerId, score);
    }

    public Optional<Integer> getCreditScore(String customerId) {
        return get("credit_score:" + customerId, Integer.class);
    }
}
