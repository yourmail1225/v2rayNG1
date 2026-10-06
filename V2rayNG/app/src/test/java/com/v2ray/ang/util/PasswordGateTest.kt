package com.v2ray.ang.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordGateTest {

    @Test
    fun aRowWithoutAPasswordDoesNotAsk() {
        assertFalse(PasswordGate.requiresPassword(""))
    }

    @Test
    fun aBlankPasswordIsTreatedAsNoPassword() {
        assertFalse(PasswordGate.requiresPassword("   "))
    }

    @Test
    fun aRowWithAPasswordAsks() {
        assertTrue(PasswordGate.requiresPassword("s3cret"))
    }

    @Test
    fun theCorrectPasswordVerifies() {
        assertTrue(PasswordGate.verify("s3cret", "s3cret"))
    }

    @Test
    fun aWrongPasswordIsRejected() {
        assertFalse(PasswordGate.verify("wrong", "s3cret"))
    }

    @Test
    fun aPasswordPrefixIsRejected() {
        assertFalse(PasswordGate.verify("s3cre", "s3cret"))
    }

    @Test
    fun anEmptyInputIsRejected() {
        assertFalse(PasswordGate.verify("", "s3cret"))
    }

    @Test
    fun anEmptyInputCannotUnlockARowWithoutAPassword() {
        assertFalse(PasswordGate.verify("", ""))
    }

    @Test
    fun noInputCanUnlockARowWithoutAPassword() {
        assertFalse(PasswordGate.verify("anything", ""))
    }
}