package com.application.e_wallet.common.validation;

/** Rejects PINs that are trivially guessable. Format (4 digits) is checked by Bean Validation. */
public final class PinPolicy {

    public static final String WEAK_PIN_MESSAGE =
            "Choose a less predictable PIN. Avoid repeated or sequential digits.";

    private PinPolicy() {
    }

    public static boolean isWeak(String pin) {
        if (pin == null || !pin.matches("[0-9]{4}")) {
            return false;
        }
        int[] d = pin.chars().map(c -> c - '0').toArray();

        boolean allSame = d[0] == d[1] && d[1] == d[2] && d[2] == d[3];
        boolean ascending = step(d, 1);
        boolean descending = step(d, 9); // -1 modulo 10
        boolean abab = d[0] == d[2] && d[1] == d[3];
        boolean aabb = d[0] == d[1] && d[2] == d[3];

        return allSame || ascending || descending || abab || aabb;
    }

    private static boolean step(int[] d, int delta) {
        for (int i = 1; i < d.length; i++) {
            if ((d[i - 1] + delta) % 10 != d[i]) {
                return false;
            }
        }
        return true;
    }
}
