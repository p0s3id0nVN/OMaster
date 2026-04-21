package com.silas.omaster.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.silas.omaster.data.local.UpdateChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import com.silas.omaster.R

/**
 * Công cụ kiểm tra cập nhật
 * Hỗ trợ kiểm tra cập nhật hai kênh GitHub và Gitee
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    // Cấu hình GitHub
    private const val GITHUB_OWNER = "iCurrer"
    private const val GITHUB_REPO = "OMaster"
    private const val GITHUB_API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    // Cấu hình Gitee
    private const val GITEE_OWNER = "qiublog"
    private const val GITEE_REPO = "OMaster"
    private const val GITEE_API_URL = "https://gitee.com/api/v5/repos/$GITEE_OWNER/$GITEE_REPO/releases/latest"

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val downloadUrl: String,
        val releaseNotes: String,
        val isNewer: Boolean
    )

    /**
     * Kiểm tra cập nhật (chọn theo kênh)
     * @param context Ngữ cảnh
     * @param currentVersionCode Số phiên bản hiện tại
     * @param channel Kênh cập nhật, mặc định Gitee
     * @return Thông tin cập nhật, trả về null nếu thất bại
     */
    suspend fun checkUpdate(
        context: Context,
        currentVersionCode: Int,
        channel: UpdateChannel = UpdateChannel.GITEE
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        return@withContext when (channel) {
            UpdateChannel.GITEE -> checkGiteeUpdate(context, currentVersionCode)
            UpdateChannel.GITHUB -> checkGithubUpdate(context, currentVersionCode)
        }
    }

    /**
     * Kiểm tra cập nhật Gitee
     */
    private suspend fun checkGiteeUpdate(context: Context, currentVersionCode: Int): UpdateInfo? {
        return checkUpdateFromApi(context, currentVersionCode, GITEE_API_URL, isGitee = true)
    }

    /**
     * Kiểm tra cập nhật GitHub
     */
    private suspend fun checkGithubUpdate(context: Context, currentVersionCode: Int): UpdateInfo? {
        return checkUpdateFromApi(context, currentVersionCode, GITHUB_API_URL, isGitee = false)
    }

    /**
     * Logic kiểm tra API chung
     */
    private fun checkUpdateFromApi(
        context: Context,
        currentVersionCode: Int,
        apiUrl: String,
        isGitee: Boolean
    ): UpdateInfo? {
        try {
            val url = URL(apiUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                // GitHub cần header yêu cầu đặc biệt
                if (!isGitee) {
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                }
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)

                val tagName = json.getString("tag_name")
                val versionName = tagName.removePrefix("v")
                val versionCode = VersionInfo.parseVersionCode(versionName)

                // Nhận liên kết tải xuống app-universal-release.apk
                val assets = json.getJSONArray("assets")
                var downloadUrl = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val assetName = asset.getString("name")
                    // Cả hai kênh đều sử dụng tên file cố định
                    if (assetName == "app-universal-release.apk") {
                        downloadUrl = asset.getString("browser_download_url")
                        break
                    }
                }

                val releaseNotes = json.optString("body", context.getString(R.string.no_release_notes))

                return UpdateInfo(
                    versionName = versionName,
                    versionCode = versionCode,
                    downloadUrl = downloadUrl,
                    releaseNotes = releaseNotes,
                    isNewer = versionCode > currentVersionCode && downloadUrl.isNotEmpty()
                )
            } else {
                Log.e(TAG, "Kiểm tra cập nhật thất bại, mã trạng thái HTTP: ${connection.responseCode}")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi kiểm tra cập nhật [${if (isGitee) "Gitee" else "GitHub"}]", e)
            return null
        }
    }

    /**
     * Sử dụng DownloadManager của hệ thống để tải xuống và cài đặt
     * @return ID tác vụ tải xuống, dùng để truy vấn tiến trình
     */
    fun downloadAndInstall(context: Context, downloadUrl: String, versionName: String): Long {
        val fileName = "app-universal-release.apk"

        // Dọn dẹp file cũ
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        File(downloadDir, fileName).delete()

        val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
            setTitle("Cập nhật OMaster")
            setDescription("Đang tải xuống v$versionName...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return downloadManager.enqueue(request)
    }

    /**
     * Truy vấn tiến trình tải xuống
     * @return Pair<Trạng thái tải xuống, Phần trăm tiến trình> Trạng thái: 1=Đang chờ, 2=Đang tải, 4=Hoàn thành, 8=Thất bại, 16=Tạm dừng
     */
    fun queryDownloadProgress(context: Context, downloadId: Long): Pair<Int, Int> {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)

        var status = -1
        var progress = 0

        if (cursor.moveToFirst()) {
            status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))

            when (status) {
                DownloadManager.STATUS_PENDING -> {
                    progress = 0
                }
                DownloadManager.STATUS_RUNNING -> {
                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    progress = 100
                }
                DownloadManager.STATUS_FAILED -> {
                    progress = -1
                }
                DownloadManager.STATUS_PAUSED -> {
                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                }
            }
        }
        cursor.close()
        return Pair(status, progress)
    }

    /**
     * Hủy tải xuống
     */
    fun cancelDownload(context: Context, downloadId: Long) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.remove(downloadId)
    }
}

/**
 * Bộ nhận thông báo tải xuống hoàn tất (đăng ký tĩnh)
 */
class DownloadCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (downloadId == -1L) return

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)

        if (cursor.moveToFirst()) {
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                // Lấy đường dẫn file cục bộ
                val localUriString = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                Log.d("DownloadReceiver", "Tải xuống hoàn tất, URI: $localUriString")

                val apkFile = if (localUriString != null) {
                    val localUri = Uri.parse(localUriString)
                    if (localUri.scheme == "file") {
                        // Là đường dẫn file trực tiếp
                        File(localUri.path!!)
                    } else {
                        // content:// URI, thử lấy đường dẫn thực qua ContentResolver
                        getFileFromContentUri(context, localUri)
                    }
                } else {
                    // Phương án dự phòng: tìm trực tiếp tên file đã biết
                    val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    File(downloadDir, "app-universal-release.apk")
                }

                if (apkFile != null && apkFile.exists()) {
                    installApk(context, apkFile)
                } else {
                    Log.e("DownloadReceiver", "File APK không tồn tại")
                }
            } else if (status == DownloadManager.STATUS_FAILED) {
                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                Log.e("DownloadReceiver", "Tải xuống thất bại, mã lỗi: $reason")
            }
        }
        cursor.close()
    }

    private fun getFileFromContentUri(context: Context, uri: Uri): File? {
        return try {
            // Đối với file tải xuống qua DownloadManager, thường có thể phân tích trực tiếp từ URI
            if (uri.path?.contains("/Android/data/") == true) {
                // Trích xuất đường dẫn thực
                val path = uri.path
                if (path != null) {
                    File(path)
                } else null
            } else {
                // Dự phòng: truy vấn qua ContentResolver
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val displayNameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (displayNameIndex != -1) {
                            val displayName = cursor.getString(displayNameIndex)
                            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                            File(downloadDir, displayName)
                        } else null
                    } else null
                }
            }
        } catch (e: Exception) {
            Log.e("DownloadReceiver", "Phân tích đường dẫn file thất bại", e)
            null
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        try {
            Log.d("DownloadReceiver", "Chuẩn bị cài đặt APK: ${apkFile.absolutePath}, Kích thước: ${apkFile.length()}")

            val intent = Intent(Intent.ACTION_VIEW).apply {
                val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
                } else {
                    Uri.fromFile(apkFile)
                }

                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            // Kiểm tra xem có ứng dụng nào có thể xử lý intent này không
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                Log.d("DownloadReceiver", "Đã khởi chạy giao diện cài đặt")
            } else {
                Log.e("DownloadReceiver", "Không tìm thấy ứng dụng có thể xử lý cài đặt")
            }
        } catch (e: Exception) {
            Log.e("DownloadReceiver", "Cài đặt thất bại: ${e.message}", e)
        }
    }
}
