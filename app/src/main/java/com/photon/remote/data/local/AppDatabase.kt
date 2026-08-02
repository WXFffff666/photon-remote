package com.photon.remote.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.local.entity.Macro
import com.photon.remote.data.local.entity.RemoteButton

/**
 * 应用数据库（计划 §1 data/local/AppDatabase.kt）。
 *
 * 三表：devices / remote_buttons / macros；版本 1；schema 导出到 app/schemas（KSP 生成）。
 */
@Database(
    entities = [Device::class, RemoteButton::class, Macro::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun deviceDao(): DeviceDao
    abstract fun buttonDao(): ButtonDao
    abstract fun macroDao(): MacroDao

    companion object {
        private const val DB_NAME = "photon_remote.db"

        @Volatile
        private var instance: AppDatabase? = null

        /** 单例获取数据库（手动 DI，AppContainer 使用） */
        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                    .fallbackToDestructiveMigration(dropAllTables = true)   // 个人应用，版本升级直接重建（后续迁移策略见计划）
                    .build()
                    .also { instance = it }
            }
    }
}
