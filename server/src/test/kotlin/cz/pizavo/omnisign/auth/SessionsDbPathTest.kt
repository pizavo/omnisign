package cz.pizavo.omnisign.auth

import cz.pizavo.omnisign.data.repository.appConfigDirectory
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Verifies [sessionsDbFile]: the [SESSIONS_DB_FILE_PROPERTY] override names the database file while it
 * is set and not blank, and without it the server's session database is `sessions.db` in the
 * configuration directory [appConfigDirectory] resolves, beside the rest of OmniSign's per-user files.
 */
class SessionsDbPathTest : FunSpec({

    test("without the override the session database is sessions.db in the configuration directory") {
        System.getProperty(SESSIONS_DB_FILE_PROPERTY).shouldBeNull()

        sessionsDbFile() shouldBe appConfigDirectory().resolve("sessions.db").toFile()
    }

    test("the override names the session database, and a blank one leaves it in the configuration directory") {
        val file = tempfile("sessions-", ".db")
        try {
            System.setProperty(SESSIONS_DB_FILE_PROPERTY, file.absolutePath)
            sessionsDbFile() shouldBe file

            System.setProperty(SESSIONS_DB_FILE_PROPERTY, " ")
            sessionsDbFile() shouldBe appConfigDirectory().resolve("sessions.db").toFile()
        } finally {
            System.clearProperty(SESSIONS_DB_FILE_PROPERTY)
        }
    }
})
