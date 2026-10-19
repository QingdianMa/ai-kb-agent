package com.kbagent.infra;

import com.kbagent.config.AppProperties;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 基于 Redis 的滑动窗口限流。
 *
 * 为什么站点公开就必须限流：接口一旦开放，任何人扫到端口都能刷你的大模型额度。
 * 这不是"可能被攻击"，是"上线当天就会发生"——爬虫和扫描器是自动运行的。
 *
 * 为什么用滑动窗口而不是"每分钟计数器"：
 * 固定窗口在边界处会放过双倍流量——比如限制每分钟 10 次，第 59 秒来 10 次、
 * 第 61 秒再来 10 次，两秒内就放过了 20 次。滑动窗口记录每次请求的时间戳，
 * 任意时刻都只看"过去 60 秒"，没有这个漏洞。
 *
 * 为什么必须用 Lua 脚本：
 * "清理过期记录 → 计数 → 判断 → 写入"这四步如果分开执行，
 * 并发请求会交错执行，导致计数不准（限流形同虚设）。
 * Redis 执行 Lua 脚本是原子的，四步不会被其他命令打断。
 */
@Component
public class RateLimiter {

    private static final String SCRIPT = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]

            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            local count = redis.call('ZCARD', key)
            if count >= limit then
                return 0
            end
            redis.call('ZADD', key, now, member)
            redis.call('EXPIRE', key, math.ceil(window / 1000) + 1)
            return 1
            """;

    private static final RedisScript<Long> RATE_LIMIT_SCRIPT = RedisScript.of(SCRIPT, Long.class);

    private final RedisOps redisOps;
    private final AppProperties properties;

    public RateLimiter(RedisOps redisOps, AppProperties properties) {
        this.redisOps = redisOps;
        this.properties = properties;
    }

    public boolean allow(String identifier) {
        if (!Boolean.TRUE.equals(properties.getRateLimit().getEnabled())) {
            return true;
        }
        long now = System.currentTimeMillis();
        long windowMillis = properties.getRateLimit().getWindowSeconds() * 1000L;

        // member 用「时间戳-随机数」，避免同一毫秒的两个请求互相覆盖
        String member = now + "-" + ThreadLocalRandom.current().nextInt(1_000_000);
        Long result = redisOps.execute(RATE_LIMIT_SCRIPT,
                List.of("kb:rl:" + identifier),
                String.valueOf(now),
                String.valueOf(windowMillis),
                String.valueOf(properties.getRateLimit().getLimit()),
                member);

        // Redis 不可用时放行：可用性优先。限流失效只是少了保护，服务本身不该挂掉
        return result == null || result == 1L;
    }
}
