package dev.paypilot;

import static dev.paypilot.Fields.*;
import static dev.paypilot.Models.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

@Service
class Workspace {
    private static final int MAX_PDF = 3 * 1024 * 1024;
    private final Store store;
    private final Vault vault;
    private final Ledger ledger;

    Workspace(Store store, Vault vault, Ledger ledger) {
        this.store = store;
        this.vault = vault;
        this.ledger = ledger;
    }

    List<Bill> bills(Account owner) {
        return store.findAll(Bill.class, """
                from Bill b left join fetch b.reminder where b.owner.id=?1 and b.archived=false order by b.due,b.id
                """, owner.id);
    }

    private Bill insert(Account owner, LocalDate due, long amount, String series, byte[] details) {
        Bill bill = new Bill();
        bill.owner = owner;
        bill.due = due;
        bill.amount = amount;
        bill.series = series;
        bill.details = details;
        store.save(bill);
        return bill;
    }

    private void setReminder(Bill bill, String frequency, LocalDate start, String channel, boolean repeat, byte[] message) {
        Reminder reminder = bill.reminder;
        if (reminder == null) {
            reminder = new Reminder();
            reminder.bill = bill;
            bill.reminder = reminder;
            reminder.frequency = frequency;
            reminder.channel = channel;
            store.save(reminder);
        }
        reminder.frequency = frequency;
        reminder.channel = channel;
        reminder.startDay = start;
        reminder.repeatSeries = repeat;
        reminder.message = message;
    }

    long saveBill(Account owner, Map<String, ?> data) {
        LocalDate due = day(data, "due", true);
        long amount = money(data);
        String recurrence = choice(data, "payment_frequency", RECURRENCES);
        String reminderFrequency = choice(data, "reminder_frequency", FREQUENCIES);
        String channel = choice(data, "channel", List.of("In app", "Email", "Contact"));
        LocalDate start = day(data, "reminder_start", false);
        if (!reminderFrequency.equals("Off")) {
            if (start == null) start = due.minusDays(Math.min(3, ChronoUnit.DAYS.between(LocalDate.of(1, 1, 1), due)));
            if (!start.isBefore(due)) throw bad("The reminder must start before the bill is due.");
        }
        String custom = text(data, "reminder_message", 250, false);
        byte[] details = vault.seal(fields("name", required(data, "name"), "company", required(data, "company"),
                "category", text(data, "category", 60, true), "payment_frequency", recurrence,
                "priority", choice(data, "priority", List.of("Normal", "High")), "notes", text(data, "notes", 1000, false)));
        byte[] message = custom.isEmpty() ? null : vault.sealText(custom);
        boolean repeatReminder = Boolean.TRUE.equals(data.get("remind_recurring"));
        Bill bill;
        if (data.get("id") != null) {
            bill = ledger.bill(owner, id(data.get("id")));
            if (bill.series != null && (!due.equals(bill.due)
                    || !recurrence.equals(vault.open(bill.details).get("payment_frequency")))) {
                throw bad("Change recurrence or the due date by creating a new series.");
            }
            if (ledger.paid(bill) + ledger.reserved(bill) > amount) {
                throw bad("The amount must cover recorded and scheduled payments.");
            }
            bill.due = due;
            bill.amount = amount;
            bill.details = details;
        } else {
            bill = insert(owner, due, amount, null, details);
        }
        setReminder(bill, reminderFrequency, start, channel, repeatReminder, message);
        if (bill.series == null && !recurrence.equals("Once")) {
            if (due.getYear() >= 9998) throw bad("Choose a nearer date for recurring bills.");
            bill.series = vault.token();
            for (LocalDate occurrence : occurrences(due, recurrence)) {
                Bill next = insert(owner, occurrence, amount, bill.series, details);
                LocalDate nextStart = start == null ? null : occurrence.plusDays(ChronoUnit.DAYS.between(due, start));
                setReminder(next, reminderFrequency, nextStart, channel, repeatReminder, message);
            }
        }
        saveAttachment(bill, data);
        store.audit(owner, "bill.saved", bill.id);
        return bill.id;
    }

    private List<LocalDate> occurrences(LocalDate anchor, String frequency) {
        var dates = new ArrayList<LocalDate>();
        switch (frequency) {
            case "Weekly", "Bi-weekly" -> {
                int step = frequency.equals("Weekly") ? 7 : 14;
                for (int days = step; days <= 90; days += step) dates.add(anchor.plusDays(days));
            }
            case "Monthly" -> {
                for (int months = 1; months <= 3; months++) dates.add(anchor.plusMonths(months));
            }
            case "Yearly" -> dates.add(anchor.plusYears(1));
            default -> throw new IllegalStateException("Invalid recurrence.");
        }
        return dates;
    }

    private void saveAttachment(Bill bill, Map<String, ?> data) {
        String value = text(data, "attachment", MAX_PDF * 4 / 3, false);
        if (!value.isEmpty()) {
            byte[] pdf;
            try {
                pdf = Base64.getDecoder().decode(value);
            } catch (IllegalArgumentException e) {
                throw bad("Invalid attachment.");
            }
            String filename = text(data, "filename", 160, true).toLowerCase(java.util.Locale.ROOT);
            String head = new String(pdf, 0, Math.min(5, pdf.length), StandardCharsets.US_ASCII);
            int tailOffset = Math.max(0, pdf.length - 2048);
            String tail = new String(pdf, tailOffset, pdf.length - tailOffset, StandardCharsets.US_ASCII);
            if (pdf.length > MAX_PDF || !filename.endsWith(".pdf") || !head.equals("%PDF-") || !tail.contains("%%EOF")) {
                throw bad("Choose a valid PDF up to 3 MB.");
            }
            Attachment attachment = store.find(Attachment.class, bill.id);
            if (attachment == null) {
                attachment = new Attachment();
                attachment.bill = bill;
                attachment.content = vault.encrypt(pdf);
                store.save(attachment);
            } else {
                attachment.content = vault.encrypt(pdf);
            }
        } else if (Boolean.TRUE.equals(data.get("remove_attachment"))) {
            Attachment attachment = store.find(Attachment.class, bill.id);
            if (attachment != null) store.remove(attachment);
        }
    }

    byte[] attachment(Account owner, long id) {
        Bill bill = ledger.bill(owner, id);
        Attachment attachment = store.find(Attachment.class, bill.id);
        if (attachment == null) throw new Problem(404, "Attachment not found.");
        return vault.decrypt(attachment.content);
    }

    List<Map<String, Object>> billAction(Account owner, String action, Map<String, ?> data) {
        if (!(data.get("ids") instanceof List<?> list) || list.isEmpty() || list.size() > 100) {
            throw bad("Select one or more bills.");
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Object value : list) ids.add(id(value));
        LocalDate today = LocalDate.now();
        LocalDate when = action.equals("snooze") || action.equals("record") ? day(data, "day", true) : today;
        if (action.equals("snooze") && !when.isAfter(today)) throw bad("Choose a snooze date after today.");
        if (action.equals("record") && (ids.size() != 1 || when.isAfter(today))) {
            throw bad("Record one payment dated today or earlier.");
        }
        String method = action.equals("pay") || action.equals("record") ? choice(data, "method", METHODS) : "Marked paid";
        String institution = action.equals("pay") && data.containsKey("institution")
                ? choice(data, "institution", INSTITUTIONS) : INSTITUTIONS.get(0);
        boolean approve = !action.equals("pay") || !data.containsKey("outcome") || choice(data, "outcome", OUTCOMES).equals("Approve");
        var receipts = new ArrayList<Map<String, Object>>();
        for (long id : ids) {
            Bill bill = ledger.bill(owner, id);
            if (action.equals("archive")) {
                bill.archived = true;
                ledger.cancelExcess(owner, bill, 0);
                store.audit(owner, "bill.archived", bill.id);
                continue;
            }
            long amount = bill.amount - ledger.paid(bill);
            if (amount <= 0) throw bad("A selected bill is already paid.");
            switch (action) {
                case "snooze" -> {
                    bill.reminder.snoozedUntil = when;
                    store.audit(owner, "bill.snoozed", bill.id);
                }
                case "pay" -> receipts.add(ledger.testPayment(owner, bill, amount, method, institution, approve, when, null));
                case "record" -> ledger.record(owner, bill, money(data), method, "External payment", when, null);
                case "mark" -> ledger.record(owner, bill, amount, method, "Manual", when, null);
                default -> throw new IllegalStateException("Unexpected bill action.");
            }
        }
        return receipts;
    }

    void refresh(Account owner, LocalDate today) {
        ledger.settleDue(owner, today);
        for (Bill bill : bills(owner)) {
            Reminder reminder = bill.reminder;
            if (!reminderDue(bill, today) || ledger.paid(bill) >= bill.amount) continue;
            if (bill.series != null && !reminder.repeatSeries) {
                LocalDate first = store.first(LocalDate.class, "select min(due) from Bill where owner.id=?1 and series=?2",
                        owner.id, bill.series);
                if (!bill.due.equals(first)) continue;
            }
            if (store.first(Notice.class, "from Notice where bill.id=?1 and day=?2", bill.id, today) != null) continue;
            String message = vault.openText(reminder.message);
            if (message.isEmpty()) message = vault.open(bill.details).get("name") + " is due on " + bill.due + ".";
            String delivery = switch (reminder.channel) {
                case "Email" -> "Pending email";
                case "Contact" -> SMS_PREVIEW;
                default -> "In app";
            };
            notification(bill, today, message, delivery);
        }
    }

    private boolean reminderDue(Bill bill, LocalDate today) {
        Reminder reminder = bill.reminder;
        if (reminder == null || reminder.frequency.equals("Off") || reminder.startDay == null
                || reminder.startDay.isAfter(today) || (reminder.snoozedUntil != null && reminder.snoozedUntil.isAfter(today))) {
            return false;
        }
        if (today.equals(reminder.snoozedUntil)) return true;
        if (today.isAfter(bill.due)) return false;
        long days = ChronoUnit.DAYS.between(reminder.startDay, today);
        return switch (reminder.frequency) {
            case "Daily" -> true;
            case "Weekly" -> days % 7 == 0;
            case "Bi-weekly" -> days % 14 == 0;
            case "Monthly" -> reminder.startDay.plusMonths(
                    ChronoUnit.MONTHS.between(reminder.startDay.withDayOfMonth(1), today.withDayOfMonth(1))).equals(today);
            default -> false;
        };
    }

    private void notification(Bill bill, LocalDate day, String message, String delivery) {
        Notice notice = new Notice();
        notice.bill = bill;
        notice.day = day;
        notice.message = vault.sealText(message);
        notice.delivery = delivery;
        store.save(notice);
    }

    void readNotice(Account owner, long id) {
        Notice notice = store.first(Notice.class, "from Notice where bill.owner.id=?1 and id=?2", owner.id, id);
        if (notice == null) throw new Problem(404, "Notification not found.");
        notice.seen = true;
    }

    Map<String, Object> state(Account owner, LocalDate today) {
        List<Payment> payments = ledger.payments(owner);
        List<PaymentPlan> plans = ledger.plans(owner);
        var paid = new HashMap<Long, Long>();
        var scheduled = new HashMap<Long, Long>();
        for (Payment payment : payments) paid.merge(payment.bill.id, payment.amount, Long::sum);
        for (PaymentPlan plan : plans) {
            if (plan.status.equals("Scheduled")) scheduled.merge(plan.bill.id, plan.amount, Long::sum);
        }
        Set<Long> attachments = Set.copyOf(store.findAll(Long.class,
                "select id from Attachment where bill.owner.id=?1 and bill.archived=false", owner.id));
        var bills = bills(owner).stream().map(bill -> {
            Reminder reminder = bill.reminder;
            long amountPaid = paid.getOrDefault(bill.id, 0L);
            long balance = bill.amount - amountPaid;
            Map<String, Object> view = vault.open(bill.details);
            view.putAll(fields("id", bill.id, "amount", bill.amount, "due", bill.due.toString(), "series", bill.series,
                    "balance", balance, "paid", amountPaid, "scheduled", scheduled.getOrDefault(bill.id, 0L),
                    "overdue_days", balance > 0 ? Math.max(0, ChronoUnit.DAYS.between(bill.due, today)) : 0,
                    "reminder_frequency", reminder.frequency, "reminder_start", dateText(reminder.startDay),
                    "channel", reminder.channel, "reminder_message", vault.openText(reminder.message),
                    "remind_recurring", reminder.repeatSeries, "snoozed", dateText(reminder.snoozedUntil),
                    "attachment", attachments.contains(bill.id)));
            return view;
        }).toList();
        var planViews = plans.stream().map(plan -> {
            var view = vault.open(plan.details);
            view.putAll(billName(plan.bill));
            view.putAll(fields("id", plan.id, "bill_id", plan.bill.id, "day", plan.day.toString(),
                    "amount", plan.amount, "status", plan.status));
            return view;
        }).toList();
        var paymentViews = payments.stream().map(payment -> {
            var view = billName(payment.bill);
            view.putAll(fields("id", payment.id, "bill_id", payment.bill.id, "due", payment.bill.due.toString(),
                    "day", payment.day.toString(), "amount", payment.amount, "method", payment.method, "source", payment.source));
            return view;
        }).toList();
        var notes = store.findAll(Notice.class, "from Notice n where n.bill.owner.id=?1 order by n.day desc,n.id desc", owner.id)
                .stream().map(n -> fields("id", n.id, "day", n.day.toString(), "message", vault.openText(n.message),
                        "delivery", n.delivery, "read", n.seen)).toList();
        var attempts = store.findAll(TestAttempt.class, "from TestAttempt where bill.owner.id=?1 order by id desc", owner.id)
                .stream().map(ledger::attemptView).toList();
        return fields("user", fields("username", owner.username, "email", owner.email, "role", owner.role, "pin", owner.pinHash != null),
                "today", today.toString(), "bills", bills, "schedules", planViews, "payments", paymentViews,
                "notifications", notes, "payment_attempts", attempts, "methods", METHODS, "categories", CATEGORIES,
                "frequencies", FREQUENCIES, "payees", PAYEES, "funding", FUNDING,
                "test_institutions", INSTITUTIONS, "test_outcomes", OUTCOMES);
    }

    private Map<String, Object> billName(Bill bill) {
        var details = vault.open(bill.details);
        return fields("bill_name", details.get("name"), "category", details.get("category"));
    }

    private static String dateText(LocalDate day) {
        return day == null ? "" : day.toString();
    }

    void seed(Account owner, LocalDate today) {
        record Sample(String name, String company, String category, int days, long amount, long paid, String priority) {}
        List<Sample> examples = List.of(
                new Sample("Home rent", "Home Rental Services", "House Rent", 4, 1850000, 0, "High"),
                new Sample("Electricity", "City Power", "Utilities", -3, 216000, 86000, "High"),
                new Sample("Home internet", "Metro Fiber", "Internet Charges", 8, 119900, 0, "Normal"),
                new Sample("Music subscription", "Studio Audio", "Subscriptions", 14, 14900, 0, "Normal"),
                new Sample("Mobile plan", "City Mobile", "Cell Phone Charges", -8, 69900, 69900, "Normal"),
                new Sample("Health cover", "Harbor Insurance", "Insurance", -14, 329900, 329900, "Normal"));
        for (Sample example : examples) {
            LocalDate due = today.plusDays(example.days);
            Bill bill = insert(owner, due, example.amount, null, vault.seal(fields("name", example.name,
                    "company", example.company, "category", example.category, "payment_frequency", "Once",
                    "priority", example.priority, "notes", "")));
            setReminder(bill, "Off", null, "In app", true, null);
            if (example.paid > 0) ledger.record(owner, bill, example.paid, "Digital wallet", "Demo data", due, null);
            if (example.name.equals("Home rent")) notification(bill, today, "Home rent is due in 4 days.", SMS_PREVIEW);
            if (example.name.equals("Home internet")) {
                ledger.schedule(owner, fields("day", today.plusDays(5).toString(), "bill_id", bill.id,
                        "purpose", "Monthly internet", "bill_date", due.toString(), "payee", "Metro Fiber",
                        "method", "Bank transfer", "funding", FUNDING.get(0), "frequency", "Once", "amount", "1199"));
            }
        }
    }
}
