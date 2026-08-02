package com.photon.remote.codebase.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 定位解析器（计划 Todo 49：定位功能）。
 *
 * 用**原生 API**（LocationManager + Geocoder，minSdk 24 兼容）获取当前省市，
 * 不引入 Google Play Services / 第三方定位库：
 * - 定位：GPS_PROVIDER 优先、NETWORK_PROVIDER 兜底（getLastKnownLocation 即时快照，
 *   不主动开启 GPS、不弹系统定位开关）；
 * - 地理编码：Geocoder.isPresent() 可用性检查 + Locale.CHINA（保证中文行政区名）；
 * - 线程：全部在 Dispatchers.IO 执行，不阻塞主线程；
 * - 容错：无权限 / 无定位 / 定位服务关闭 / Geocoder 不可用 / 无地址信息 → 一律返回
 *   null，由调用方降级为手动选择，绝不抛异常。
 *
 * 需要运行时权限：ACCESS_FINE_LOCATION（或 COARSE 兜底），声明见 AndroidManifest.xml。
 */
class LocationResolver(private val context: Context) {

    /**
     * 解析当前所在省市。
     * @return (省名, 市名)；任一步失败返回 null（调用方降级手动选择）
     */
    @Suppress("DEPRECATION")   // getLastKnownLocation 已废弃但为 API 24 唯一可选方案
    suspend fun resolveProvinceCity(): Pair<String, String>? = withContext(Dispatchers.IO) {
        // 1) 权限防御检查（UI 层已请求；此处兜底：FINE 或 COARSE 任一授权即可）
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) return@withContext null

        // 2) 定位：GPS 优先、NETWORK 兜底（无 FINE 权限时 GPS 调用会抛 SecurityException，
        //    用 runCatching 兜住，直接走 NETWORK）
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext null
        val location = if (fineGranted) {
            runCatching { manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
                ?: runCatching { manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        } else {
            runCatching { manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        } ?: return@withContext null

        // 3) 反向地理编码：可用性检查 + 中文 locale
        if (!Geocoder.isPresent()) return@withContext null
        val addresses = runCatching {
            Geocoder(context, Locale.CHINA).getFromLocation(location.latitude, location.longitude, 1)
        }.getOrNull()
        val address = addresses?.firstOrNull() ?: return@withContext null

        // 4) 提取省市：adminArea=省；市取 locality，缺失回退 subAdminArea，再兜底省名
        val province = (address.adminArea ?: address.subAdminArea)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        var city = (address.locality ?: address.subAdminArea)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: province
        // 直辖市（北京/上海/天津/重庆）：locality 常为区县（"朝阳区"）或与省同名，
        // 归一化相等时直接以省名作为市名，让匹配层命中 irext 的"北京市"城市节点
        if (AreaNameMatcher.normalize(city) == AreaNameMatcher.normalize(province)) city = province
        province to city
    }
}
