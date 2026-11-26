package com.application.e_wallet.notification;

import java.math.BigDecimal;
import java.text.DecimalFormat;

/** How amounts and phone numbers appear in messages: TZS 50,000 and 0754 ••• 210. Never a full account number. */
public final class NotificationFormat {

    private NotificationFormat() {
    }

    public static String tzs(BigDecimal amount) {
        if (amount == null) {
            return "0";
        }
        return new DecimalFormat("#,##0.##").format(amount);
    }

    /** +255754123210 becomes 0754 ••• 210. */
    public static String maskedPhone(String e164) {
        if (e164 == null) {
            return "";
        }
        String digits = e164.replaceAll("\\D", "");
        if (digits.length() < 9) {
            return "";
        }
        String local = "0" + digits.substring(digits.length() - 9);
        return local.substring(0, 4) + " ••• " + local.substring(7);
    }

    /** Till numbers are shown in groups: 220341 becomes 220 341. */
    public static String till(String tillNumber) {
        if (tillNumber == null || tillNumber.length() != 6) {
            return tillNumber == null ? "" : tillNumber;
        }
        return tillNumber.substring(0, 3) + " " + tillNumber.substring(3);
    }
}
