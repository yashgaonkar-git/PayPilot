package dev.paypilot;

class Problem extends RuntimeException {
    final int status;

    Problem(int status, String message) {
        super(message);
        this.status = status;
    }

    static final class Authentication extends Problem {
        Authentication(int status, String message) {
            super(status, message);
        }
    }
}
