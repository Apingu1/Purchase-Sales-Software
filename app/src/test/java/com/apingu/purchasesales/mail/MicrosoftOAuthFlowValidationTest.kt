package com.apingu.purchasesales.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrosoftOAuthFlowValidationTest {
    @Test fun exactMicrosoftRedirectIsAccepted() {
        assertTrue(MicrosoftOAuthFlowValidation.validRedirect(
            "com.apingu.purchasesales", "oauth2redirect", -1, ""
        ))
        assertTrue(MicrosoftOAuthFlowValidation.validRedirect(
            "com.apingu.purchasesales", "oauth2redirect", -1, "/"
        ))
    }

    @Test fun foreignCallbackIsRejected() {
        assertFalse(MicrosoftOAuthFlowValidation.validRedirect(
            "https", "oauth2redirect", -1, ""
        ))
        assertFalse(MicrosoftOAuthFlowValidation.validRedirect(
            "com.apingu.purchasesales", "other-host", -1, ""
        ))
        assertFalse(MicrosoftOAuthFlowValidation.validRedirect(
            "com.apingu.purchasesales", "oauth2redirect", 443, ""
        ))
        assertFalse(MicrosoftOAuthFlowValidation.validRedirect(
            "com.apingu.purchasesales", "oauth2redirect", -1, "/evil"
        ))
    }

    @Test fun oauthStateMustMatchAndCannotBeEmpty() {
        assertTrue(MicrosoftOAuthFlowValidation.sameState("state-123", "state-123"))
        assertFalse(MicrosoftOAuthFlowValidation.sameState("state-123", "state-456"))
        assertFalse(MicrosoftOAuthFlowValidation.sameState(null, "state-123"))
        assertFalse(MicrosoftOAuthFlowValidation.sameState("", ""))
        assertFalse(MicrosoftOAuthFlowValidation.sameState("state-123", null))
    }
}
