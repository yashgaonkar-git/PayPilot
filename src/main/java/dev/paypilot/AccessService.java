package dev.paypilot;

import static dev.paypilot.Fields.*;
import static dev.paypilot.Models.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AccessService {
    static final Duration SESSION_LENGTH = Duration.ofHours(8);
    private static final Map<String, String> SHAPES = Map.of("◆", "diamond", "●", "circle", "■", "square",
            "▲", "triangle", "★", "star", "♥", "heart");
    private final Store store;
    private final Vault vault;
    private final Delivery delivery;
    private final String dummyPassword;

    AccessService(Store store, Vault vault, Delivery delivery) {
        this.store = store;
        this.vault = vault;
        this.delivery = delivery;
        this.dummyPassword = vault.hashPassword(vault.token());
    }

    private AuthToken createToken(String raw, Account account, String purpose, String verifier, Duration lifetime) {
        var token = new AuthToken();
        token.id = vault.fingerprint(purpose, raw);
        token.owner = account;
        token.purpose = purpose;
        token.verifier = verifier;
        token.expires = Instant.now().plus(lifetime);
        store.save(token);
        return token;
    }

    @Transactional
    public Map<String, Object> challenge() {
        List<String> keys = new ArrayList<>(SHAPES.keySet());
        Collections.shuffle(keys);
        List<String> choices = List.copyOf(keys.subList(0, 3));
        String answer = choices.get(vault.draw(choices.size()));
        String raw = vault.token();
        createToken(raw, null, "challenge", answer, Duration.ofMinutes(5));
        return fields("token", raw, "shapes", choices, "names", SHAPES, "prompt", SHAPES.get(answer));
    }

    String username(Map<String, ?> data) {
        String username = text(data, "username", 40, true).toLowerCase(Locale.ROOT);
        if (!username.matches("[a-z0-9_.-]{3,40}")) throw bad("Use 3–40 letters, numbers, dots, underscores or hyphens for user ID.");
        return username;
    }

    String email(Map<String, ?> data) {
        String email = text(data, "email", 200, true).toLowerCase(Locale.ROOT);
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw bad("Enter a valid email address.");
        return email;
    }

    String password(Map<String, ?> data, String field) {
        Object input = data.get(field);
        if (input instanceof String string && !string.isEmpty() && string.length() <= 128) return string;
        throw bad("Enter your password.");
    }

    private String newPassword(Map<String, ?> data) {
        String value = password(data, "password");
        if (value.length() < 10) throw bad("Use 10–128 characters for your password.");
        if (!value.equals(data.get("confirm"))) throw bad("The passwords must match.");
        return vault.hashPassword(value);
    }

    @Transactional
    public Map<String, Object> register(Map<String, ?> data) {
        String name = username(data);
        String email = email(data);
        String pan = text(data, "pan", 10, true).toUpperCase(Locale.ROOT);
        String account = text(data, "account", 18, true);
        String ifsc = text(data, "ifsc", 11, true).toUpperCase(Locale.ROOT);
        String bank = required(data, "bank");
        if (!pan.matches("[A-Z]{5}[0-9]{4}[A-Z]")) throw bad("Enter a PAN in the format ABCDE1234F.");
        if (!account.matches("[0-9]{6,18}") || !ifsc.matches("[A-Z]{4}0[A-Z0-9]{6}")) {
            throw bad("Enter a valid account number format and IFSC.");
        }
        String fingerprint = vault.fingerprint("pan", pan);
        if (store.first(Account.class, "from Account where username=?1 or email=?2 or panFingerprint=?3",
                name, email, fingerprint) != null) {
            throw new Problem(409, "A request or account already exists for this user ID, email or sample PAN.");
        }
        Account user = new Account();
        user.username = name;
        user.email = email;
        user.passwordHash = newPassword(data);
        user.panFingerprint = fingerprint;
        user.details = vault.seal(fields("pan", pan, "account", account, "ifsc", ifsc, "bank", bank));
        store.save(user);
        store.audit(user, "access.requested", user.id);
        return fields("message", "Access requested. Sign in once an administrator approves it.");
    }

    @Transactional
    public void bootstrap(String name, String address, String password) {
        if (name.isBlank() && address.isBlank() && password.isBlank()) return;
        var values = fields("username", name, "email", address, "password", password, "confirm", password);
        String userId = username(values);
        String email = email(values);
        Account existing = store.first(Account.class, "from Account where username=?1 or email=?2", userId, email);
        if (existing != null) {
            if (existing.role.equals("admin") && existing.username.equals(userId) && existing.email.equals(email)) return;
            throw new IllegalStateException("The configured administrator user ID or email is already in use.");
        }
        Account admin = new Account();
        admin.username = userId;
        admin.email = email;
        admin.passwordHash = newPassword(values);
        admin.role = "admin";
        admin.status = "approved";
        store.save(admin);
        store.audit(admin, "admin.created", admin.id);
    }

    private void verifyChallenge(Map<String, ?> data) {
        String raw = text(data, "challenge", 100, true);
        String answer = text(data, "answer", 4, true);
        AuthToken token = store.lock(AuthToken.class, vault.fingerprint("challenge", raw));
        if (token == null) throw auth(400, "Screen verification failed. Try a fresh challenge.");
        boolean valid = token.expires.isAfter(Instant.now()) && token.verifier.equals(answer);
        store.remove(token);
        if (!valid) throw auth(400, "Screen verification failed. Try a fresh challenge.");
    }

    @Transactional(noRollbackFor = Problem.Authentication.class)
    public Map<String, Object> login(Map<String, ?> data) {
        String name = username(data);
        String password = password(data, "password");
        verifyChallenge(data);
        Long found = store.first(Long.class, "select id from Account where username=?1", name);
        if (found == null) {
            vault.matchesPassword(password, dummyPassword);
            throw auth(401, "Invalid credentials. You can request access from the sign-in page.");
        }
        Account user = store.lock(Account.class, found);
        Instant now = Instant.now();
        if (user.lockedUntil.isAfter(now)) throw auth(423, "Account is locked. Reset the password or try again in 15 minutes.");
        if (!vault.matchesPassword(password, user.passwordHash)) {
            if (!user.lockedUntil.equals(Instant.EPOCH)) user.attempts = 0;
            user.attempts++;
            user.lockedUntil = Instant.EPOCH;
            if (user.attempts >= 3) {
                user.lockedUntil = now.plus(Duration.ofMinutes(15));
                store.audit(user, "access.locked", user.id);
                throw auth(423, "Account locked for 15 minutes.");
            }
            throw auth(401, "Invalid credentials.");
        }
        if (!user.status.equals("approved")) {
            throw auth(403, user.status.equals("pending") ? "Your account is awaiting approval." : "Your access request was declined.");
        }
        user.attempts = 0;
        user.lockedUntil = Instant.EPOCH;
        return fields("token", issueCode(user, "login"), "pin", user.pinHash != null);
    }

    private String issueCode(Account user, String purpose) {
        AuthToken recent = store.first(AuthToken.class,
                "from AuthToken where owner.id=?1 and purpose=?2 and expires>?3",
                user.id, purpose, Instant.now().plusSeconds(540));
        if (recent != null) throw auth(429, "Wait one minute before requesting another code.");
        store.delete("delete from AuthToken where owner.id=?1 and purpose=?2", user.id, purpose);
        String token = vault.token();
        String code = String.format(Locale.ROOT, "%06d", vault.draw(1_000_000));
        createToken(token, user, purpose, vault.fingerprint("otp", token + code), Duration.ofMinutes(10));
        delivery.verification(user.email, code);
        return token;
    }

    @Transactional
    public Map<String, Object> reset(Map<String, ?> data) {
        Long found = store.first(Long.class, "select id from Account where username=?1 and email=?2 and status='approved'",
                username(data), email(data));
        String token = found == null ? vault.token() : issueCode(store.lock(Account.class, found), "reset");
        return fields("token", token, "message", "If the account matches, a verification code will be sent.");
    }

    @Transactional(noRollbackFor = Problem.Authentication.class)
    public Verification verify(Map<String, ?> data, boolean reset) {
        String purpose = reset ? "reset" : "login";
        String raw = text(data, "token", 100, true);
        String code = text(data, "code", 10, true);
        String fingerprint = vault.fingerprint(purpose, raw);
        Long found = store.first(Long.class, "select owner.id from AuthToken where id=?1", fingerprint);
        if (found == null) throw auth(401, "Invalid or expired verification code.");
        Account user = store.lock(Account.class, found);
        AuthToken token = store.lock(AuthToken.class, fingerprint);
        if (token == null || !token.expires.isAfter(Instant.now()) || token.attempts >= 5 ||
                !user.status.equals("approved")) {
            throw auth(401, "Invalid or expired verification code.");
        }
        token.attempts++;
        if (!Vault.matches(token.verifier, vault.fingerprint("otp", raw + code))) {
            throw auth(401, "Invalid or expired verification code.");
        }
        if (reset) {
            user.passwordHash = newPassword(data);
            user.attempts = 0;
            user.lockedUntil = Instant.EPOCH;
            store.delete("delete from AuthToken where owner.id=?1", user.id);
            store.audit(user, "password.reset", user.id);
            return new Verification(fields("message", "Password updated. Sign in with your new password."), null);
        }
        if (user.lockedUntil.isAfter(Instant.now())) throw auth(423, "Account is locked. Reset your password to continue.");
        if (user.pinHash != null && !vault.matchesPassword(text(data, "pin", 8, true), user.pinHash)) {
            throw auth(401, "Invalid access PIN.");
        }
        store.remove(token);
        return new Verification(Map.of(), session(user));
    }

    String session(Account user) {
        String raw = vault.token();
        createToken(raw, user, "session", null, SESSION_LENGTH);
        store.audit(user, "sign-in", user.id);
        return raw;
    }

    Account account(String raw, boolean write) {
        if (raw == null || raw.length() > 100) throw auth(401, "Sign in to continue.");
        String id = vault.fingerprint("session", raw);
        if (!write) {
            AuthToken session = store.find(AuthToken.class, id);
            if (session == null || !session.expires.isAfter(Instant.now()) || !session.owner.status.equals("approved")) {
                throw auth(401, "Sign in to continue.");
            }
            return session.owner;
        }
        Long owner = store.first(Long.class, "select owner.id from AuthToken where id=?1", id);
        if (owner == null) throw auth(401, "Sign in to continue.");
        Account user = store.lock(Account.class, owner);
        AuthToken locked = store.lock(AuthToken.class, id);
        if (user == null || locked == null || !locked.expires.isAfter(Instant.now()) || !user.status.equals("approved")) {
            throw auth(401, "Sign in to continue.");
        }
        return user;
    }

    void logout(String raw) {
        store.delete("delete from AuthToken where id=?1", vault.fingerprint("session", raw));
    }

    void changeCredentials(Account actor, String raw, Map<String, ?> data, boolean pin) {
        if (!vault.matchesPassword(password(data, "current"), actor.passwordHash)) throw bad("Current password is incorrect.");
        if (pin) {
            String value = text(data, "pin", 8, false);
            if (!value.isEmpty() && !value.matches("[0-9]{6,8}")) throw bad("Use 6–8 digits for the access PIN.");
            actor.pinHash = value.isEmpty() ? null : vault.hashPassword(value);
            store.delete("delete from AuthToken where owner.id=?1 and purpose='login'", actor.id);
            store.audit(actor, "pin.changed", actor.id);
        } else {
            actor.passwordHash = newPassword(data);
            store.delete("delete from AuthToken where owner.id=?1 and id<>?2",
                    actor.id, vault.fingerprint("session", raw));
            store.audit(actor, "password.changed", actor.id);
        }
    }

    static Problem.Authentication auth(int status, String message) {
        return new Problem.Authentication(status, message);
    }

    record Verification(Map<String, Object> data, String session) {}
}
