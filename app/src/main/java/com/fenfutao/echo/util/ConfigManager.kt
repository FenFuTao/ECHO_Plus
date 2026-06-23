package com.fenfutao.echo.util

import android.content.Context
import org.json.JSONObject
import java.io.File

data class PanelConfig(
    var plotWeight: Float = 0f,
    var outputWeight: Float = 0f,
    var leftPanelWeight: Float = 0f,
    var rightPanelWeight: Float = 0f,
    var uiScale: Float = 0f,
    var menuBarWidthDp: Int = 0,
    var primaryColorHex: String = "",
    var menuBarBgHex: String = "",
    // ── 数据引擎与数据接口选择 ──
    var dataEngineSelection: Int = 0,
    var protocolSelection: Int = 0,
    // ── TCP 客户端参数 ──
    var tcpClientServerIp: String = "",
    var tcpClientNetworkPort: String = "",
    var tcpClientHandshake: String = "",
    var tcpClientTimeoutSeconds: Int = 0,
    // ── UDP 参数 ──
    var udpRemoteIp: String = "",
    var udpRemotePort: String = "",
    var udpLocalPort: String = "",
    // ── TCP 服务端参数 ──
    var tcpServerListenPort: String = "",
    var tcpServerHandshake: String = "",
    // ── 接收区工具栏 ──
    var outputShowHex: Boolean = false,
    var outputShowTimestamp: Boolean = false,
    var outputRxHighlight: Boolean = false,
    var outputTxHighlight: Boolean = false,
    var outputFontSize: Float = 0f,
    var outputUseGbk: Boolean = false,
    // ── Rx 过滤 ──
    var rxShowSampleData: Boolean = true,
    var rxShowImagePacket: Boolean = true,
    var rxShowImageWindow: Boolean = true,
    // ── 图像子窗口位置与尺寸（冷保存）──
    var imageWindowPosX: Int = -1,
    var imageWindowPosY: Int = -1,
    var imageWindowWidth: Int = -1,
    var imageWindowHeight: Int = -1,

    // ── 发送区工具栏 ──
    var sendHexMode: Boolean = false,
    var sendLineEndingSelection: Int = 0,
    // ── 发送缓冲区 ──
    var sendBufferText: String = ""
) {
    /** 将所有字段重置为出厂默认值（来自 DefaultConfig） */
    fun resetToDefault() {
        val def = DefaultConfig.createDefault()
        plotWeight = def.plotWeight
        outputWeight = def.outputWeight
        leftPanelWeight = def.leftPanelWeight
        rightPanelWeight = def.rightPanelWeight
        uiScale = def.uiScale
        menuBarWidthDp = def.menuBarWidthDp
        primaryColorHex = def.primaryColorHex
        menuBarBgHex = def.menuBarBgHex
        dataEngineSelection = def.dataEngineSelection
        protocolSelection = def.protocolSelection
        tcpClientServerIp = def.tcpClientServerIp
        tcpClientNetworkPort = def.tcpClientNetworkPort
        tcpClientHandshake = def.tcpClientHandshake
        tcpClientTimeoutSeconds = def.tcpClientTimeoutSeconds
        udpRemoteIp = def.udpRemoteIp
        udpRemotePort = def.udpRemotePort
        udpLocalPort = def.udpLocalPort
        tcpServerListenPort = def.tcpServerListenPort
        tcpServerHandshake = def.tcpServerHandshake
        outputShowHex = def.outputShowHex
        outputShowTimestamp = def.outputShowTimestamp
        outputRxHighlight = def.outputRxHighlight
        outputTxHighlight = def.outputTxHighlight
        outputFontSize = def.outputFontSize
        outputUseGbk = def.outputUseGbk
        rxShowSampleData = def.rxShowSampleData
        rxShowImagePacket = def.rxShowImagePacket
        rxShowImageWindow = def.rxShowImageWindow
        imageWindowPosX = def.imageWindowPosX
        imageWindowPosY = def.imageWindowPosY
        imageWindowWidth = def.imageWindowWidth
        imageWindowHeight = def.imageWindowHeight

        sendHexMode = def.sendHexMode
        sendLineEndingSelection = def.sendLineEndingSelection
        sendBufferText = def.sendBufferText
    }

    companion object {
        /** 出厂默认配置（参见 DefaultConfig.kt） */
        private val default: PanelConfig by lazy { DefaultConfig.createDefault() }

        fun fromJson(obj: JSONObject?): PanelConfig {
            val d = default
            return PanelConfig().apply {
                obj?.let {
                    plotWeight = it.optDouble("plotWeight", d.plotWeight.toDouble()).toFloat()
                    outputWeight = it.optDouble("outputWeight", d.outputWeight.toDouble()).toFloat()
                    leftPanelWeight = it.optDouble("leftPanelWeight", d.leftPanelWeight.toDouble()).toFloat()
                    rightPanelWeight = it.optDouble("rightPanelWeight", d.rightPanelWeight.toDouble()).toFloat()
                    uiScale = it.optDouble("uiScale", d.uiScale.toDouble()).toFloat()
                    menuBarWidthDp = it.optInt("menuBarWidthDp", d.menuBarWidthDp)
                    primaryColorHex = it.optString("primaryColorHex", d.primaryColorHex)
                    menuBarBgHex = it.optString("menuBarBgHex", d.menuBarBgHex)
                    dataEngineSelection = it.optInt("dataEngineSelection", d.dataEngineSelection)
                    protocolSelection = it.optInt("protocolSelection", d.protocolSelection)
                    tcpClientServerIp = it.optString("tcpClientServerIp", d.tcpClientServerIp)
                    tcpClientNetworkPort = it.optString("tcpClientNetworkPort", d.tcpClientNetworkPort)
                    tcpClientHandshake = it.optString("tcpClientHandshake", d.tcpClientHandshake)
                    tcpClientTimeoutSeconds = it.optInt("tcpClientTimeoutSeconds", d.tcpClientTimeoutSeconds)
                    udpRemoteIp = it.optString("udpRemoteIp", d.udpRemoteIp)
                    udpRemotePort = it.optString("udpRemotePort", d.udpRemotePort)
                    udpLocalPort = it.optString("udpLocalPort", d.udpLocalPort)
                    tcpServerListenPort = it.optString("tcpServerListenPort", d.tcpServerListenPort)
                    tcpServerHandshake = it.optString("tcpServerHandshake", d.tcpServerHandshake)
                    outputShowHex = it.optBoolean("outputShowHex", d.outputShowHex)
                    outputShowTimestamp = it.optBoolean("outputShowTimestamp", d.outputShowTimestamp)
                    outputRxHighlight = it.optBoolean("outputRxHighlight", d.outputRxHighlight)
                    outputTxHighlight = it.optBoolean("outputTxHighlight", d.outputTxHighlight)
                    outputFontSize = it.optDouble("outputFontSize", d.outputFontSize.toDouble()).toFloat()
                    outputUseGbk = it.optBoolean("outputUseGbk", d.outputUseGbk)
                    rxShowSampleData = it.optBoolean("rxShowSampleData", d.rxShowSampleData)
                    rxShowImagePacket = it.optBoolean("rxShowImagePacket", d.rxShowImagePacket)
                    rxShowImageWindow = it.optBoolean("rxShowImageWindow", d.rxShowImageWindow)
                    imageWindowPosX = it.optInt("imageWindowPosX", d.imageWindowPosX)
                    imageWindowPosY = it.optInt("imageWindowPosY", d.imageWindowPosY)
                    imageWindowWidth = it.optInt("imageWindowWidth", d.imageWindowWidth)
                    imageWindowHeight = it.optInt("imageWindowHeight", d.imageWindowHeight)

                    sendHexMode = it.optBoolean("sendHexMode", d.sendHexMode)
                    sendLineEndingSelection = it.optInt("sendLineEndingSelection", d.sendLineEndingSelection)
                    sendBufferText = it.optString("sendBufferText", d.sendBufferText)
                }
            }
        }
    }

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
        put("tcpClientTimeoutSeconds", tcpClientTimeoutSeconds)
        put("udpRemoteIp", udpRemoteIp)
        put("udpRemotePort", udpRemotePort)
        put("udpLocalPort", udpLocalPort)
        put("tcpServerListenPort", tcpServerListenPort)
        put("tcpServerHandshake", tcpServerHandshake)
        put("outputShowHex", outputShowHex)
        put("outputShowTimestamp", outputShowTimestamp)
        put("outputRxHighlight", outputRxHighlight)
        put("outputTxHighlight", outputTxHighlight)
        put("outputFontSize", outputFontSize.toDouble())
        put("outputUseGbk", outputUseGbk)
        put("rxShowSampleData", rxShowSampleData)
        put("rxShowImagePacket", rxShowImagePacket)
        put("rxShowImageWindow", rxShowImageWindow)
        put("imageWindowPosX", imageWindowPosX)
        put("imageWindowPosY", imageWindowPosY)
        put("imageWindowWidth", imageWindowWidth)
        put("imageWindowHeight", imageWindowHeight)

        put("sendHexMode", sendHexMode)
        put("sendLineEndingSelection", sendLineEndingSelection)
        put("sendBufferText", sendBufferText)
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
        val file = getConfigFile() ?: return DefaultConfig.createDefault()
        if (!file.exists()) {
            val defaultCfg = DefaultConfig.createDefault()
            cachedConfig = defaultCfg
            saveConfig(cachedConfig!!)
            return cachedConfig!!
        }
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            PanelConfig.fromJson(json).also { cachedConfig = it }
        } catch (e: Exception) {
            AppLogger.e("ConfigManager", errMsg(e))
            DefaultConfig.createDefault().also { cachedConfig = it }
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
