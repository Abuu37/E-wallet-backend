package com.application.e_wallet.bank.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NameMatcherTest {

    @Test
    void sameNameInADifferentShapeMatches() {
        assertThat(NameMatcher.matches("Amina Said", "AMINA SAID")).isTrue();
        assertThat(NameMatcher.matches("Amina Said", "Said, Amina")).isTrue();
        assertThat(NameMatcher.matches("Amina Juma Said", "Amina Said")).isTrue();
        assertThat(NameMatcher.matches("Amina Said", "Amina Juma Said")).isTrue();
    }

    @Test
    void accentsAndPunctuationAreIgnored() {
        assertThat(NameMatcher.matches("Zoë O'Neil", "Zoe ONeil")).isTrue();
    }

    @Test
    void aDifferentPersonDoesNotMatch() {
        assertThat(NameMatcher.matches("Amina Said", "Juma Ali")).isFalse();
        assertThat(NameMatcher.matches("Amina Said", "Amina Hassan")).isFalse();
    }

    @Test
    void aSingleSharedWordIsNeverEnough() {
        assertThat(NameMatcher.matches("Amina", "Amina")).isFalse();
        assertThat(NameMatcher.matches("Amina Said", "Amina")).isFalse();
        assertThat(NameMatcher.matches(null, "Amina Said")).isFalse();
    }

    @Test
    void anyOwnerNameCanMatch() {
        assertThat(NameMatcher.matchesAny("Amina Said", List.of("Juma Ali", "Amina Said"))).isTrue();
        assertThat(NameMatcher.matchesAny("Amina Said", List.of())).isFalse();
    }
}
