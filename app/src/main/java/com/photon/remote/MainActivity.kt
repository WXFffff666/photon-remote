// com.photon.remote.MainActivity.kt —— 单 Activity，Compose 入口（计划 §1 / §6.6）
package com.photon.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.photon.remote.ui.navigation.PhotonNavHost
import com.photon.remote.ui.theme.PhotonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhotonTheme {
                // 自适应导航骨架（NavigationSuiteScaffold + NavHost：
                // home / addDevice / remote/{deviceId} / acpanel/{deviceId}）
                PhotonNavHost()
            }
        }
    }
}
