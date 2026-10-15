package com.apingu.purchasesales.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailConnectionSettingsTest {
    @Test fun microsoftClientIdIsPublicUuidNotSecret() {
        assertTrue(MailConnectionSettings.isValidMicrosoftClientId("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"))
        assertTrue(MailConnectionSettings.isValidMicrosoftClientId(" AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE "))
    }

    @Test fun missingInvalidOrClientSecretValuesDoNotEnableMicrosoftOauth() {
        assertFalse(MailConnectionSettings.isValidMicrosoftClientId(""))
        assertFalse(MailConnectionSettings.isValidMicrosoftClientId("not-an-app-id"))
        assertFalse(MailConnectionSettings.isValidMicrosoftClientId("secret-value"))
        assertFalse(MailConnectionSettings.isValidMicrosoftClientId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/"))
    }
}
