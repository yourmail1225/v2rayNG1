package com.v2ray.ang.ui.activation

import com.v2ray.ang.R
import com.v2ray.ang.handler.ActivationErrorKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivationErrorMappingTest {

    @Test
    fun everyErrorKindMapsToItsOwnMessage() {
        assertEquals(R.string.activation_error_network, ActivationErrorKind.NETWORK.toResId())
        assertEquals(R.string.activation_error_limit, ActivationErrorKind.LIMIT.toResId())
        assertEquals(R.string.activation_error_denied, ActivationErrorKind.DENIED.toResId())
        assertEquals(
            R.string.activation_error_no_write_token,
            ActivationErrorKind.NO_WRITE_TOKEN.toResId(),
        )
    }

    @Test
    fun nonErrorKindsFallBackToTheGenericMessage() {
        assertEquals(R.string.activation_error_generic, ActivationErrorKind.NONE.toResId())
        assertEquals(R.string.activation_error_generic, ActivationErrorKind.GENERIC.toResId())
    }
}