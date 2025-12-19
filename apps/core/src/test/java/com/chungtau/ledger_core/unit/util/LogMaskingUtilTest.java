package com.chungtau.ledger_core.unit.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.chungtau.ledger_core.util.LogMaskingUtil;

class LogMaskingUtilTest {

    @Test
    void mask_ShouldMaskString_WhenLengthIsSufficient() {
        assertEquals("****5678", LogMaskingUtil.mask("12345678"));
        assertEquals("****", LogMaskingUtil.mask("123"));
        assertEquals("****", LogMaskingUtil.mask(null));
    }

    @Test
    void maskUuid_ShouldMaskCorrectly() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        String expected = "550e8400-****-****-****-****";
        assertEquals(expected, LogMaskingUtil.maskUuid(uuid));
        
        assertEquals("****", LogMaskingUtil.maskUuid("invalid"));
        assertEquals("****", LogMaskingUtil.maskUuid(null));
    }
}