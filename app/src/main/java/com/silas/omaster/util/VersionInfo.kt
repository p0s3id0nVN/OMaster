package com.silas.omaster.util

import com.silas.omaster.BuildConfig

/**
 * Công cụ quản lý thông tin phiên bản
 * Tự động đọc số phiên bản từ BuildConfig, tránh sửa đổi nhiều nơi
 */
object VersionInfo {

    /**
     * Hiển thị số phiên bản ra bên ngoài, ví dụ "1.1.0"
     * Tương ứng với versionName trong build.gradle.kts
     */
    val VERSION_NAME: String = BuildConfig.VERSION_NAME

    /**
     * Số phiên bản nội bộ, dùng để kiểm tra cập nhật
     * Tính từ versionName: Phiên bản chính*10000 + Phiên bản phụ*100 + Phiên bản sửa đổi
     * Ví dụ: 1.1.0 -> 10100, 1.0.3 -> 10003
     */
    val VERSION_CODE: Int = parseVersionCode(VERSION_NAME)

    /**
     * Tính giá trị số tương ứng với số phiên bản
     * Dùng để so sánh phiên bản với GitHub release
     */
    fun parseVersionCode(versionName: String): Int {
        val parts = versionName.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return major * 10000 + minor * 100 + patch
    }
}
