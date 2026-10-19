package com.kbagent.web;

import com.kbagent.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 两级访问控制。
 *
 * 设计原则是"公开白名单 + 默认保护"：
 *   - 公开（访客可访问）：站点内容的只读接口、问答接口
 *   - 其余全部按管理员处理：文档管理、评测、站点内容的写操作
 *
 * 为什么用白名单而不是黑名单：新增接口时忘记加权限，最坏结果是"太严"（访问不了），
 * 而不是"太松"（被写了数据）。安全设计上，默认拒绝永远比默认放行安全。
 */
@Component
@Order(1)
public class AccessTokenFilter extends OncePerRequestFilter {

    /** 访客口令的 cookie 名。SSE 无法自定义请求头，所以必须支持 cookie。 */
    public static final String COOKIE_NAME = "kb_token";
    /** 管理员口令的 cookie 名。 */
    public static final String ADMIN_COOKIE_NAME = "kb_admin_token";

    private static final Logger log = LoggerFactory.getLogger(AccessTokenFilter.class);

    private final String visitorToken;
    private final String adminToken;

    public AccessTokenFilter(AppProperties properties) {
        this.visitorToken = properties.getSecurity().getAccessToken();
        this.adminToken = properties.getSecurity().getAdminToken();
        if (adminToken == null || adminToken.isBlank()) {
            log.warn("未配置 kb.security.admin-token，后台写接口处于无保护状态。"
                    + "本地开发没问题，部署到公网前请务必设置。");
        }
        if (visitorToken == null || visitorToken.isBlank()) {
            log.info("未配置 kb.security.access-token，站点对访客完全公开。");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        boolean adminOnly = !isPublic(uri, request.getMethod());
        String required = adminOnly ? adminToken : visitorToken;
        List<String> provided = resolveTokens(request);

        // 没配置要求的口令 = 这一级不设防
        if (required == null || required.isBlank() || containsMatch(provided, required)) {
            chain.doFilter(request, response);
            return;
        }
        reject(response, adminOnly ? "需要管理员口令" : "访问口令不正确或缺失");
    }

    /** 公开路径：站点内容只读、问答接口。其余一律按管理员处理。 */
    private boolean isPublic(String uri, String method) {
        if (uri.startsWith("/api/chat")) {
            return true;
        }
        return "GET".equalsIgnoreCase(method) && uri.startsWith("/api/lab/");
    }

    /** 请求头、cookie、查询参数三处都找一遍，兼容普通请求和 SSE。 */
    private List<String> resolveTokens(HttpServletRequest request) {
        List<String> tokens = new ArrayList<>(4);
        addIfPresent(tokens, request.getHeader("X-Access-Token"));
        addIfPresent(tokens, request.getHeader("X-Admin-Token"));
        addIfPresent(tokens, request.getParameter("token"));

        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE_NAME.equals(cookie.getName()) || ADMIN_COOKIE_NAME.equals(cookie.getName())) {
                    addIfPresent(tokens, cookie.getValue());
                }
            }
        }
        return tokens;
    }

    private void addIfPresent(List<String> tokens, String value) {
        if (value != null && !value.isBlank()) {
            tokens.add(value);
        }
    }

    /** 用恒定时间比较，避免通过响应时间差异逐字符猜口令。 */
    private boolean containsMatch(List<String> provided, String required) {
        byte[] expected = required.getBytes(StandardCharsets.UTF_8);
        for (String candidate : provided) {
            if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
