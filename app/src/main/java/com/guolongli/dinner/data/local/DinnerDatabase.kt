package com.guolongli.dinner.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RecordEntity::class,
        MediaEntity::class,
        CommentEntity::class,
        OutboxEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class DinnerDatabase : RoomDatabase() {

    abstract fun recordDao(): RecordDao

    abstract fun outboxDao(): OutboxDao

    companion object {
        const val NAME = "dinner.db"

        /**
         * v1 → v2：记录多了位置三列。
         *
         * 手写迁移而不是 destructive：这三个列是新增的，老数据一条都不用丢，
         * 一旦用 fallbackToDestructiveMigration，用户升级一次就等于清空一次记录。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE records ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE records ADD COLUMN longitude REAL")
                db.execSQL("ALTER TABLE records ADD COLUMN place TEXT")
            }
        }

        fun build(context: Context): DinnerDatabase =
            Room.databaseBuilder(context.applicationContext, DinnerDatabase::class.java, NAME)
                // 外键级联删除要显式打开，否则删记录时媒体/评论会残留
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
