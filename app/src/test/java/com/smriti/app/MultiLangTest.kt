package com.smriti.app

import com.smriti.app.query.Intent
import com.smriti.app.query.Localizer
import com.smriti.app.query.MultiLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiLangTest {
    private val now = 1_800_000_000_000L

    @Test fun hindiWhere() {
        assertEquals(Intent.Where("keys"), MultiLang.parse("मेरी चाबी कहाँ है", "hi-IN", now))
        assertEquals(Intent.Where("phone"), MultiLang.parse("मेरा फोन कहां रखा है", "hi-IN", now))
        assertEquals(Intent.Where("pill"), MultiLang.parse("मेरी दवाई कहाँ है", "hi-IN", now))
        assertEquals(Intent.Where("glasses"), MultiLang.parse("चश्मा कहाँ है?", "hi-IN", now))
    }

    @Test fun hindiInLatinLetters() {
        assertEquals(Intent.Where("phone"), MultiLang.parse("mera phone kahan hai", "hi-IN", now))
        assertEquals(Intent.Where("keys"), MultiLang.parse("meri chabi kahan hai", "hi-IN", now))
        assertEquals(Intent.Where("laptop"), MultiLang.parse("mera laptop kahan hai", "hi-IN", now))
    }

    @Test fun hindiLiveHistoryForget() {
        assertEquals(Intent.Live, MultiLang.parse("आपको क्या दिख रहा है", "hi-IN", now))
        assertEquals(Intent.Live, MultiLang.parse("kya dikh raha hai", "hi-IN", now))
        val r = MultiLang.parse("पिछले 10 मिनट में क्या हुआ", "hi-IN", now) as Intent.Range
        assertEquals(now - 10 * 60_000L, r.fromMs)
        val d = MultiLang.parse("पिछले ५ मिनट में क्या हुआ", "hi-IN", now) as Intent.Range   // Devanagari digit
        assertEquals(now - 5 * 60_000L, d.fromMs)
        assertEquals(Intent.Forget(5), MultiLang.parse("पिछले 5 मिनट भूल जाओ", "hi-IN", now))
        assertEquals(Intent.Help, MultiLang.parse("नमस्ते", "hi-IN", now))
    }

    @Test fun otherIndianLanguagesFindTheThing() {
        assertEquals(Intent.Where("keys"), MultiLang.parse("என் சாவி எங்கே", "ta-IN", now))
        assertEquals(Intent.Where("keys"), MultiLang.parse("నా తాళం చెవి ఎక్కడ", "te-IN", now))
        assertEquals(Intent.Where("keys"), MultiLang.parse("ನನ್ನ ಬೀಗದ ಕೈ ಎಲ್ಲಿ", "kn-IN", now))
        assertEquals(Intent.Where("keys"), MultiLang.parse("എന്റെ താക്കോൽ എവിടെ", "ml-IN", now))
        assertEquals(Intent.Where("keys"), MultiLang.parse("माझी चावी कुठे आहे", "mr-IN", now))
        assertEquals(Intent.Where("keys"), MultiLang.parse("আমার চাবি কোথায়", "bn-IN", now))
        assertEquals(Intent.Where("keys"), MultiLang.parse("મારી ચાવી ક્યાં છે", "gu-IN", now))
        assertEquals(Intent.Where("phone"), MultiLang.parse("என் போன் எங்கே", "ta-IN", now))
    }

    @Test fun englishAndUnknownFallThrough() {
        assertNull(MultiLang.parse("where are my keys", "en-IN", now))
        assertNull(MultiLang.parse("ಇದು ಏನು ಸಂಬಂಧವಿಲ್ಲದ ವಾಕ್ಯ", "kn-IN", now))
    }

    @Test fun replyLanguage() {
        assertEquals("hi-IN", Localizer.replyTag("hi-IN", ttsHasVoice = true))
        // no Hindi voice on the phone: answer in English so it is never read out in the wrong accent
        assertEquals("en-IN", Localizer.replyTag("hi-IN", ttsHasVoice = false))
        // understood but not yet written in Tamil: English answer, English voice
        assertEquals("en-IN", Localizer.replyTag("ta-IN", ttsHasVoice = true))
        assertTrue(Localizer.help().contains("स्मृति"))
    }
}
