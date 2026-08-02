package com.photon.remote

import android.app.Application

/**
 * 应用入口（Application）。
 *
 * 后续 Todo（数据层）将在此建立手动 DI 容器（di/AppContainer）并初始化离线码库
 * （IREXT 索引 / irdb CSV），当前骨架阶段仅需一个空的 Application 类。
 */
class PhotonApplication : Application()
