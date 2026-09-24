package com.limingyuan.bookkeeping.identity.security;

import com.limingyuan.bookkeeping.common.api.*;
import com.limingyuan.bookkeeping.identity.application.AuthenticationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
public class IdentitySecurityConfiguration {
    @Bean
    SecurityFilterChain identitySecurityFilterChain(HttpSecurity http, AuthenticationService authentication,
            ApiErrors errors) throws Exception {
        // Filters belong only to this chain, not the servlet container's automatic filter registration.
        var sessionFilter = new SessionAuthenticationFilter(authentication, errors);
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                // Tokens are accepted only from Authorization, never ambient cookies or HTTP Basic.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) ->
                                errors.write(request, response, 401, "SESSION_INVALID", "会话无效或已过期"))
                        .accessDeniedHandler((request, response, ex) ->
                                errors.write(request, response, 403, "OPERATION_FORBIDDEN", "不允许此操作")))
                .addFilterBefore(sessionFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new RequestIdFilter(), SessionAuthenticationFilter.class)
                .build();
    }
}
