package com.kbagent.web;

import com.kbagent.config.AppProperties;
import com.kbagent.infra.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 给问答接口加限流。
 * 只保护 /api/chat/**，因为只有它会产生大模型调用成本；
 * 读站点内容是纯数据库查询，不值得为它增加复杂度。
 */
@Component
@Order(2)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final AppProperties properties;

    public RateLimitFilter(RateLimiter rateLimiter, AppProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/chat")) {
            chain.doFilter(request, response);
            return;
        }
        if (rateLimiter.allow(clientIp(request))) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"提问太频繁了，请稍等一会儿再试\"}");
    }

    /**
     * 取客户端 IP。
     *
     * 注意 X-Forwarded-For：这个头是客户端可以伪造的。只有当服务确实部署在可信代理
     * （比如 Nginx）后面时才能信它，否则攻击者随便填个值就能绕开限流。
     * 所以我们默认只信 TCP 连接的来源地址，需要时才在配置里打开。
     */
    private String clientIp(HttpServletRequest request) {
        if (Boolean.TRUE.equals(properties.getRateLimit().getTrustForwardedHeader())) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
