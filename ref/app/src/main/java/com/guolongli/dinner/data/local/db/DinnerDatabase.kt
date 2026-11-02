package com.guolongli.dinner.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.guolongli.dinner.data.local.db.dao.CommentDao
import com.guolongli.dinner.data.local.db.dao.MealRecordDao
import com.guolongli.dinner.data.local.db.entity.CommentEntity
import com.guolongli.dinner.data.local.db.entity.MealRecordEntity

/**
 * 本地库。
 *
 * 2.0.0 起版本重置为 1：表结构只有一种形态（没有单媒体旧列），
 * 新安装直接建表，不做任何历史迁移，因此这里也不保留任何 Migration。
 */
@Database(
    entities = [MealRecordEntity::class, CommentEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class DinnerDatabase : RoomDatabase() {
    abstract fun mealRecordDao(): MealRecordDao
    abstract fun commentDao(): CommentDao

    companion object {
        const val NAME = "dinner.db"
    }
}
