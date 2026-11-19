package dev.paypilot;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class ResponseHeaders extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; "
                + "img-src 'self' data:; form-action 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'");
        if (request.getRequestURI().startsWith("/api/") && request.getContentLengthLong() > 4_300_000) {
            response.setStatus(413);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid request size.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
