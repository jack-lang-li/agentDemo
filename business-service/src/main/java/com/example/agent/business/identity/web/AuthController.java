package com.example.agent.business.identity.web;

import com.example.agent.business.identity.api.CurrentCallerProvider;
import com.example.agent.common.core.context.CallerContext;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供前端认证初始化所需的查询接口。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final CurrentCallerProvider currentCallerProvider;

    public AuthController(
            CurrentCallerProvider currentCallerProvider) {
        this.currentCallerProvider = currentCallerProvider;
    }

    @GetMapping("/csrf")
    public ResponseEntity<CsrfResponse> csrf(CsrfToken csrfToken) {
        // 访问 token 会触发延迟 Token 的生成与保存。
        CsrfResponse body = new CsrfResponse(
                csrfToken.getHeaderName(),
                csrfToken.getToken()
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    @GetMapping("/me")
    public ResponseEntity<CallerContext> me() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(currentCallerProvider.requireCurrentCaller());
    }

    public record CsrfResponse(
            String headerName,
            String token) {
    }
}