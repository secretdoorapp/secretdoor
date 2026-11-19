package com.x8bit.bitwarden.data.credentials.datasource.disk.database

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.bitwarden.ui.platform.base.BaseRobolectricTest
import com.x8bit.bitwarden.data.credentials.datasource.disk.di.CredentialsDiskModule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Guards the list of apps the user chose to trust against being erased when the app is updated.
 *
 * The database is built exactly as the released schema created it (from the exported Room schema
 * in `app/schemas`) and opened with the real [CredentialsDiskModule] configuration.
 */
class PrivilegedAppDatabaseMigrationTest : BaseRobolectricTest() {

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
    fun `a database created by the released schema opens and keeps its data`() {
        createDatabaseAtVersion(version = 1) { db ->
            db.execSQL(
                "INSERT INTO privileged_apps (package_name, signature) " +
                    "VALUES ('com.example.browser', 'abc123')",
            )
        }

        val database = CredentialsDiskModule.providePrivilegedAppDatabase(app = app)
        val apps = runBlocking { database.privilegedAppDao().getAllUserTrustedPrivilegedApps() }
        database.close()

        assertEquals(listOf("com.example.browser"), apps.map { it.packageName })
    }

    @Test
    fun `a database that cannot be migrated fails and does not lose its data`() {
        // A version this build knows nothing about (as after a downgrade): there is no migration
        // path, and the real configuration has no destructive fallback.
        createDatabaseAtVersion(version = 1, userVersion = 99) { db ->
            db.execSQL(
                "INSERT INTO privileged_apps (package_name, signature) " +
                    "VALUES ('com.example.browser', 'abc123')",
            )
        }

        val database = CredentialsDiskModule.providePrivilegedAppDatabase(app = app)
        assertThrows(Exception::class.java) {
            runBlocking { database.privilegedAppDao().getAllUserTrustedPrivilegedApps() }
        }
        runCatching { database.close() }

        assertEquals(1, countRows(table = "privileged_apps"))
    }

    private fun createDatabaseAtVersion(
        version: Int,
        userVersion: Int = version,
        populate: (SQLiteDatabase) -> Unit,
    ) {
        val schema = Json
            .parseToJsonElement(File("$SCHEMA_DIRECTORY/$version.json").readText())
            .jsonObject
            .getValue("database")
            .jsonObject
        val db = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        try {
            schema.getValue("entities").jsonArray.forEach { entity ->
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                db.execSQL(
                    entity.jsonObject.getValue("createSql").jsonPrimitive.content
                        .replace("\${TABLE_NAME}", table),
                )
            }
            (schema["setupQueries"] as? JsonArray).orEmpty().forEach {
                db.execSQL(it.jsonPrimitive.content)
            }
            populate(db)
            db.version = userVersion
        } finally {
            db.close()
        }
    }

    private fun countRows(table: String): Int {
        val db = SQLiteDatabase.openDatabase(
            databaseFile.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        return try {
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                it.moveToFirst()
                it.getInt(0)
            }
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
        const val DATABASE_NAME = "privileged_apps_database"
        const val SCHEMA_DIRECTORY =
            "schemas/" +
                "com.x8bit.bitwarden.data.credentials.datasource.disk.database.PrivilegedAppDatabase"
    }
}
