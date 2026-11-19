package com.x8bit.bitwarden.data.vault.datasource.disk.database

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.bitwarden.ui.platform.base.BaseRobolectricTest
import com.x8bit.bitwarden.data.platform.manager.DatabaseSchemeManager
import com.x8bit.bitwarden.data.vault.datasource.disk.di.VaultDiskModule
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Guards the vault against data loss when the app is updated.
 *
 * SecretDoor has no server, so a vault that is wiped by a database upgrade cannot be synced back.
 * These tests build a database exactly as an older version of the app created it (from the
 * exported Room schema in `app/schemas`), open it with the real [VaultDiskModule] configuration
 * and check that the stored items survive. They also check that an upgrade with no migration path
 * fails loudly instead of silently deleting the tables.
 */
class VaultDatabaseMigrationTest : BaseRobolectricTest() {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val databaseFile: File get() = app.getDatabasePath(DATABASE_NAME)

    @Before
    fun setUp() {
        deleteDatabase()
        databaseFile.parentFile?.mkdirs()
    }

    @After
    fun tearDown() {
        deleteDatabase()
    }

    @Test
    fun `upgrading from the previous schema version keeps ciphers and folders and drops sends`() {
        createDatabaseAtVersion(version = 10) { db ->
            db.execSQL(
                "INSERT INTO ciphers (id, user_id, cipher_type, cipher_json, organization_id) " +
                    "VALUES ('cipher-1', 'user-1', 'LOGIN', '{\"name\":\"enc\"}', NULL)",
            )
            db.execSQL(
                "INSERT INTO folders (id, user_id, name, revision_date) " +
                    "VALUES ('folder-1', 'user-1', 'enc-folder', 1700000000000)",
            )
            db.execSQL(
                "INSERT INTO sends (id, user_id, send_type, send_json) " +
                    "VALUES ('send-1', 'user-1', 'TEXT', '{}')",
            )
        }

        val database = openRealDatabase()
        val ciphers = runBlocking { database.cipherDao().getAllCiphers(userId = "user-1") }
        val folders = runBlocking { database.folderDao().getAllFolders(userId = "user-1") }
        database.close()

        assertEquals(listOf("cipher-1"), ciphers.map { it.id })
        assertEquals("{\"name\":\"enc\"}", ciphers.single().cipherJson)
        assertEquals(listOf("folder-1"), folders.map { it.id })
        assertFalse(tableExists(table = "sends"))
    }

    @Test
    fun `upgrading with no migration path fails and does not delete the vault`() {
        // Version 5 predates the oldest migration; the real configuration has no fallback.
        createDatabaseAtVersion(version = 5) { db ->
            db.execSQL(
                "INSERT INTO ciphers (id, user_id, cipher_type, cipher_json) " +
                    "VALUES ('cipher-1', 'user-1', 'LOGIN', '{}')",
            )
        }

        val database = openRealDatabase()
        assertThrows(Exception::class.java) {
            runBlocking { database.cipherDao().getAllCiphers(userId = "user-1") }
        }
        runCatching { database.close() }

        // The data is still there, untouched.
        assertTrue(tableExists(table = "ciphers"))
        assertEquals(1, countRows(table = "ciphers"))
    }

    private fun openRealDatabase(): VaultDatabase =
        VaultDiskModule().provideVaultDatabase(
            app = app,
            databaseSchemeManager = mockk<DatabaseSchemeManager>(relaxed = true),
        )

    /**
     * Creates the database file as the given schema version of the app would have, then lets the
     * test add rows before it is closed.
     */
    private fun createDatabaseAtVersion(version: Int, populate: (SQLiteDatabase) -> Unit) {
        val schema = Json
            .parseToJsonElement(File("$SCHEMA_DIRECTORY/$version.json").readText())
            .jsonObject
            .getValue("database")
            .jsonObject
        val db = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        try {
            schema.getValue("entities").jsonArray.forEach { entity ->
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                db.execSQL(entity.jsonObject.sql("createSql", table))
                entity.jsonObject["indices"]?.jsonArray?.forEach {
                    db.execSQL(it.jsonObject.sql("createSql", table))
                }
            }
            (schema["setupQueries"] as? JsonArray).orEmpty().forEach {
                db.execSQL(it.jsonPrimitive.content)
            }
            populate(db)
            db.version = version
        } finally {
            db.close()
        }
    }

    private fun JsonObject.sql(key: String, table: String): String =
        getValue(key).jsonPrimitive.content.replace("\${TABLE_NAME}", table)

    private fun tableExists(table: String): Boolean =
        withRawDatabase { db ->
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(table),
            ).use { it.count > 0 }
        }

    private fun countRows(table: String): Int =
        withRawDatabase { db ->
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                it.moveToFirst()
                it.getInt(0)
            }
        }

    private fun <T> withRawDatabase(block: (SQLiteDatabase) -> T): T {
        val db = SQLiteDatabase.openDatabase(
            databaseFile.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        return try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun deleteDatabase() {
        listOf("", "-wal", "-shm", "-journal").forEach {
            File(databaseFile.path + it).delete()
        }
    }

    private companion object {
        const val DATABASE_NAME = "vault_database"
        const val SCHEMA_DIRECTORY =
            "schemas/com.x8bit.bitwarden.data.vault.datasource.disk.database.VaultDatabase"
    }
}
