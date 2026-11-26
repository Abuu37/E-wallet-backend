package com.application.e_wallet.integration.plaid;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The name-check bypass exists only in Sandbox, and the app refuses to start with it anywhere else. */
class PlaidPropertiesTest {

    private static PlaidProperties props(String env, boolean skip) {
        PlaidProperties p = new PlaidProperties();
        p.setEnv(env);
        p.getSandbox().setSkipNameMatch(skip);
        return p;
    }

    @Test
    void theOverrideIsActiveOnlyInSandboxWithTheFlagOn() {
        assertThat(props("sandbox", true).skipNameMatchActive()).isTrue();
        assertThat(props("sandbox", false).skipNameMatchActive()).isFalse();
    }

    @Test
    void theFlagOutsideSandboxStopsTheApp() {
        assertThatThrownBy(() -> props("production", true).refuseUnsafeFlag()).isInstanceOf(IllegalStateException.class);
        props("production", false).refuseUnsafeFlag();
        props("sandbox", true).refuseUnsafeFlag();
        assertThat(props("production", true).skipNameMatchActive()).isFalse();
    }
}
