package com.limingyuan.bookkeeping.common.api;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApiErrors {
    public record ErrorBody(String code, String message, String requestId) { }
    public record ErrorResponse(ErrorBody error) { }
    private final ObjectMapper mapper;

    public ApiErrors(ObjectMapper mapper) { this.mapper = mapper; }

    public ErrorResponse body(HttpServletRequest request, String code, String message) {
        return new ErrorResponse(new ErrorBody(code, message, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }

    public void write(HttpServletRequest request, HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(mapper.writeValueAsString(body(request, code, message)));
    }
}
