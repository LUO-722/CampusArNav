package com.campus.arnav.core

import com.campus.arnav.BuildConfig

/**
 * 高德地图运行环境。
 *
 * Key 由构建期从本机 `local.properties` 的 `amap.key` 注入（见 `app/build.gradle` 的
 * `buildConfigField AMAP_KEY` 与 AndroidManifest 里的 `${AMAP_KEY}` 占位），
 * 仓库与源码里都不出现明文 Key，和 dashscope 的注入方式一致。
 *
 * 未配置 Key（或构建机没有 local.properties）时 [ready] 为 false，
 * 地图入口应直接隐藏/降级成「字符串地址」路线，而不是创建 MapView 后崩溃。
 */
object AmapEnv {

    /** Manifest 里高德要求声明的 meta-data name，供自检/诊断引用。 */
    const val META_DATA_KEY = "com.amap.api.v2.apikey"

    /** 当前构建注入的高德 Key；没有配置时为空串。 */
    fun key(): String = BuildConfig.AMAP_KEY

    /** Key 是否已配置：false 表示地图功能不可用，UI 应降级。 */
    fun ready(): Boolean = key().isNotBlank()
}