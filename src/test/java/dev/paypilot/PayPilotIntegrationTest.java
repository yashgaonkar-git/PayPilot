package dev.paypilot;

import static dev.paypilot.Fields.fields;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:paypilot-tests;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "paypilot.worker.enabled=false"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PayPilotIntegrationTest {
    private static final String ADMIN_EMAIL = "integration-admin@example.test";
    private static final String PASSWORD = "Test password example";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccessService access;
    @Autowired TestDelivery delivery;

    @DynamicPropertySource
    static void keyFile(DynamicPropertyRegistry registry) {
        registry.add("paypilot.key-file", () -> Path.of(System.getProperty("java.io.tmpdir"),
                "paypilot-java-tests-" + UUID.randomUUID(), "secret.key").toString());
    }

    @BeforeAll
    void admin() {
        access.bootstrap("integration-admin", ADMIN_EMAIL, PASSWORD);
    }

    @Test
    void paymentDeclineRetryAndDueScheduleUseTheHibernateLedger() throws Exception {
        Cookie session = demo();
        JsonNode before = state(session);
        JsonNode bill = named(before.path("bills"), "Electricity");
        long id = bill.path("id").asLong();
        assertThat(bill.path("balance").asLong()).isEqualTo(130000);
        Map<String, Object> request = fields("ids", List.of(id), "method", "Bank transfer",
                "institution", "Demo Bank", "outcome", "Decline", "request_id", "decline");
        JsonNode failed = body(send("pay", request, session).andExpect(status().isOk()));
        assertThat(failed.at("/attempts/0/status").asText()).isEqualTo("Declined");
        assertThat(named(state(session).path("bills"), "Electricity").path("balance").asLong()).isEqualTo(130000);
        request.put("outcome", "Approve");
        request.put("request_id", "retry");
        JsonNode settled = body(send("pay", request, session).andExpect(status().isOk()));
        assertThat(settled.at("/attempts/0/status").asText()).isEqualTo("Settled");
        assertThat(body(send("pay", request, session).andExpect(status().isOk()))).isEqualTo(settled);

        long plan = before.at("/schedules/0/id").asLong();
        send("schedule", fields("id", plan, "day", LocalDate.now().toString()), session).andExpect(status().isOk());
        send("refresh", Map.of(), session).andExpect(status().isOk());
        send("refresh", Map.of(), session).andExpect(status().isOk());
        JsonNode after = state(session);
        assertThat(after.at("/schedules/0/status").asText()).isEqualTo("Completed");
        assertThat(after.path("payment_attempts").size()).isEqualTo(3);
        assertThat(named(after.path("bills"), "Electricity").path("balance").asLong()).isZero();
        assertThat(state(demo()).path("payment_attempts").isEmpty()).isTrue();
    }

    @Test
    void billPdfRecurrenceAndContactReminderStayScopedToTheOwner() throws Exception {
        Cookie session = demo();
        LocalDate today = LocalDate.now();
        byte[] pdf = "%PDF-1.4\nSynthetic fixture\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        var bill = fields("name", "Utility fixture", "company", "City Power", "category", "Utilities",
                "amount", "42.50", "due", today.plusDays(4).toString(), "priority", "Normal",
                "payment_frequency", "Monthly", "reminder_frequency", "Daily", "reminder_start", today.toString(),
                "reminder_message", "Utility fixture is due soon.", "channel", "Contact", "remind_recurring", true,
                "attachment", Base64.getEncoder().encodeToString(pdf), "filename", "fixture.pdf");
        long id = body(send("bill", bill, session).andExpect(status().isOk())).path("id").asLong();
        send("refresh", Map.of(), session).andExpect(status().isOk());
        send("refresh", Map.of(), session).andExpect(status().isOk());
        JsonNode state = state(session);
        assertThat(state.path("bills").size()).isEqualTo(10);
        assertThat(state.path("notifications").size()).isEqualTo(2);
        assertThat(state.at("/notifications/0/delivery").asText()).isEqualTo(Fields.SMS_PREVIEW);
        mvc.perform(get("/api/attachment/{id}", id).cookie(session)).andExpect(status().isOk())
                .andExpect(content().bytes(pdf));
        mvc.perform(get("/api/attachment/{id}", id).cookie(demo())).andExpect(status().isNotFound());
        send("archive", fields("ids", List.of(id)), session).andExpect(status().isOk());
        mvc.perform(get("/api/attachment/{id}", id).cookie(session)).andExpect(status().isNotFound());
    }

    @Test
    void accountApprovalLockoutRecoveryAndAudit() throws Exception {
        String username = "integration-user";
        String email = "integration-user@example.test";
        var request = fields("username", username, "email", email, "password", PASSWORD, "confirm", PASSWORD,
                "pan", "ABCDE1234F", "account", "123456789", "ifsc", "DEMO0123456", "bank", "Demo Bank");
        send("register", request, null).andExpect(status().isOk());
        send("register", request, null).andExpect(status().isConflict());
        login(username, PASSWORD).andExpect(status().isForbidden());
        Cookie admin = verifyLogin("integration-admin", ADMIN_EMAIL, PASSWORD);
        mvc.perform(get("/api/audit").cookie(demo())).andExpect(status().isForbidden());
        long id = body(mvc.perform(get("/api/access").cookie(admin)).andExpect(status().isOk())).get(0).path("id").asLong();
        send("access", fields("id", id, "status", "approved"), admin).andExpect(status().isOk());
        mvc.perform(get("/api/audit").cookie(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("access.approved"));
        login(username, "wrong").andExpect(status().isUnauthorized());
        login(username, "wrong").andExpect(status().isUnauthorized());
        login(username, "wrong").andExpect(status().isLocked());

        JsonNode reset = body(send("reset", fields("username", username, "email", email), null).andExpect(status().isOk()));
        String otp = capturedCode(email);
        String updated = "Updated test password";
        send("verify-reset", fields("token", reset.path("token").asText(), "code", otp,
                "password", updated, "confirm", updated), null).andExpect(status().isOk());
        JsonNode challenge = body(login(username, updated).andExpect(status().isOk()));
        var verification = fields("token", challenge.path("token").asText(), "code", capturedCode(email));
        Cookie session = send("verify-login", verification, null).andExpect(status().isOk()).andReturn().getResponse().getCookie(Api.COOKIE);
        assertThat(state(session).at("/user/username").asText()).isEqualTo(username);
        send("verify-login", verification, null).andExpect(status().isUnauthorized());
    }

    @Test
    void controllerServesTheSharedFrontendAndRejectsCrossOriginWrites() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(view().name("forward:index.html"));
        mvc.perform(get("/app.js")).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(post("/api/demo").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/demo").contentType(MediaType.APPLICATION_JSON).content("{}")
                .header("X-PayPilot", "1").header("Origin", "http://other.example"))
                .andExpect(status().isForbidden());
    }

    private Cookie demo() throws Exception {
        Cookie session = send("demo", Map.of(), null).andExpect(status().isOk()).andReturn().getResponse().getCookie(Api.COOKIE);
        assertThat(session).isNotNull();
        return session;
    }

    private ResultActions login(String username, String password) throws Exception {
        JsonNode challenge = body(send("challenge", Map.of(), null).andExpect(status().isOk()));
        String prompt = challenge.path("prompt").asText();
        String answer = "";
        for (JsonNode symbol : challenge.path("shapes")) {
            if (challenge.path("names").path(symbol.asText()).asText().equals(prompt)) answer = symbol.asText();
        }
        return send("login", fields("username", username, "password", password, "challenge", challenge.path("token").asText(),
                "answer", answer), null);
    }

    private Cookie verifyLogin(String user, String email, String password) throws Exception {
        JsonNode result = body(login(user, password).andExpect(status().isOk()));
        return send("verify-login", fields("token", result.path("token").asText(), "code", capturedCode(email)), null)
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(Api.COOKIE);
    }

    private String capturedCode(String email) {
        String code = delivery.codes.get(email);
        assertThat(code).isNotNull();
        return code;
    }

    private JsonNode state(Cookie session) throws Exception {
        return body(mvc.perform(get("/api/state").cookie(session)).andExpect(status().isOk()));
    }

    private JsonNode named(JsonNode rows, String name) {
        for (JsonNode row : rows) {
            if (row.path("name").asText().equals(name)) return row;
        }
        throw new AssertionError("Bill not found: " + name);
    }

    private ResultActions send(String path, Map<String, Object> content, Cookie cookie) throws Exception {
        var request = post("/api/" + path).contentType(MediaType.APPLICATION_JSON)
                .header("X-PayPilot", "1").content(json.writeValueAsBytes(content));
        if (cookie != null) request.cookie(cookie);
        return mvc.perform(request);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class TestMail {
        @Bean
        @Primary
        TestDelivery testDelivery(ObjectProvider<JavaMailSender> mail) {
            return new TestDelivery(mail);
        }
    }

    static class TestDelivery extends Delivery {
        final Map<String, String> codes = new java.util.HashMap<>();

        TestDelivery(ObjectProvider<JavaMailSender> mail) {
            super(mail, "", true);
        }

        @Override
        void verification(String email, String code) {
            codes.put(email, code);
        }
    }
}
