package com.application.e_wallet.integration.plaid;

import com.application.e_wallet.common.exception.ProviderUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The only class that talks HTTP to Plaid. It adds the credentials to every call and turns every failure
 * into a safe {@link ProviderUnavailableException}: no secret, token or personal data reaches a log or a response.
 */
@Slf4j
@Component
public class PlaidClient {

    private final PlaidProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public PlaidClient(PlaidProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    public JsonNode post(String path, Map<String, Object> body) {
        if (properties.getClientId() == null || properties.getClientId().isBlank()
                || properties.getSecret() == null || properties.getSecret().isBlank()) {
            log.error("Plaid is not configured: PLAID_CLIENT_ID / PLAID_SECRET are missing");
            throw new ProviderUnavailableException("Identity verification is not available right now.");
        }
        Map<String, Object> payload = new LinkedHashMap<>(body);
        payload.put("client_id", properties.getClientId());
        payload.put("secret", properties.getSecret());
        try {
            String raw = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(payload))
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(raw);
        } catch (RestClientResponseException e) {
            JsonNode error = parseQuietly(e.getResponseBodyAsString());
            log.warn("Plaid {} failed: status={} error_code={} request_id={}", path, e.getStatusCode().value(),
                    text(error, "error_code"), text(error, "request_id"));
            throw new ProviderUnavailableException("Identity verification is not available right now. Please try again.");
        } catch (RestClientException e) {
            log.warn("Plaid {} unreachable: {}", path, e.getClass().getSimpleName());
            throw new ProviderUnavailableException("Identity verification is not available right now. Please try again.");
        }
    }

    private JsonNode parseQuietly(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asString() : "unknown";
    }
}
