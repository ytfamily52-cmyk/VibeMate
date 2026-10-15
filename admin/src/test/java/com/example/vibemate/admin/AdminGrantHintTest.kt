package com.example.vibemate.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The admin allow-list and the "how do I become an admin" hint.
 *
 * This is the message a freshly created Firebase account sees when Firestore
 * answers 403, so it must always name the signed-in account and the exact
 * document to create — otherwise the owner is left guessing.
 */
class AdminGrantHintTest {

    @Test
    fun allowListMirrorsTheRulesAndLetsTheOwnerIn() {
        // ADMIN_EMAILS mirrors firestore.rules -> isAdmin() (e-mail + uid): it is
        // the EARLY, friendly half of the gate, the rules stay the real one.
        // (An EMPTY list means "let any valid account try and let the rules
        // answer" — handy when the admin account changes.)
        assertEquals(listOf("ngkaman143@gmail.com"), AdminConfig.ADMIN_EMAILS)
        assertTrue(AdminConfig.isAdminEmail("ngkaman143@gmail.com"))
        assertTrue(AdminConfig.isAdminEmail("  NGKAMAN143@Gmail.COM  "))
        assertFalse(AdminConfig.isAdminEmail("someone.else@example.com"))
        assertFalse(AdminConfig.isAdminEmail(""))
        assertFalse(AdminConfig.isAdminEmail("   "))
    }

    @Test
    fun hintNamesTheAccountAndTheAdminsDocumentToCreate() {
        val hint = AdminConfig.grantHint("ngkaman143@gmail.com", "uid-123")

        assertTrue(hint.contains("ngkaman143@gmail.com"))
        assertTrue(hint.contains("uid-123"))
        assertTrue(hint.contains("admins"))       // the console shortcut
        assertTrue(hint.contains("Document ID"))
        assertTrue(hint.contains("isAdmin()"))    // the rules alternative
        assertTrue(hint.contains("firestore.rules"))
    }

    @Test
    fun hintStillReadsWellWithoutAUid() {
        val hint = AdminConfig.grantHint("ngkaman143@gmail.com", "")

        assertTrue(hint.contains("ngkaman143@gmail.com"))
        assertTrue(hint.contains("Authentication"))   // where to copy the uid
        assertFalse(hint.contains("uid \n"))
    }

    @Test
    fun hintSurvivesCompletelyEmptyIdentity() {
        val hint = AdminConfig.grantHint("", "")

        assertTrue(hint.contains("admins"))
        assertTrue(hint.contains("isAdmin()"))
    }
}