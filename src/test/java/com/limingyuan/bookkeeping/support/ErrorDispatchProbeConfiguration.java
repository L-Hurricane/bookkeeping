package com.limingyuan.bookkeeping.support;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.EnumSet;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/** Test-only marker. Production does not register this filter. */
@Configuration(proxyBeanMethods = false)
public class ErrorDispatchProbeConfiguration {
    public static final String HEADER = "X-Test-Error-Dispatch";

    @Bean
    FilterRegistrationBean<ErrorDispatchMarker> errorDispatchMarker() {
        var registration = new FilterRegistrationBean<>(new ErrorDispatchMarker());
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.ERROR));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    static final class ErrorDispatchMarker extends OncePerRequestFilter {
        @Override
        protected boolean shouldNotFilterErrorDispatch() {
            return false;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            response.setHeader(HEADER, request.getDispatcherType().name());
            chain.doFilter(request, response);
        }
    }
}
