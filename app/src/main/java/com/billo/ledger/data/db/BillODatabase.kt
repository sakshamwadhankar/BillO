package com.billo.ledger.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        JournalEntryEntity::class,
        JournalLineEntity::class,
        EvidenceEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class BillODatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    abstract fun evidenceDao(): EvidenceDao

    companion object {
        @Volatile
        private var instance: BillODatabase? = null

        fun get(context: Context): BillODatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BillODatabase::class.java,
                    "billo.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
