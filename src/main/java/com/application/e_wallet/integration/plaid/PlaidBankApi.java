package com.application.e_wallet.integration.plaid;

import com.application.e_wallet.common.exception.BadRequestException;
import com.application.e_wallet.common.exception.ProviderUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the wallet needs from a person's bank, through Plaid: link it, read the balance and the account holder's name,
 * and (Sandbox only) post the matching line on the Sandbox account. Access tokens are passed in and never logged.
 */
@Component
@RequiredArgsConstructor
public class PlaidBankApi {

    static final String BANK_UNAVAILABLE = "Your bank is not available right now. Please try again.";

    private final PlaidClient plaid;
    private final PlaidProperties properties;

    public record Exchanged(String accessToken, String itemId) { }

    public record BankAccount(String id, String name, String mask, String type, String subtype) { }

    /** A link token for Plaid Link. Only our own user id is sent to Plaid, never a phone number or a name. */
    public String createLinkToken(String clientUserId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_name", properties.getClientName());
        body.put("language", "en");
        body.put("country_codes", List.of("US"));
        body.put("user", Map.of("client_user_id", clientUserId));
        // transactions is needed so the Sandbox bank can show the matching debit or credit line.
        body.put("products", List.of("auth", "transactions"));
        body.put("account_filters", Map.of("depository", Map.of("account_subtypes", List.of("checking", "savings"))));
        JsonNode response = plaid.post("/link/token/create", body, BANK_UNAVAILABLE);
        if (!response.hasNonNull("link_token")) {
            throw new ProviderUnavailableException(BANK_UNAVAILABLE);
        }
        return response.get("link_token").asString();
    }

    public Exchanged exchange(String publicToken) {
        JsonNode response = plaid.post("/item/public_token/exchange", Map.of("public_token", publicToken), BANK_UNAVAILABLE);
        if (!response.hasNonNull("access_token") || !response.hasNonNull("item_id")) {
            throw new ProviderUnavailableException(BANK_UNAVAILABLE);
        }
        return new Exchanged(response.get("access_token").asString(), response.get("item_id").asString());
    }

    public List<BankAccount> accounts(String accessToken) {
        JsonNode response = plaid.post("/accounts/get", Map.of("access_token", accessToken), BANK_UNAVAILABLE);
        List<BankAccount> accounts = new ArrayList<>();
        for (JsonNode a : response.path("accounts")) {
            accounts.add(new BankAccount(text(a, "account_id"), text(a, "name"), text(a, "mask"), text(a, "type"), text(a, "subtype")));
        }
        return accounts;
    }

    public String institutionName(String accessToken) {
        try {
            JsonNode item = plaid.post("/item/get", Map.of("access_token", accessToken), BANK_UNAVAILABLE);
            return text(item.path("item"), "institution_name");
        } catch (ProviderUnavailableException e) {
            return null; // only a label; linking must not fail because of it
        }
    }

    /** The names on the account, as the bank holds them. Empty when the bank does not say. */
    public List<String> ownerNames(String accessToken, String accountId) {
        try {
            JsonNode response = plaid.post("/identity/get", Map.of("access_token", accessToken, "options", Map.of("account_ids", List.of(accountId))), BANK_UNAVAILABLE);
            List<String> names = new ArrayList<>();
            for (JsonNode account : response.path("accounts")) {
                for (JsonNode owner : account.path("owners")) {
                    for (JsonNode name : owner.path("names")) {
                        names.add(name.asString());
                    }
                }
            }
            return names;
        } catch (ProviderUnavailableException e) {
            return List.of();
        }
    }

    /** What the account can spend, in the bank's own currency (USD in Sandbox). */
    public BigDecimal availableBalance(String accessToken, String accountId) {
        JsonNode response = plaid.post("/accounts/balance/get", Map.of("access_token", accessToken, "options", Map.of("account_ids", List.of(accountId))), BANK_UNAVAILABLE);
        JsonNode balances = response.path("accounts").path(0).path("balances");
        JsonNode value = balances.hasNonNull("available") ? balances.get("available") : balances.get("current");
        if (value == null || value.isNull()) {
            throw new BadRequestException("Your bank did not share a balance for this account.");
        }
        return value.decimalValue();
    }

    /**
     * Sandbox only: put the matching line on the Sandbox bank. A positive amount is a debit (money left the bank),
     * a negative amount is a credit (money arrived).
     */
    public void postSandboxLine(String accessToken, BigDecimal signedAmount, String description) {
        if (!properties.isSandbox()) {
            return;
        }
        String today = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam")).toString();
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("date_transacted", today);
        line.put("date_posted", today);
        line.put("amount", signedAmount);
        line.put("description", description);
        line.put("iso_currency_code", "USD");
        plaid.post("/sandbox/transactions/create", Map.of("access_token", accessToken, "transactions", List.of(line)), BANK_UNAVAILABLE);
    }

    public void removeItem(String accessToken) {
        try {
            plaid.post("/item/remove", Map.of("access_token", accessToken), BANK_UNAVAILABLE);
        } catch (ProviderUnavailableException ignored) {
            // the link is ended on our side either way
        }
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asString() : null;
    }
}
