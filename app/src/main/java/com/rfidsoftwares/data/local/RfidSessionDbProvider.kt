
package com.rfidsoftwares.data.local

import android.content.Context
import androidx.room.Room
import com.rfidsoftwares.data.local.RfidSessionMigrations

object RfidSessionDbProvider {
    @Volatile
    private var instance: RfidSessionDatabase? = null

    fun getInstance(context: Context): RfidSessionDatabase {
        val existing = instance
        if (existing != null) return existing

        return synchronized(this) {
            val again = instance
            if (again != null) return again

            val db = Room.databaseBuilder(
                context.applicationContext,
                RfidSessionDatabase::class.java,
                "rfid_session_db"
            )
                .addMigrations(*RfidSessionMigrations.ALL)
                // Phase 2 controller runs reads/writes on background threads.
                .build()

            instance = db
            db
        }
    }
}

