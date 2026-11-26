package com.application.e_wallet.bank.service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Does the name on the bank account belong to the person using it? Banks write names in different shapes
 * ("AMINA J SAID", "Said, Amina"), so each name is cut into plain words and the shorter one must be found
 * inside the longer. A single word on its own never matches, so one common first name is not enough.
 */
public final class NameMatcher {

    private NameMatcher() { }

    public static boolean matchesAny(String expected, Collection<String> bankNames) {
        return bankNames.stream().anyMatch(name -> matches(expected, name));
    }

    public static boolean matches(String expected, String bankName) {
        Set<String> a = words(expected);
        Set<String> b = words(bankName);
        if (a.size() < 2 || b.size() < 2) {
            return false;
        }
        Set<String> shorter = a.size() <= b.size() ? a : b;
        Set<String> longer = shorter == a ? b : a;
        return longer.containsAll(shorter);
    }

    static Set<String> words(String name) {
        if (name == null) {
            return Set.of();
        }
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("['’]", "");
        return new LinkedHashSet<>(Arrays.stream(plain.split("[^a-z]+")).filter(w -> !w.isBlank()).toList());
    }
}
