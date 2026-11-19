package dev.paypilot;

import static dev.paypilot.Fields.*;
import static dev.paypilot.Models.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ApplicationService {
    private final Store store;
    private final Vault vault;
    private final AccessService access;
    private final Workspace workspace;
    private final Ledger ledger;
    private final Delivery delivery;

    ApplicationService(Store store, Vault vault, AccessService access, Workspace workspace,
                       Ledger ledger, Delivery delivery) {
        this.store = store;
        this.vault = vault;
        this.access = access;
        this.workspace = workspace;
        this.ledger = ledger;
        this.delivery = delivery;
    }

    @Transactional
    public String demo() {
        Account user = new Account();
        user.username = "demo-" + java.util.UUID.randomUUID().toString().substring(0, 12);
        user.email = user.username + "@example.invalid";
        user.passwordHash = vault.hashPassword(vault.token());
        user.status = "approved";
        store.save(user);
        workspace.seed(user, LocalDate.now());
        return access.session(user);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> state(String token) {
        return workspace.state(access.account(token, false), LocalDate.now());
    }

    @Transactional(readOnly = true)
    public byte[] attachment(String token, long bill) {
        return workspace.attachment(access.account(token, false), bill);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> requests(String token) {
        requireAdmin(access.account(token, false));
        return store.findAll(Account.class,
                "from Account where role='user' and status in ('pending','denied') order by id desc")
                .stream().map(user -> fields("id", user.id, "username", user.username, "email", user.email,
                        "status", user.status)).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> audit(String token) {
        requireAdmin(access.account(token, false));
        return store.findAll(AuditEntry.class, """
                from AuditEntry a join fetch a.actor where a.actor.email not like '%@example.invalid'
                order by a.id desc limit 100
                """).stream().map(entry -> fields("at", entry.recordedAt, "actor", entry.actor.username,
                        "action", entry.action, "target", entry.target)).toList();
    }

    private void requireAdmin(Account actor) {
        if (!actor.role.equals("admin")) throw new Problem(403, "Administrator access is required.");
    }

    private void decide(Account actor, Map<String, ?> data) {
        requireAdmin(actor);
        long id = id(data.get("id"));
        String status = choice(data, "status", List.of("approved", "denied"));
        Account user = store.lock(Account.class, id);
        if (user == null || !user.role.equals("user") || !(user.status.equals("pending") || user.status.equals("denied"))) {
            throw new Problem(404, "Access request not found.");
        }
        user.status = status;
        store.audit(actor, "access." + status, user.id);
    }

    @Transactional
    public Map<String, Object> change(String token, String action, Map<String, Object> data) {
        Account actor = access.account(token, true);
        String key = text(data, "request_id", 60, false);
        String fingerprint = null;
        if (!key.isEmpty() && !action.equals("logout")) {
            fingerprint = vault.fingerprint("mutation", action + ":" + vault.json(new TreeMap<>(data)));
            Mutation prior = store.first(Mutation.class, "from Mutation where owner.id=?1 and key=?2", actor.id, key);
            if (prior != null) {
                if (!Vault.matches(prior.fingerprint, fingerprint)) {
                    throw new Problem(409, "This request ID was used for a different operation.");
                }
                return vault.open(prior.response);
            }
        }
        Map<String, Object> result = Map.of();
        switch (action) {
            case "bill" -> result = fields("id", workspace.saveBill(actor, data));
            case "snooze", "mark", "pay", "record", "archive" -> {
                var receipts = workspace.billAction(actor, action, data);
                if (!receipts.isEmpty()) result = fields("attempts", receipts);
            }
            case "schedule" -> ledger.schedule(actor, data);
            case "cancel" -> ledger.cancel(actor, id(data.get("id")));
            case "refresh" -> workspace.refresh(actor, LocalDate.now());
            case "notification" -> workspace.readNotice(actor, id(data.get("id")));
            case "access" -> decide(actor, data);
            case "password", "pin" -> access.changeCredentials(actor, token, data, action.equals("pin"));
            case "logout" -> access.logout(token);
            default -> throw new Problem(404, "Unknown action.");
        }
        if (fingerprint != null) {
            Mutation mutation = new Mutation();
            mutation.owner = actor;
            mutation.key = key;
            mutation.fingerprint = fingerprint;
            mutation.expires = Instant.now().plusSeconds(86400);
            mutation.response = vault.seal(result);
            store.save(mutation);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<Long> activeOwners() {
        return store.findAll(Long.class, """
                select distinct b.owner.id from Bill b where b.archived=false and b.owner.status='approved'
                """);
    }

    @Transactional
    public void processOwner(Long id) {
        Account owner = store.lock(Account.class, id);
        if (owner == null || !owner.status.equals("approved")) return;
        workspace.refresh(owner, LocalDate.now());
        for (Notice notice : store.findAll(Notice.class, """
                from Notice n where n.bill.owner.id=?1 and n.bill.archived=false and n.delivery='Pending email'
                order by n.id
                """, owner.id)) {
            if (!delivery.send(owner.email, "PayPilot bill reminder", vault.openText(notice.message))) break;
            notice.delivery = "Email sent";
        }
    }

    @Transactional
    public void prune() {
        Instant now = Instant.now();
        store.delete("delete from AuthToken where expires<=?1", now);
        store.delete("delete from Mutation where expires<=?1", now);
    }
}
