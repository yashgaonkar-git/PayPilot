package dev.paypilot;

import static dev.paypilot.Fields.*;
import static dev.paypilot.Models.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
class Ledger {
    private final Store store;
    private final Vault vault;

    Ledger(Store store, Vault vault) {
        this.store = store;
        this.vault = vault;
    }

    Bill bill(Account owner, long id) {
        Bill found = store.first(Bill.class, "from Bill where id=?1 and owner.id=?2 and archived=false", id, owner.id);
        if (found == null) throw new Problem(404, "Bill not found.");
        return found;
    }

    List<Payment> payments(Account owner) {
        return store.findAll(Payment.class, "from Payment p join fetch p.bill where p.bill.owner.id=?1 order by p.day desc,p.id desc", owner.id);
    }

    List<PaymentPlan> plans(Account owner) {
        return store.findAll(PaymentPlan.class, "from PaymentPlan p join fetch p.bill where p.bill.owner.id=?1 order by p.day,p.id", owner.id);
    }

    long paid(Bill bill) {
        return store.first(Long.class, "select coalesce(sum(p.amount),0) from Payment p where p.bill.id=?1", bill.id);
    }

    long reserved(Bill bill) {
        return store.first(Long.class,
                "select coalesce(sum(p.amount),0) from PaymentPlan p where p.bill.id=?1 and p.status='Scheduled'", bill.id);
    }

    void cancelExcess(Account owner, Bill bill, long remaining) {
        for (PaymentPlan plan : store.findAll(PaymentPlan.class,
                "from PaymentPlan where bill.id=?1 and status='Scheduled' order by day,id", bill.id)) {
            if (plan.amount <= remaining) {
                remaining -= plan.amount;
            } else {
                plan.status = "Cancelled";
                store.audit(owner, "schedule.cancelled", plan.id);
            }
        }
    }

    Payment record(Account owner, Bill bill, long amount, String method, String source, LocalDate day, PaymentPlan plan) {
        long balance = bill.amount - paid(bill);
        if (amount <= 0 || amount > balance) throw bad("Payment must be within the outstanding balance.");
        var payment = new Payment();
        payment.bill = bill;
        payment.plan = plan;
        payment.day = day;
        payment.amount = amount;
        payment.method = method;
        payment.source = source;
        store.save(payment);
        if (plan != null) plan.status = "Completed";
        cancelExcess(owner, bill, balance - amount);
        store.audit(owner, "payment.recorded", payment.id);
        return payment;
    }

    Map<String, Object> testPayment(Account owner, Bill bill, long amount, String method, String institution,
                                    boolean approve, LocalDate day, PaymentPlan plan) {
        String source = plan == null ? "Simulated Pay Now" : "Simulated schedule";
        var attempt = new TestAttempt();
        if (approve) attempt.payment = record(owner, bill, amount, method, source, day, plan);
        attempt.bill = bill;
        attempt.reference = "PP-TEST-" + java.util.UUID.randomUUID().toString().replace("-", "")
                .substring(0, 16).toUpperCase(java.util.Locale.ROOT);
        attempt.day = day;
        attempt.amount = amount;
        attempt.method = method;
        attempt.institution = institution;
        attempt.source = source;
        attempt.status = approve ? "Settled" : "Declined";
        Map<String, Object> billDetails = vault.open(bill.details);
        attempt.snapshot = vault.seal(fields("bill_name", billDetails.get("name"), "category", billDetails.get("category")));
        store.save(attempt);
        store.audit(owner, "test-payment." + attempt.status.toLowerCase(java.util.Locale.ROOT), attempt.reference);
        return attemptView(attempt);
    }

    Map<String, Object> attemptView(TestAttempt attempt) {
        Map<String, Object> result = vault.open(attempt.snapshot);
        result.putAll(fields("day", attempt.day.toString(), "reference", attempt.reference, "amount", attempt.amount,
                "method", attempt.method, "institution", attempt.institution, "source", attempt.source,
                "status", attempt.status));
        return result;
    }

    PaymentPlan plan(Account owner, long id) {
        PaymentPlan found = store.first(PaymentPlan.class,
                "from PaymentPlan where id=?1 and bill.owner.id=?2 and status='Scheduled'", id, owner.id);
        if (found == null) throw new Problem(404, "Active schedule not found.");
        return found;
    }

    void cancel(Account owner, long id) {
        PaymentPlan plan = plan(owner, id);
        plan.status = "Cancelled";
        store.audit(owner, "schedule.cancelled", plan.id);
    }

    void schedule(Account owner, Map<String, ?> data) {
        LocalDate today = LocalDate.now();
        LocalDate scheduled = day(data, "day", true);
        if (scheduled.isBefore(today)) throw bad("The payment date cannot be in the past.");
        if (data.get("id") != null) {
            PaymentPlan plan = plan(owner, id(data.get("id")));
            plan.day = scheduled;
            store.audit(owner, "schedule.rescheduled", plan.id);
            return;
        }
        Bill bill = bill(owner, id(data.get("bill_id")));
        String frequency = choice(data, "frequency", List.of("Once", (String) vault.open(bill.details).get("payment_frequency")));
        String payee = choice(data, "payee", PAYEES.keySet());
        String method = choice(data, "method", METHODS);
        Map<String, Object> details = fields("purpose", required(data, "purpose"), "payee", payee,
                "method", method, "funding", choice(data, "funding", FUNDING), "frequency", frequency,
                "address", PAYEES.get(payee).get(0), "bank", PAYEES.get(payee).get(1));
        long amount = money(data);
        LocalDate billDate = day(data, "bill_date", true);
        long billOffset = ChronoUnit.DAYS.between(bill.due, billDate);
        long scheduledOffset = ChronoUnit.DAYS.between(bill.due, scheduled);
        createPlan(owner, bill, scheduled, billDate, amount, details);
        if (!frequency.equals("Once") && bill.series != null) {
            var members = store.findAll(Bill.class, """
                    from Bill where owner.id=?1 and series=?2 and due>?3 and archived=false order by due
                    """, owner.id, bill.series, bill.due);
            for (Bill member : members) {
                createPlan(owner, member, member.due.plusDays(scheduledOffset),
                        member.due.plusDays(billOffset), amount, details);
            }
        }
    }

    private void createPlan(Account owner, Bill bill, LocalDate scheduled, LocalDate billDate,
                            long amount, Map<String, Object> details) {
        if (scheduled.getYear() > 9999 || billDate.getYear() < 1 || billDate.getYear() > 9999) {
            throw bad("A recurring payment would fall outside the supported dates.");
        }
        if (amount > bill.amount - paid(bill) - reserved(bill)) {
            throw bad("The amount exceeds the unreserved balance of a selected occurrence.");
        }
        var plan = new PaymentPlan();
        plan.bill = bill;
        plan.day = scheduled;
        plan.amount = amount;
        var snapshot = new HashMap<>(details);
        snapshot.put("bill_date", billDate.toString());
        plan.details = vault.seal(snapshot);
        store.save(plan);
        store.audit(owner, "schedule.created", plan.id);
    }

    void settleDue(Account owner, LocalDate today) {
        var due = store.findAll(PaymentPlan.class, """
                from PaymentPlan p join fetch p.bill where p.bill.owner.id=?1 and p.status='Scheduled'
                and p.day<=?2 and p.bill.archived=false order by p.day,p.id
                """, owner.id, today);
        for (PaymentPlan plan : due) {
            if (!plan.status.equals("Scheduled")) continue;
            String method = (String) vault.open(plan.details).get("method");
            testPayment(owner, plan.bill, plan.amount, method, INSTITUTIONS.get(0), true, today, plan);
        }
    }
}
