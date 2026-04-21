package com.silas.omaster.util

import android.content.Context
import com.silas.omaster.data.config.ConfigCenter
import com.silas.omaster.data.config.SubscriptionConfig
import com.silas.omaster.model.MasterPreset
import com.silas.omaster.model.PresetList
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.IOException
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.Locale

/**
 * 【Lớp công cụ tải preset tích hợp - Ứng dụng sẽ tải lại khi cập nhật】
 * Lớp công cụ JSON - Chịu trách nhiệm tải và phân tích dữ liệu preset từ thư mục assets
 * 
 * 【Sự khác biệt quan trọng】
 * Tệp này quản lý các preset tích hợp (dữ liệu được đóng gói cùng với Ứng dụng)
 * Hoàn toàn khác với dữ liệu người dùng do CustomPresetManager quản lý
 * 
 * 【Hành vi cập nhật ứng dụng】
 * - Khi cập nhật Ứng dụng, assets/presets.json sẽ bị ghi đè bởi phiên bản mới
 * - Điều này là bình thường, vì preset tích hợp nên được cập nhật cùng với cập nhật Ứng dụng
 * - Dữ liệu người dùng (SharedPreferences) hoàn toàn không bị ảnh hưởng
 * 
 * 【Luồng dữ liệu】
 * assets/presets.json -> JsonUtil.loadPresets() -> PresetRepository -> UI 展示
 */
object JsonUtil {

    private val gson = Gson()
    
    /**
     * 【Bộ nhớ cache】
     * Lưu bộ nhớ đệm danh sách preset đã tải, tránh phân tích JSON lặp lại
     * Lưu ý: Sau khi khởi động lại Ứng dụng, bộ đệm sẽ bị xóa và cần tải lại
     */
    private var cachedPresets: List<MasterPreset>? = null

    /**
     * Phiên bản preset đang được tải
     * Mặc định là 2 (phiên bản hiện tại)
     */
    var currentPresetsVersion: Int = 2
        private set

    private const val PREFS_NAME = "json_util_prefs"
    private const val KEY_MIGRATION_DONE = "migration_done"

    /**
     * Kiểm tra xem đã hoàn tất di chuyển dữ liệu chưa
     */
    private fun isMigrationDone(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MIGRATION_DONE, false)
    }

    /**
     * Đánh dấu di chuyển dữ liệu đã hoàn tất
     */
    private fun setMigrationDone(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MIGRATION_DONE, true)
            .apply()
    }

    /**
     * 【Phương pháp tải preset tích hợp】
     * Tải tệp presets.json từ thư mục assets
     * 
     * 【Giải thích chính】
     * 1. Vị trí tệp: app/src/main/assets/presets.json
     * 2. Tệp này được đóng gói cùng với Ứng dụng, người dùng không thể sửa đổi
     * 3. Khi cập nhật Ứng dụng, tệp này sẽ bị ghi đè bởi phiên bản mới
     * 4. Sử dụng bộ đệm để tránh phân tích lặp lại
     * 
     * @param context Ngữ cảnh ứng dụng
     * @param fileName Tên tệp JSON, mặc định là "presets.json"
     * @return Danh sách preset đã phân tích, trả về danh sách trống nếu tải thất bại
     */
    fun loadPresets(context: Context, fileName: String = "presets.json"): List<MasterPreset> {
        // Nếu đã có bộ đệm, trả về trực tiếp từ bộ đệm (tối ưu hóa hiệu suất)
        cachedPresets?.let {
            Logger.d("JsonUtil", "Returning cached presets, count: ${it.size}")
            return it
        }

        // Logic đặc biệt: Kiểm tra xem có tồn tại tệp cập nhật từ xa phiên bản cũ (presets_remote.json) không
        // Nếu tồn tại và chưa hoàn tất di chuyển, có nghĩa là người dùng đang nâng cấp từ phiên bản cũ và cần được nhắc nhở di chuyển
        val oldRemoteFile = java.io.File(context.filesDir, "presets_remote.json")
        if (oldRemoteFile.exists() && !isMigrationDone(context)) {
            Logger.d("JsonUtil", "Old remote presets file detected, triggering migration")
            currentPresetsVersion = 1
        } else {
            // Nếu không tồn tại tệp cũ hoặc đã hoàn tất di chuyển, mặc định được đặt thành phiên bản mới nhất hiện tại
            currentPresetsVersion = 2
        }

        val allPresets = mutableListOf<MasterPreset>()

        val config = ConfigCenter.getInstance(context)
        val subscriptions = config.subscriptionsFlow.value

        // 1. Tải tất cả các preset đăng ký đã bật
        try {
            val enabledSubs = subscriptions.filter { it.isEnabled }

            for (sub in enabledSubs) {
                // Kiểm tra xem tệp đăng ký đã tải xuống có tồn tại không
                val subFile = java.io.File(context.filesDir, config.getSubscriptionFileName(sub.url))
                if (subFile.exists()) {
                    // Nếu tệp đăng ký tồn tại, hãy tải nó
                    try {
                        subFile.inputStream().use { inputStream ->
                            InputStreamReader(inputStream).use { reader ->
                                val presetListType = object : TypeToken<PresetList>() {}.type
                                val presetList: PresetList? = gson.fromJson(reader, presetListType)
                                if (presetList != null) {
                                    val processed = processPresets(presetList.presets ?: emptyList(), sub.url)
                                    // Lưu ý: Không còn đọc phiên bản từ tệp đăng ký để ghi đè currentPresetsVersion
                                    // currentPresetsVersion chỉ được sử dụng để phát hiện di chuyển tệp cũ presets_remote.json
                                    allPresets.addAll(processed)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e("JsonUtil", "Failed to load sub file: ${sub.url}", e)
                    }
                } else if (sub.url == SubscriptionConfig.DEFAULT_PRESET_URL) {
                    // Nếu là đăng ký chính thức nhưng tệp không tồn tại, hãy tải từ assets
                    try {
                        context.assets.open(fileName).use { inputStream ->
                            InputStreamReader(inputStream).use { reader ->
                                val presetListType = object : TypeToken<PresetList>() {}.type
                                val presetList: PresetList? = gson.fromJson(reader, presetListType)
                                if (presetList != null) {
                                    currentPresetsVersion = presetList.version
                                    val processed = processPresets(presetList.presets ?: emptyList(), "asset")
                                    allPresets.addAll(processed)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e("JsonUtil", "Failed to load presets from assets", e)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("JsonUtil", "Failed to load presets from subscriptions", e)
        }

        // Nếu không có bất kỳ preset nào, trả về rỗng
        if (allPresets.isEmpty()) return emptyList()

        cachedPresets = allPresets
        Logger.d("JsonUtil", "Total presets loaded: ${allPresets.size}")
        return allPresets
    }

    private fun processPresets(presets: List<MasterPreset>, sourceId: String): List<MasterPreset> {
        return presets.mapIndexed { index, preset ->
            // Đối với preset tích hợp chính thức, bất kể tải từ assets hay từ xa, đều giữ ID nhất quán
            val effectiveSourceId = if (sourceId == "asset" || sourceId == SubscriptionConfig.DEFAULT_PRESET_URL) {
                "official"
            } else {
                sourceId
            }

            if (preset.id == null) {
                // Nếu không có ID, hãy tạo dựa trên nguồn và chỉ mục
                val newId = generatePresetId("${effectiveSourceId}_${preset.name}", index)
                preset.copy(id = newId)
            } else {
                // Nếu có ID, để tránh xung đột giữa các đăng ký khác nhau, có thể thêm tiền tố (nếu là đăng ký từ xa)
                if (effectiveSourceId != "official") {
                    preset.copy(id = "sub_${effectiveSourceId.hashCode().toString(16)}_${preset.id}")
                } else {
                    preset
                }
            }
        }
    }

    /**
     * 【Thuật toán tạo ID】
     * Tạo ID ngắn gọn dựa trên tên preset
     * Ví dụ: "富士胶片" -> "fuji_film_0", "蓝调时刻" -> "blue_hour_1"
     * 
     * 【Các bước thuật toán】
     * 1. Xóa dấu thanh điệu (pinyin hóa)
     * 2. Chuyển đổi thành chữ thường
     * 3. Thay thế các ký tự không phải chữ và số bằng dấu gạch dưới
     * 4. Giới hạn độ dài
     * 5. Thêm hậu tố chỉ mục để tránh trùng lặp
     * 
     * @param name Tên preset
     * @param index Chỉ mục (để xử lý tên trùng lặp)
     * @return ID được tạo
     */
    private fun generatePresetId(name: String, index: Int): String {
        // 1. Xóa dấu thanh điệu (pinyin hóa)
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

        // 2. Chuyển đổi thành chữ thường
        val lowerCase = normalized.lowercase(Locale.getDefault())

        // 3. Thay thế các ký tự không phải chữ và số bằng dấu gạch dưới
        val cleaned = lowerCase.replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')  // Xóa dấu gạch dưới ở đầu và cuối
            .replace(Regex("_+"), "_")  // Nhiều dấu gạch dưới hợp nhất thành một

        // 4. Giới hạn độ dài
        val truncated = if (cleaned.length > 30) cleaned.substring(0, 30) else cleaned

        // 5. Nếu trống hoặc quá ngắn, sử dụng chỉ mục
        val baseId = if (cleaned.length < 2) "preset_$index" else truncated

        // 6. Thêm hậu tố chỉ mục để tránh trùng lặp
        return "${baseId}_$index"
    }

    /**
     * 【Phương pháp công cụ gỡ lỗi】
     * Chuyển đổi danh sách preset thành chuỗi JSON
     * Dùng để gỡ lỗi hoặc xuất dữ liệu
     * 
     * @param presets Danh sách preset
     * @return Chuỗi định dạng JSON
     */
    fun presetsToJson(presets: List<MasterPreset>): String {
        return gson.toJson(PresetList(version = currentPresetsVersion, presets = presets))
    }
    /**
     * Clear in-memory cache so subsequent calls will re-read remote or asset files.
     * Call this after remote presets file is updated.
     */
    fun invalidateCache() {
        cachedPresets = null
        Logger.d("JsonUtil", "Cache invalidated")
    }

    /**
     * Xóa tệp preset từ xa (để di chuyển dữ liệu)
     */
    fun deleteRemotePresets(context: Context) {
        try {
            val remoteFile = java.io.File(context.filesDir, "presets_remote.json")
            if (remoteFile.exists()) {
                remoteFile.delete()
                Logger.d("JsonUtil", "Deleted remote presets file for migration")
            }
            // Đánh dấu di chuyển đã hoàn tất, ngăn cửa sổ bật lên lặp lại
            setMigrationDone(context)
            Logger.d("JsonUtil", "Migration marked as done")
            invalidateCache()
        } catch (e: Exception) {
            Logger.e("JsonUtil", "Failed to delete remote presets file", e)
        }
    }
}
