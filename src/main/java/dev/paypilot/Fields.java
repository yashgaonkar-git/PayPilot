package dev.paypilot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Fields {
    static final List<String> METHODS = List.of("Online banking", "Bank transfer", "Direct debit",
            "Debit card", "Credit card", "Digital wallet");
    static final List<String> CATEGORIES = List.of("Utilities", "House Rent", "Groceries", "Internet Charges",
            "Retirement Charges", "Cell Phone Charges", "Debt Payments", "Subscriptions", "Insurance");
    static final List<String> FREQUENCIES = List.of("Off", "Daily", "Weekly", "Bi-weekly", "Monthly");
    static final List<String> RECURRENCES = List.of("Once", "Weekly", "Bi-weekly", "Monthly", "Yearly");
    static final Map<String, List<String>> PAYEES = Map.of(
            "Home Rental Services", List.of("14 Maple Avenue, Pune", "Demo Bank •••• 2134"),
            "City Power", List.of("52 East Street, Mumbai", "Demo Bank •••• 0975"),
            "Metro Fiber", List.of("16 Park Road, Bengaluru", "Demo Bank •••• 4410"));
    static final List<String> FUNDING = List.of("Demo current •••• 4582", "Demo savings •••• 0921");
    static final List<String> INSTITUTIONS = List.of("Demo Bank", "Demo Wallet");
    static final List<String> OUTCOMES = List.of("Approve", "Decline");
    static final String SMS_PREVIEW = "SMS preview · Demo handset";

    private Fields() {}

    static String text(Map<String, ?> input, String key, int max, boolean required) {
        Object value = input.get(key);
        if (value == null && !required) return "";
        if (value instanceof String string && string.length() <= max && (!required || !string.isBlank())) {
            return string.strip();
        }
        throw bad("Enter a valid " + key.replace('_', ' ') + ".");
    }

    static String required(Map<String, ?> input, String key) {
        return text(input, key, 120, true);
    }

    static String choice(Map<String, ?> input, String key, Collection<String> accepted) {
        String value = required(input, key);
        if (!accepted.contains(value)) throw bad("Choose a valid " + key.replace('_', ' ') + ".");
        return value;
    }

    static long id(Object value) {
        if ((value instanceof Integer || value instanceof Long) && ((Number) value).longValue() > 0) {
            return ((Number) value).longValue();
        }
        throw bad("Invalid record ID.");
    }

    static LocalDate day(Map<String, ?> input, String key, boolean required) {
        String value = text(input, key, 10, required);
        if (value.isEmpty()) return null;
        try {
            LocalDate day = LocalDate.parse(value);
            if (day.getYear() >= 1 && day.getYear() <= 9999 && day.toString().equals(value)) return day;
        } catch (DateTimeParseException ignored) {
            // An invalid date has the same response as an unsupported year.
        }
        throw bad("Enter a valid " + key.replace('_', ' ') + ".");
    }

    static long money(Map<String, ?> input) {
        String value = text(input, "amount", 12, true);
        if (value.matches("[0-9]{1,8}(\\.[0-9]{1,2})?")) {
            long minorUnits = new BigDecimal(value).movePointRight(2).longValueExact();
            if (minorUnits > 0) return minorUnits;
        }
        throw bad("Enter a positive amount with up to two decimal places.");
    }

    static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) fields.put((String) pairs[i], pairs[i + 1]);
        return fields;
    }

    static Problem bad(String message) {
        return new Problem(400, message);
    }
}
