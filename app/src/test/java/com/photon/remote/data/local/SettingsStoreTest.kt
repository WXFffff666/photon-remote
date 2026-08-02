package com.photon.remote.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.photon.remote.data.model.ACStatusData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * SettingsStore 读写测试（计划 §5.9+§5.7 / Todo 7，纯 JVM 内存 DataStore）。
 *
 * 覆盖：全部设置字段读写、每设备 AC 状态独立存取、ACStatusData 字符串互转。
 */
class SettingsStoreTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: SettingsStore
    private lateinit var tempFile: File

    @Before
    fun setUp() {
        // 临时文件 DataStore（进程内作用域，测试结束后删除）
        tempFile = File.createTempFile("settings_test", ".preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(produceFile = { tempFile })
        store = SettingsStore(dataStore)
    }

    @After
    fun tearDown() {
        tempFile.delete()
    }

    @Test
    fun 默认值_正确() = runBlocking {
        assertEquals("system", store.themeMode.first())
        assertEquals(0, store.accentColor.first())
        assertTrue(store.haptic.first())
        assertEquals("auto", store.transmitterPath.first())
        assertEquals("1LED", store.audioMode.first())
        assertNull(store.acStatus(1L).first())
    }

    @Test
    fun 主题_强调色_震动_发射路径_音频模式_读写往返() = runBlocking {
        store.setThemeMode("black")
        store.setAccentColor(0xFF6750A4.toInt())
        store.setHaptic(false)
        store.setTransmitterPath("usb")
        store.setAudioMode("2LED")

        assertEquals("black", store.themeMode.first())
        assertEquals(0xFF6750A4.toInt(), store.accentColor.first())
        assertFalse(store.haptic.first())
        assertEquals("usb", store.transmitterPath.first())
        assertEquals("2LED", store.audioMode.first())
    }

    @Test
    fun AC状态_按设备独立读写() = runBlocking {
        val statusA = ACStatusData(acPower = 1, acMode = 0, acTemp = 26, acWindSpeed = 2, acWindDir = 1, changeWindDir = 0)
        val statusB = ACStatusData(acPower = 0, acMode = 3, acTemp = 18, acWindSpeed = 0, acWindDir = 0, changeWindDir = 1)

        store.setAcStatus(1L, statusA)
        store.setAcStatus(2L, statusB)

        assertEquals(statusA, store.acStatus(1L).first())
        assertEquals(statusB, store.acStatus(2L).first())
    }

    @Test
    fun AC状态_覆盖写入_后写覆盖先写() = runBlocking {
        store.setAcStatus(1L, ACStatusData(acPower = 0, acMode = 0, acTemp = 20, acWindSpeed = 0, acWindDir = 0, changeWindDir = 0))
        store.setAcStatus(1L, ACStatusData(acPower = 1, acMode = 2, acTemp = 28, acWindSpeed = 3, acWindDir = 1, changeWindDir = 1))
        assertEquals(
            ACStatusData(acPower = 1, acMode = 2, acTemp = 28, acWindSpeed = 3, acWindDir = 1, changeWindDir = 1),
            store.acStatus(1L).first(),
        )
    }

    @Test
    fun ACStatusData_存储字符串互转() {
        val status = ACStatusData(acPower = 1, acMode = 1, acTemp = 24, acWindSpeed = 1, acWindDir = 0, changeWindDir = 1)
        val raw = status.toStorageString()
        assertEquals("1,1,24,1,0,1", raw)
        assertEquals(status, ACStatusData.fromStorageString(raw))
        // 容忍空格
        assertEquals(status, ACStatusData.fromStorageString(" 1, 1, 24, 1, 0, 1 "))
    }

    @Test
    fun ACStatusData_非法字符串返回null() {
        assertNull(ACStatusData.fromStorageString(""))
        assertNull(ACStatusData.fromStorageString("1,2,3"))
        assertNull(ACStatusData.fromStorageString("1,2,3,4,5,abc"))
        assertNull(ACStatusData.fromStorageString("1,2,3,4,5,6,7"))
    }
}
