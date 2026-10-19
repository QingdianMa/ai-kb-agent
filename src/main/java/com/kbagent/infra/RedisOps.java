package com.kbagent.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis 访问的统一封装，核心职责是**容错降级**。
 *
 * 为什么需要它：Redis 是外部依赖，它挂了不应该让整个网站挂掉。
 * 缓存读不到就当作未命中（照常调模型），限流执行不了就放行（记一条告警）。
 *
 * 这是"可用性优先"的取舍：宁可短时间失去缓存和限流，也不能让访客打不开页面。
 * 反面选择是"一致性/安全性优先"——比如支付系统，Redis 挂了就该拒绝服务。
 * 面试时被问到"依赖挂了怎么办"，答的就是这个取舍，而不是背"要做降级"。
 */
@Component
public class RedisOps {

    private static final Logger log = LoggerFactory.getLogger(RedisOps.class);

    private final StringRedisTemplate redis;
    /** 降级日志只打一次，否则每个请求都告警会把日志刷爆 */
    private final AtomicBoolean degradationLogged = new AtomicBoolean(false);

    public RedisOps(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(key));
        } catch (Exception e) {
            degrade("读取", e);
            return Optional.empty();
        }
    }

    public void set(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            degrade("写入", e);
        }
    }

    /** 执行 Lua 脚本。返回 null 表示 Redis 不可用，调用方按"降级"处理。 */
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
        try {
            return redis.execute(script, keys, args);
        } catch (Exception e) {
            degrade("执行脚本", e);
            return null;
        }
    }

    private void degrade(String action, Exception e) {
        if (degradationLogged.compareAndSet(false, true)) {
            log.warn("[Redis] {}失败，已降级为「不缓存、不限流」。原因: {}。"
                            + "本地开发没有 Redis 属正常现象；生产环境请检查 Redis 配置。",
                    action, e.getMessage());
        }
    }
}
