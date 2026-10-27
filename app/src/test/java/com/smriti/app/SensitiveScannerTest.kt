package com.smriti.app

import com.smriti.app.privacy.SensitiveKind
import com.smriti.app.privacy.SensitiveScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveScannerTest {
    private fun kinds(text: String, faces: Int = 0, objs: List<String> = emptyList()) = SensitiveScanner.scan(text, faces, objs).map { it.kind }

    @Test fun cardNumberNeedsAValidLuhnSum() {
        assertTrue(SensitiveKind.CARD in kinds("Card 4111 1111 1111 1111 exp"))
        assertFalse(SensitiveKind.CARD in kinds("Order 4111 1111 1111 1112"))
    }

    @Test fun twelveDigitIdIsClassified() {
        assertTrue(SensitiveKind.ID_NUMBER in kinds("2345 6789 0123"))
    }

    @Test fun phoneEmailPlate() {
        val k = kinds("call 98765 43210 or mail dhanesh@example.com car KA01AB1234")
        assertTrue(SensitiveKind.PHONE in k)
        assertTrue(SensitiveKind.EMAIL in k)
        assertTrue(SensitiveKind.PLATE in k)
    }

    @Test fun namesAndSecrets() {
        val f = SensitiveScanner.scan("Name: Dhanesh Kumar  OTP 482913", 0, emptyList())
        assertTrue(f.any { it.kind == SensitiveKind.PERSON && it.masked == "D•••••• K••••" })
        assertTrue(f.any { it.kind == SensitiveKind.SECRET })
    }

    @Test fun faceAndScreenAndDocument() {
        val k = kinds("Government of India Aadhaar enrolment number printed on this card", faces = 2, objs = listOf("laptop"))
        assertTrue(SensitiveKind.FACE in k)
        assertTrue(SensitiveKind.DOCUMENT in k)
        assertTrue(SensitiveKind.SCREEN in k)
    }

    @Test fun theRealValueIsNeverInTheMaskedPreview() {
        val all = SensitiveScanner.scan("4111 1111 1111 1111 dhanesh@example.com Name: Dhanesh Kumar 9876543210", 0, emptyList()).joinToString { it.masked }
        for (secret in listOf("4111", "1111", "dhanesh", "example", "hanesh", "umar", "876543210")) assertFalse("leaked $secret in $all", all.contains(secret))
    }

    @Test fun plainTextHasNoFindings() {
        assertEquals(emptyList<SensitiveKind>(), kinds("a red cup on a wooden table"))
    }
}
