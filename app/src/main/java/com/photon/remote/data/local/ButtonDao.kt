package com.photon.remote.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.photon.remote.data.local.entity.RemoteButton

/**
 * 按键 DAO（计划 §1 data/local/ButtonDao.kt）。
 *
 * 删除设备时按键由外键 CASCADE 自动级联删除，本 DAO 额外提供显式清空方法（测试/清理兜底）。
 */
@Dao
interface ButtonDao {

    /** 某设备全部按键（按布局内顺序，`order` 为 SQLite 关键字需反引号转义） */
    @Query("SELECT * FROM remote_buttons WHERE deviceId = :deviceId ORDER BY `order` ASC, id ASC")
    suspend fun getButtonsForDevice(deviceId: Long): List<RemoteButton>

    @Query("SELECT * FROM remote_buttons WHERE id = :id")
    suspend fun getButton(id: Long): RemoteButton?

    @Insert
    suspend fun insertButton(button: RemoteButton): Long

    @Insert
    suspend fun insertButtons(buttons: List<RemoteButton>): List<Long>

    @Update
    suspend fun updateButton(button: RemoteButton)

    @Delete
    suspend fun deleteButton(button: RemoteButton)

    /** 显式删除某设备全部按键（正常情况下由外键 CASCADE 完成，此方法供迁移/清理使用） */
    @Query("DELETE FROM remote_buttons WHERE deviceId = :deviceId")
    suspend fun deleteButtonsForDevice(deviceId: Long)
}
