package dev.x360reborn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val sample: Map<String, Any> = mapOf(
        "pins" to "com.a/.Main\ncom.b/.Main",
        "recent:com.a/.Main" to 1793859931297L,
        "folderSeq" to 3,
        "clock24h" to true,
        "theme" to "midnight",
        "folder:f1:name" to "Games “retro”",
        "folder:f1:members" to "com.c/.Main#10",
        "someFloat" to 1.5f,
    )

    @Test fun roundTripKeepsEveryValueAndType() {
        val back = Backup.decode(Backup.encode(sample))
        assertEquals(sample, back)
        // Types survive: a recent's timestamp must come back a Long, folderSeq an Int.
        assertTrue(back["recent:com.a/.Main"] is Long)
        assertTrue(back["folderSeq"] is Int)
        assertTrue(back["someFloat"] is Float)
    }

    @Test fun emptySettingsRoundTrip() {
        assertEquals(emptyMap<String, Any>(), Backup.decode(Backup.encode(emptyMap<String, Any>())))
    }

    @Test fun unsupportedValuesAreSkipped() {
        val back = Backup.decode(Backup.encode(mapOf("set" to setOf("a"), "theme" to "xbox")))
        assertEquals(mapOf("theme" to "xbox"), back)
    }

    private fun rejects(text: String) {
        try {
            Backup.decode(text)
            throw AssertionError("accepted: $text")
        } catch (e: Backup.BadBackup) {
            // expected
        }
    }

    @Test fun rejectsNonBackups() {
        rejects("")
        rejects("not json")
        rejects("""{"format":"something-else","version":1,"prefs":{}}""")
        rejects("""{"format":"x360reborn-settings","version":99,"prefs":{}}""")
        rejects("""{"format":"x360reborn-settings","version":1}""")
        rejects("""{"format":"x360reborn-settings","version":1,"prefs":{"k":{"type":"long","value":"x"}}}""")
        rejects("""{"format":"x360reborn-settings","version":1,"prefs":{"k":{"type":"blob","value":1}}}""")
    }
}
