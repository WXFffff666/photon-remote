package com.photon.remote.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.photon.remote.data.local.entity.Device
import kotlinx.coroutines.flow.Flow

/**
 * 设备 DAO（计划 §1 data/local/DeviceDao.kt）。
 *
 * 列表统一按 sortOrder 升序（插入顺序为序），收藏切换由 Repository 提供。
 */
@Dao
interface DeviceDao {

    /** 观察全部设备（按 sortOrder 升序，同序按 id） */
    @Query("SELECT * FROM devices ORDER BY sortOrder ASC, id ASC")
    fun observeDevices(): Flow<List<Device>>

    /** 一次性读取全部设备（排序与 observeDevices 一致，供排序重排使用） */
    @Query("SELECT * FROM devices ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllDevices(): List<Device>

    @Query("SELECT * FROM devices WHERE id = :id")
    suspend fun getDevice(id: Long): Device?

    @Insert
    suspend fun insertDevice(device: Device): Long

    @Update
    suspend fun updateDevice(device: Device)

    @Delete
    suspend fun deleteDevice(device: Device)

    @Query("UPDATE devices SET name = :name WHERE id = :id")
    suspend fun renameDevice(id: Long, name: String)

    @Query("UPDATE devices SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Query("UPDATE devices SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)
}
