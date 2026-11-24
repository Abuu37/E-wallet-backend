package com.application.e_wallet.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthHttpSecurityTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void responsesCarryTheSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/auth/csrf").secure(true))
                .andExpect(status().isNoContent())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "strict-origin"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().exists("Strict-Transport-Security"));
    }

    @Test
    void csrfEndpointIssuesAReadableXsrfCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf")).andReturn();
        Cookie xsrf = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        assertThat(xsrf.isHttpOnly()).isFalse(); // the SPA must be able to read it
    }

    @Test
    void refreshWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("wp_refresh", "anything")))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isForbidden());
    }

    @Test
    void refreshWithCsrfButNoRefreshCookieIsUnauthorized() throws Exception {
        String token = mockMvc.perform(get("/api/v1/auth/csrf")).andReturn()
                .getResponse().getCookie("XSRF-TOKEN").getValue();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("XSRF-TOKEN", token))
                        .header("X-XSRF-TOKEN", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshWithAGarbageCookieIsUnauthorized() throws Exception {
        String token = mockMvc.perform(get("/api/v1/auth/csrf")).andReturn()
                .getResponse().getCookie("XSRF-TOKEN").getValue();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("XSRF-TOKEN", token), new Cookie("wp_refresh", "not-a-jwt"))
                        .header("X-XSRF-TOKEN", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedForgotPinRequestsAreRateLimitedWith429AndRetryAfter() throws Exception {
        String body = "{\"email\":\"ratelimit-probe@example.com\"}";
        int last = 0;
        MvcResult limited = null;
        for (int i = 0; i < 8; i++) {
            MvcResult result = mockMvc.perform(post("/api/v1/auth/forgot-pin")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn();
            last = result.getResponse().getStatus();
            if (last == 429) {
                limited = result;
                break;
            }
        }
        assertThat(last).isEqualTo(429);
        assertThat(limited.getResponse().getHeader("Retry-After")).isNotBlank();
    }
}
