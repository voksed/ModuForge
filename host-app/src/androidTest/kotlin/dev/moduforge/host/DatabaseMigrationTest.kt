package dev.moduforge.host

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.moduforge.core.module.ModuleState
import dev.moduforge.host.data.HostDatabase
import dev.moduforge.host.data.RoomModuleRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Opens a database written by the first released schema with the current code. */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    private val name = "migration-test.db"
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun modulesInstalledUnderSchema1SurviveTheUpgrade() = runBlocking {
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
            db.execSQL("CREATE TABLE `modules` (`id` TEXT NOT NULL, `manifestJson` TEXT NOT NULL, `state` TEXT NOT NULL, `installedAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE `grants` (`moduleId` TEXT NOT NULL, `capability` TEXT NOT NULL, `grantedAtMs` INTEGER NOT NULL, `targets` TEXT NOT NULL, PRIMARY KEY(`moduleId`, `capability`))")
            db.execSQL("CREATE TABLE `audit` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestampMs` INTEGER NOT NULL, `moduleId` TEXT NOT NULL, `type` TEXT NOT NULL, `capability` TEXT, `target` TEXT, `detail` TEXT NOT NULL)")
            db.execSQL("CREATE INDEX `index_audit_moduleId` ON `audit` (`moduleId`)")
            db.execSQL(
                "INSERT INTO modules VALUES ('com.example.old', ?, 'ENABLED', 7)",
                arrayOf("""{"id":"com.example.old","name":"Old","version":"1.0.0","sdkRange":">=1.0.0 <2.0.0","entry":"com.example.old.Main"}"""),
            )
            db.version = 1
        }

        val database = Room.databaseBuilder(context, HostDatabase::class.java, name)
            .addMigrations(HostDatabase.MIGRATION_1_2)
            .build()
        try {
            val record = RoomModuleRegistry(database.moduleDao()).find("com.example.old")!!
            assertEquals(ModuleState.ENABLED, record.state)
            assertEquals(7L, record.installedAtMs)
            assertNull(record.signer)
            assertFalse(record.autoStart)
        } finally {
            database.close()
        }
    }
}
