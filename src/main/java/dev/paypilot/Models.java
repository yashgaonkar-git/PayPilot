package dev.paypilot;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

final class Models {
    private Models() {}

    @MappedSuperclass
    public abstract static class Identified {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "pp_ids")
        @SequenceGenerator(name = "pp_ids", sequenceName = "pp_id_seq", allocationSize = 1)
        public Long id;
    }

    @Entity(name = "Account") @Table(name = "pp_account")
    public static class Account extends Identified {
        @Column(nullable = false, unique = true, length = 40) public String username;
        @Column(nullable = false, unique = true, length = 200) public String email;
        @Column(nullable = false) public String passwordHash;
        @Column(unique = true, length = 64) public String panFingerprint;
        @Lob public byte[] details;
        public String pinHash;
        @Column(nullable = false) public String status = "pending";
        @Column(nullable = false) public String role = "user";
        public int attempts;
        public Instant lockedUntil = Instant.EPOCH;
    }

    @Entity(name = "AuthToken") @Table(name = "pp_auth_token", indexes = @Index(columnList = "expires"))
    public static class AuthToken {
        @Id @Column(length = 64) public String id;
        @ManyToOne public Account owner;
        @Column(nullable = false) public String purpose;
        public String verifier;
        public int attempts;
        @Column(nullable = false) public Instant expires;
    }

    @Entity(name = "Bill") @Table(name = "pp_bill",
            indexes = @Index(columnList = "owner_id,due"))
    public static class Bill extends Identified {
        @ManyToOne(optional = false) public Account owner;
        @Column(nullable = false) public LocalDate due;
        public String series;
        public long amount;
        public boolean archived;
        @Lob @Column(nullable = false) public byte[] details;
        @OneToOne(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true)
        public Reminder reminder;
    }

    @Entity(name = "Reminder") @Table(name = "pp_reminder")
    public static class Reminder extends Identified {
        @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(unique = true) public Bill bill;
        @Column(nullable = false) public String frequency;
        @Column(nullable = false) public String channel;
        public LocalDate startDay;
        public LocalDate snoozedUntil;
        public boolean repeatSeries;
        @Lob public byte[] message;
    }

    @Entity(name = "Attachment") @Table(name = "pp_attachment")
    public static class Attachment {
        @Id public Long id;
        @MapsId @OneToOne(fetch = FetchType.LAZY, optional = false) public Bill bill;
        @Lob @Column(nullable = false) public byte[] content;
    }

    @Entity(name = "PaymentPlan") @Table(name = "pp_schedule",
            indexes = {@Index(columnList = "bill_id"), @Index(columnList = "status,planned_day")})
    public static class PaymentPlan extends Identified {
        @ManyToOne(optional = false) public Bill bill;
        @Column(name = "planned_day", nullable = false) public LocalDate day;
        @Column(nullable = false) public String status = "Scheduled";
        public long amount;
        @Lob @Column(nullable = false) public byte[] details;
    }

    @Entity(name = "Payment") @Table(name = "pp_payment", indexes = @Index(columnList = "bill_id"))
    public static class Payment extends Identified {
        @ManyToOne(optional = false) public Bill bill;
        @OneToOne @JoinColumn(unique = true) public PaymentPlan plan;
        @Column(name = "paid_day", nullable = false) public LocalDate day;
        public long amount;
        @Column(nullable = false) public String method;
        @Column(nullable = false) public String source;
    }

    @Entity(name = "TestAttempt") @Table(name = "pp_test_attempt", indexes = @Index(columnList = "bill_id"))
    public static class TestAttempt extends Identified {
        @ManyToOne(optional = false) public Bill bill;
        @OneToOne(fetch = FetchType.LAZY) @JoinColumn(unique = true) public Payment payment;
        @Column(nullable = false, unique = true) public String reference;
        @Column(name = "attempt_day", nullable = false) public LocalDate day;
        public long amount;
        @Column(nullable = false) public String method;
        @Column(nullable = false) public String institution;
        @Column(nullable = false) public String source;
        @Column(nullable = false) public String status;
        @Lob @Column(nullable = false) public byte[] snapshot;
    }

    @Entity(name = "Notice") @Table(name = "pp_notice",
            uniqueConstraints = @UniqueConstraint(columnNames = {"bill_id", "notice_day"}))
    public static class Notice extends Identified {
        @ManyToOne(optional = false) public Bill bill;
        @Column(name = "notice_day", nullable = false) public LocalDate day;
        @Lob @Column(nullable = false) public byte[] message;
        @Column(nullable = false) public String delivery;
        public boolean seen;
    }

    @Entity(name = "Mutation") @Table(name = "pp_mutation",
            uniqueConstraints = @UniqueConstraint(columnNames = {"owner_id", "request_key"}),
            indexes = @Index(columnList = "expires"))
    public static class Mutation extends Identified {
        @ManyToOne(optional = false) public Account owner;
        @Column(name = "request_key", nullable = false, length = 60) public String key;
        @Column(nullable = false, length = 64) public String fingerprint;
        @Column(nullable = false) public Instant expires;
        @Lob @Column(nullable = false) public byte[] response;
    }

    @Entity(name = "AuditEntry") @Table(name = "pp_audit", indexes = @Index(columnList = "actor_id"))
    public static class AuditEntry extends Identified {
        @ManyToOne(optional = false) public Account actor;
        @Column(nullable = false) public String action;
        @Column(nullable = false) public String target;
        @Column(nullable = false) public Instant recordedAt;
    }
}
