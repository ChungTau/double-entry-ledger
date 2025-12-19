package com.chungtau.ledger_core.util;

/**
 * Utility class for masking sensitive data in logs.
 * Helps comply with PCI-DSS and banking regulations.
 */
public final class LogMaskingUtil {

    private LogMaskingUtil() {
        // Utility class, prevent instantiation
    }

    /**
     * Masks sensitive string data for logging purposes.
     * PCI-DSS compliant: shows only last 4 characters.
     *
     * @param value the sensitive value to mask
     * @return masked string showing only last 4 characters
     */
    public static String mask(String value) {
        if (value == null || value.length() < 4) {
            return "****";
        }
        return "****" + value.substring(value.length() - 4);
    }

    /**
     * Masks UUID for logging purposes.
     * Shows only the first segment of the UUID.
     *
     * @param value the UUID string to mask
     * @return masked UUID showing only first segment
     */
    public static String maskUuid(String value) {
        if (value == null || value.length() < 8) {
            return "****";
        }
        int dashIndex = value.indexOf('-');
        if (dashIndex > 0) {
            return value.substring(0, dashIndex) + "-****-****-****-****";
        }
        return value.substring(0, 8) + "****";
    }
}
