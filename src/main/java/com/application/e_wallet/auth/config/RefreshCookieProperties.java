package com.application.e_wallet.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "app.auth.refresh-cookie")
public class RefreshCookieProperties {

    /** Cookie name. */
    private String name = "wp_refresh";

    /** Always true outside local plain-http development. */
    private boolean secure = true;

    /** Only the auth endpoints ever receive the cookie. */
    private String path = "/api/v1/auth";
}
