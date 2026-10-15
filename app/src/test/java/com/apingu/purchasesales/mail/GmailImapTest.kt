package com.apingu.purchasesales.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GmailImapTest {
    @Test fun googleAppPasswordAllowsGrouped16Characters() {
        assertEquals("abcdefghijklmnop",
            GmailImapCredentials.requireAppPassword("abcd efgh ijkl mnop"))
    }

    @Test fun appPasswordRejectsRegularPasswordsAndEmptyValues() {
        assertThrows(IllegalArgumentException::class.java) {
            GmailImapCredentials.requireAppPassword("normal-password")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GmailImapCredentials.requireAppPassword("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GmailImapCredentials.requireAppPassword("abcd efgh ijkl")
        }
    }

    @Test fun imapAcceptsOnlyInvoicePreviewFileTypes() {
        assertEquals("application/pdf", supportedImapAttachment("supplier invoice.PDF"))
        assertEquals("image/jpeg", supportedImapAttachment("photo.JPEG"))
        assertEquals("image/png", supportedImapAttachment("photo.png"))
        assertNull(supportedImapAttachment("invoice.exe"))
        assertNull(supportedImapAttachment("invoice.docx"))
        assertNull(supportedImapAttachment("invoice"))
    }
}
