package com.photon.remote

import android.app.Application
import com.photon.remote.di.AppContainer

/**
 * 应用入口（Application）。
 *
 * 手动 DI 容器（di/AppContainer）在此惰性创建并暴露给页面 ViewModel：
 * 页面通过 `(application as PhotonApplication).container` 取依赖
 * （计划 §1 / D5：单模块 + 手动 DI，无 Hilt）。
 */
class PhotonApplication : Application() {

    /** 全局手动 DI 容器（首次访问时创建，App 生命周期内单例） */
    val container: AppContainer by lazy { AppContainer(this) }
}
