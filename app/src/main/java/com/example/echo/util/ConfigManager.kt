package com.example.echo.util

import android.content.Context
import org.json.JSONObject
import java.io.File

data class PanelConfig(
    var plotWeight: Float = 1f,
    var outputWeight: Float = 1f,
    var leftPanelWeight: Float = 2f,
    var rightPanelWeight: Float = 1f,
    var uiScale: Float = 1.0f,
    var menuBarWidthDp: Int = 40,
    var primaryColorHex: String = "#FF2983BB",
    var menuBarBgHex: String = "#FFBACCD9",
    // ── 数据引擎与数据接口选择 ──
    var dataEngineSelection: Int = 0,
    var protocolSelection: Int = 0,
    // ── TCP 客户端参数 ──
    var tcpClientServerIp: String = "",
    var tcpClientNetworkPort: String = "",
    var tcpClientHandshake: String = "plot0",
    // ── TCP 服务端参数 ──
    var tcpServerListenPort: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("plotWeight", plotWeight.toDouble())
        put("outputWeight", outputWeight.toDouble())
        put("leftPanelWeight", leftPanelWeight.toDouble())
        put("rightPanelWeight", rightPanelWeight.toDouble())
        put("uiScale", uiScale.toDouble())
        put("menuBarWidthDp", menuBarWidthDp)
        put("primaryColorHex", primaryColorHex)
        put("menuBarBgHex", menuBarBgHex)
        put("dataEngineSelection", dataEngineSelection)
        put("protocolSelection", protocolSelection)
        put("tcpClientServerIp", tcpClientServerIp)
        put("tcpClientNetworkPort", tcpClientNetworkPort)
        put("tcpClientHandshake", tcpClientHandshake)
        put("tcpServerListenPort", tcpServerListenPort)
    }

    companion object {
        fun fromJson(obj: JSONObject?): PanelConfig = PanelConfig().apply {
            obj?.let {
                plotWeight = it.optDouble("plotWeight", 1.0).toFloat()
                outputWeight = it.optDouble("outputWeight", 1.0).toFloat()
                leftPanelWeight = it.optDouble("leftPanelWeight", 2.0).toFloat()
                rightPanelWeight = it.optDouble("rightPanelWeight", 1.0).toFloat()
                uiScale = it.optDouble("uiScale", 1.0).toFloat()
                menuBarWidthDp = it.optInt("menuBarWidthDp", 40)
                primaryColorHex = it.optString("primaryColorHex", "#FF2983BB")
                menuBarBgHex = it.optString("menuBarBgHex", "#FFBACCD9")
                dataEngineSelection = it.optInt("dataEngineSelection", 0)
                protocolSelection = it.optInt("protocolSelection", 0)
                tcpClientServerIp = it.optString("tcpClientServerIp", "")
                tcpClientNetworkPort = it.optString("tcpClientNetworkPort", "")
                tcpClientHandshake = it.optString("tcpClientHandshake", "plot0")
                tcpServerListenPort = it.optString("tcpServerListenPort", "")
            }
        }
    }
}

object ConfigManager {

    private const val CONFIG_FILE = "config.json"

    private var configDir: File? = null
    private var cachedConfig: PanelConfig? = null

    fun init(context: Context) {
        configDir = context.getExternalFilesDir(null)
    }

    fun loadConfig(): PanelConfig {
        cachedConfig?.let { return it }
        val file = getConfigFile() ?: return PanelConfig()
        if (!file.exists()) {
            cachedConfig = PanelConfig()
            saveConfig(cachedConfig!!)
            return cachedConfig!!
        }
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            PanelConfig.fromJson(json).also { cachedConfig = it }
        } catch (e: Exception) {
            AppLogger.e("ConfigManager", errMsg(e)); PanelConfig().also { cachedConfig = it }
        }
    }

    private fun errMsg(e: Exception) = "读取配置失败: ${e.message}"

    fun saveConfig(config: PanelConfig) {
        cachedConfig = config
        val file = getConfigFile() ?: return
        try {
            file.parentFile?.mkdirs()
            file.writeText(config.toJson().toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            AppLogger.e("ConfigManager", "保存配置失败: ${e.message}")
        }
    }

    private fun getConfigFile(): File? = configDir?.let { File(it, CONFIG_FILE) }
}
