package com.example.agent.business.security.config;

import com.example.agent.business.security.permission.BusinessPermissions;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 业务服务的 HTTP 认证与接口授权边界。
 *
 * 认证确认当前用户身份。
 * 授权根据用户持有的权限码检查接口访问资格。
 */
@Configuration(proxyBeanMethods = false)
public class BusinessSecurityConfiguration {

    private final ObjectMapper objectMapper;

    public BusinessSecurityConfiguration(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(
            UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {

        // 明确使用本系统配置的账号来源和密码编码器。
        DaoAuthenticationProvider provider =
                new DaoAuthenticationProvider(userDetailsService);

        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            DaoAuthenticationProvider provider) throws Exception {

        http
                .authenticationProvider(provider)

                // Cookie + Session 场景保留 CSRF。
                .csrf(Customizer.withDefaults())

                // 同源开发代理，无需配置跨域。
                .httpBasic(AbstractHttpConfigurer::disable)

                // 前端管理登录页面，不保存登录前的业务请求。
                .requestCache(AbstractHttpConfigurer::disable)

                .sessionManagement(session -> session
                        .sessionCreationPolicy(
                                SessionCreationPolicy.IF_REQUIRED
                        )
                        .sessionFixation(fixation ->
                                fixation.changeSessionId()
                        )
                )

                .authorizeHttpRequests(authorize -> authorize

                        // 首次 REQUEST 仍须通过下面的规则。
                        // 允许现有 SSE 异步派发和错误派发继续完成。
                        .dispatcherTypeMatchers(
                                DispatcherType.ASYNC,
                                DispatcherType.ERROR
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/auth/csrf",
                                "/actuator/health"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/auth/login"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/auth/me",
                                "/actuator/info"
                        ).authenticated()

                                // Agent 聊天入口：要求持有 Agent 调用权限。
                                .requestMatchers(
                                        HttpMethod.POST,
                                        "/api/chat/chat"
                                ).hasAuthority(
                                        BusinessPermissions.CHAT_AGENT_INVOKE
                                )

// 工作流聊天入口：要求持有工作流调用权限。
                                .requestMatchers(
                                        HttpMethod.POST,
                                        "/api/chat/dify"
                                ).hasAuthority(
                                        BusinessPermissions.CHAT_WORKFLOW_INVOKE
                                )

                        // 新接口必须显式登记。
                        .anyRequest().denyAll()
                )

                .formLogin(form -> form
                        // 页面由前端提供，不生成默认 HTML 登录页。
                        .loginPage("/login")
                        .loginProcessingUrl("/api/auth/login")
                        .usernameParameter("username")
                        .passwordParameter("password")

                        .successHandler((request, response, authentication) ->
                                response.setStatus(
                                        HttpServletResponse.SC_NO_CONTENT
                                )
                        )

                        .failureHandler((request, response, exception) -> {
                            // 数据库读取等认证服务异常，不能误报成用户密码错误。
                            if (exception instanceof AuthenticationServiceException) {
                                writeProblem(
                                        response,
                                        503,
                                        "AUTH_SERVICE_UNAVAILABLE",
                                        "登录服务暂时不可用，请稍后重试"
                                );
                                return;
                            }

                            // 错误密码、未知账号和停用账号统一返回登录失败。
                            writeProblem(
                                    response,
                                    401,
                                    "LOGIN_FAILED",
                                    "用户名或密码错误"
                            );
                        })
                )

                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(
                                        HttpServletResponse.SC_NO_CONTENT
                                )
                        )
                )

                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(
                                (request, response, exception) ->
                                        writeProblem(
                                                response,
                                                401,
                                                "UNAUTHENTICATED",
                                                "请先登录"
                                        )
                        )
                        .accessDeniedHandler(
                                (request, response, exception) -> {
                                    if (exception instanceof CsrfException) {
                                        writeProblem(
                                                response,
                                                403,
                                                "CSRF_INVALID",
                                                "安全令牌缺失或已失效"
                                        );
                                        return;
                                    }

                                    writeProblem(
                                            response,
                                            403,
                                            "FORBIDDEN",
                                            "当前请求不允许访问"
                                    );
                                }
                        )
                );

        return http.build();
    }

    /**
     * 安全过滤器在 Controller 之前执行，
     * 这里直接输出错误，不依赖 ControllerAdvice。
     */
    private void writeProblem(
            HttpServletResponse response,
            int status,
            String code,
            String detail) throws IOException {

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatusCode.valueOf(status),
                        detail
                );

        problem.setProperty("code", code);

        response.setStatus(status);
        response.setCharacterEncoding(
                StandardCharsets.UTF_8.name()
        );
        response.setContentType(
                MediaType.APPLICATION_PROBLEM_JSON_VALUE
        );
        response.setHeader("Cache-Control", "no-store");

        objectMapper.writeValue(
                response.getOutputStream(),
                problem
        );
    }
}