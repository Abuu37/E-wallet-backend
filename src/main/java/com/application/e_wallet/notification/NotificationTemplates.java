package com.application.e_wallet.notification;

import java.util.EnumMap;
import java.util.Map;

/** The wording of every message (sms-notifications.md). {name} style placeholders are filled from the params. */
public final class NotificationTemplates {

    private static final Map<NotificationKey, String> TEXT = new EnumMap<>(NotificationKey.class);

    static {
        TEXT.put(NotificationKey.S1, "WalletPesa code: {code}. Expires in {minutes} min. Don't share it.");
        TEXT.put(NotificationKey.S2, "WalletPesa PIN reset code: {code}. Expires in {minutes} min.");
        TEXT.put(NotificationKey.S3, "Your WalletPesa PIN was changed. All devices were signed out. Not you? Call 0800 000 000.");
        TEXT.put(NotificationKey.S4, "Welcome to WalletPesa, {name} ({agentId}). One-time PIN: {pin}. Sign in and set your own PIN.");
        TEXT.put(NotificationKey.S5, "You were added to WalletPesa staff. One-time PIN: {pin}. Sign in and set your own PIN.");
        TEXT.put(NotificationKey.S6, "Your identity is verified. New limits: {perPayment} per payment, {daily} per day.");
        TEXT.put(NotificationKey.S7, "We couldn't verify your ID. Open the app to try again.");
        TEXT.put(NotificationKey.S8, "Your WalletPesa account is on hold. Call 0800 000 000.");
        TEXT.put(NotificationKey.S9, "Too many wrong PINs. Your account is locked for 30 min.");

        TEXT.put(NotificationKey.C1, "You added TZS {amount} from {bank} ••{mask}. Ref {ref}. New balance {balance}.");
        TEXT.put(NotificationKey.C2, "You received TZS {amount} cash-in from {agent} ({agentId}). Ref {ref}. New balance {balance}.");
        TEXT.put(NotificationKey.C3, "You sent TZS {amount} to {name} {phone}. Ref {ref}. New balance {balance}.");
        TEXT.put(NotificationKey.C4, "You received TZS {amount} from {name} {phone}. Ref {ref}. New balance {balance}.");
        TEXT.put(NotificationKey.C5, "You withdrew TZS {amount} at {agent} (till {till}). Fee {fee}. Code {code}. Ref {ref}. New balance {balance}.");
        TEXT.put(NotificationKey.C6, "TZS {amount} is on its way to {bank} ••{mask}. Fee {fee}. Ref {ref}. New balance {balance}.");
        TEXT.put(NotificationKey.C7, "Your transfer to {bank} ••{mask} failed. TZS {amount} is back in your wallet. Ref {ref}.");

        TEXT.put(NotificationKey.A1, "Cash-in TZS {amount} to {name}. Ref {ref}. Commission +{commission}. Float {float}.");
        TEXT.put(NotificationKey.A2, "Withdrawal TZS {amount} by {name}. Code {code}. Hand over cash. Commission +{commission}. Float {float}.");
        TEXT.put(NotificationKey.A3, "Float topped up TZS {amount} from {bank} ••{mask}. Ref {ref}. Float {float}.");
        TEXT.put(NotificationKey.A4, "We got your float request of TZS {amount} (Ref {ref}). We'll email you once it's checked.");
        TEXT.put(NotificationKey.A5, "Float request {ref} approved. TZS {amount} added. Float {float}.");
        TEXT.put(NotificationKey.A6, "Float request {ref} was not approved: {reason}. Call 0800 000 000.");
        TEXT.put(NotificationKey.A7, "TZS {amount} sent from float to {bank} ••{mask}. Ref {ref}. Float {float}.");
        TEXT.put(NotificationKey.A8, "Commission TZS {amount} sent to {bank} ••{mask}. Ref {ref}.");
        TEXT.put(NotificationKey.A9, "Your float is TZS {float}. Top up to keep serving customers.");

        TEXT.put(NotificationKey.T1, TEXT.get(NotificationKey.S5));
        TEXT.put(NotificationKey.T2, "Reconciliation alert: wallets and escrow differ by TZS {difference} for {date}. Check the console.");
    }

    private NotificationTemplates() {
    }

    public static String message(NotificationKey key, Map<String, String> params) {
        String text = TEXT.get(key);
        if (text == null) {
            throw new IllegalArgumentException("No template for " + key);
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            text = text.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return text;
    }

    /** Email needs a subject; SMS ignores it. */
    public static String subject(NotificationKey key) {
        return switch (key) {
            case S1 -> "Your WalletPesa verification code";
            case S2 -> "Reset your WalletPesa PIN";
            case S3 -> "Your WalletPesa PIN was changed";
            case S4, S5, T1 -> "Your WalletPesa one-time PIN";
            case S6, S7, S8, S9 -> "About your WalletPesa account";
            default -> "WalletPesa notification";
        };
    }
}
