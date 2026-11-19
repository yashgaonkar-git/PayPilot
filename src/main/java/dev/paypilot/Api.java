package dev.paypilot;

import static dev.paypilot.Fields.*;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class Api {
    static final String COOKIE = "paypilot_java";
    private final ApplicationService service;
    private final AccessService access;
    private final boolean demo;
    private final boolean secure;
    private final String maintenanceNotice;
    private final Map<String, Window> limits = new LinkedHashMap<>();

    Api(ApplicationService service, AccessService access, @Value("${paypilot.demo}") boolean demo,
        @Value("${paypilot.secure-cookie}") boolean secure,
        @Value("${paypilot.maintenance-notice:}") String maintenanceNotice) {
        this.service = service;
        this.access = access;
        this.demo = demo;
        this.secure = secure;
        this.maintenanceNotice = maintenanceNotice;
    }

    @GetMapping("/config")
    Map<String, Object> config() {
        return fields("demo", demo, "maintenance_notice", maintenanceNotice, "audit_available", true);
    }

    @GetMapping("/state")
    Map<String, Object> state(@CookieValue(name = COOKIE, required = false) String raw) {
        return service.state(raw);
    }

    @GetMapping("/access")
    Object requests(@CookieValue(name = COOKIE, required = false) String raw) {
        return service.requests(raw);
    }

    @GetMapping("/audit")
    Object audit(@CookieValue(name = COOKIE, required = false) String raw) {
        return service.audit(raw);
    }

    @GetMapping("/attachment/{id}")
    ResponseEntity<byte[]> attachment(@CookieValue(name = COOKIE, required = false) String raw, @PathVariable long id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bill.pdf\"")
                .cacheControl(CacheControl.noStore()).body(service.attachment(raw, id));
    }

    @PostMapping(path = "/{action}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> post(@PathVariable String action,
                                           @CookieValue(name = COOKIE, required = false) String raw,
                                           @RequestBody Map<String, Object> body, HttpServletRequest request) {
        if ("null".equals(request.getHeader("Origin")) || !"1".equals(request.getHeader("X-PayPilot"))) {
            throw new Problem(403, "Invalid API request.");
        }
        String fetch = request.getHeader("Sec-Fetch-Site");
        if (fetch != null && !fetch.equals("same-origin")) throw new Problem(403, "Invalid request origin.");
        String origin = request.getHeader("Origin");
        if (origin != null && !sameOrigin(origin, request)) throw new Problem(403, "Invalid request origin.");
        String ip = request.getRemoteAddr();
        return switch (action) {
            case "challenge" -> {
                limit(ip + ":challenge", 90, Duration.ofMinutes(5));
                yield respond(access.challenge(), null);
            }
            case "demo" -> {
                if (!demo) throw new Problem(404, "Demo accounts are disabled.");
                limit(ip + ":demo", 20, Duration.ofHours(1));
                yield respond(Map.of(), service.demo());
            }
            case "register", "login", "reset" -> {
                limit(ip + ":" + action, action.equals("register") ? 12 : 40, Duration.ofMinutes(5));
                yield respond(switch (action) {
                    case "register" -> access.register(body);
                    case "login" -> access.login(body);
                    default -> access.reset(body);
                }, null);
            }
            case "verify-login", "verify-reset" -> {
                limit(ip + ":verify", 40, Duration.ofMinutes(5));
                var result = access.verify(body, action.equals("verify-reset"));
                yield respond(result.data(), result.session());
            }
            default -> {
                if (action.equals("password") || action.equals("pin")) limit(ip + ":credentials", 20, Duration.ofMinutes(5));
                yield respond(service.change(raw, action, body), action.equals("logout") ? "" : null);
            }
        };
    }

    private boolean sameOrigin(String origin, HttpServletRequest request) {
        try {
            var uri = java.net.URI.create(origin);
            if (!request.getScheme().equals(uri.getScheme()) || !request.getServerName().equalsIgnoreCase(uri.getHost())
                    || uri.getRawUserInfo() != null) return false;
            int port = uri.getPort() >= 0 ? uri.getPort() : uri.getScheme().equals("https") ? 443 : 80;
            return request.getServerPort() == port;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private synchronized void limit(String key, int cap, Duration period) {
        Instant now = Instant.now();
        limits.entrySet().removeIf(entry -> !entry.getValue().expires.isAfter(now));
        Window current = limits.get(key);
        if (current != null && current.hits >= cap) throw new Problem(429, "Too many attempts. Try again later.");
        if (current == null && limits.size() >= 4096) throw new Problem(429, "Too many attempts. Try again later.");
        limits.put(key, current == null ? new Window(1, now.plus(period)) : new Window(current.hits + 1, current.expires));
    }

    private ResponseEntity<Map<String, Object>> respond(Map<String, Object> body, String session) {
        var response = ResponseEntity.ok();
        if (session != null) {
            var cookie = ResponseCookie.from(COOKIE, session).httpOnly(true).secure(secure).sameSite("Strict")
                    .path("/").maxAge(session.isEmpty() ? Duration.ZERO : AccessService.SESSION_LENGTH).build();
            response.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return response.body(body);
    }

    @ExceptionHandler(Problem.class)
    ResponseEntity<Map<String, Object>> problem(Problem problem) {
        return ResponseEntity.status(problem.status).body(fields("error", problem.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, Object>> conflict() {
        return ResponseEntity.status(409).body(fields("error", "This request conflicts with an existing record."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> invalidJson() {
        return ResponseEntity.badRequest().body(fields("error", "Expected a valid JSON object."));
    }

    private record Window(int hits, Instant expires) {}
}
