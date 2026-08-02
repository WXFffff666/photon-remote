package com.photon.remote.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.photon.remote.data.local.entity.Macro
import kotlinx.coroutines.flow.Flow

/**
 * 宏 DAO（计划 §1 data/local/MacroDao.kt）。
 */
@Dao
interface MacroDao {

    /** 观察全部宏（按 sortOrder 升序） */
    @Query("SELECT * FROM macros ORDER BY sortOrder ASC, id ASC")
    fun observeMacros(): Flow<List<Macro>>

    /** 一次性读取全部宏（设备删除时的宏清理需要） */
    @Query("SELECT * FROM macros ORDER BY sortOrder ASC, id ASC")
    suspend fun getMacros(): List<Macro>

    @Query("SELECT * FROM macros WHERE id = :id")
    suspend fun getMacro(id: Long): Macro?

    @Insert
    suspend fun insertMacro(macro: Macro): Long

    @Update
    suspend fun updateMacro(macro: Macro)

    @Delete
    suspend fun deleteMacro(macro: Macro)
}
