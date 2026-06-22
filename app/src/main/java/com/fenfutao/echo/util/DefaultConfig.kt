package com.fenfutao.echo.util

/**
 * 出厂默认配置
 *
 * 所有默认值集中在此单一文件中，PanelConfig 不再持有任何默认值。
 * - 首次启动：ConfigManager.loadConfig() 读取此文件生成初始配置
 * - 重置按钮：PanelConfig.resetToDefault() 从此文件覆盖全部参数
 * - JSON 回退：PanelConfig.fromJson() 字段缺失时从此文件获取回退值
 */
object DefaultConfig {

    fun createDefault(): PanelConfig = PanelConfig(
        // ── 布局 ──
        plotWeight = 1f,
        outputWeight = 1f,
        leftPanelWeight = 2f,
        rightPanelWeight = 1f,
        uiScale = 1.0f,
        menuBarWidthDp = 40,
        primaryColorHex = "#FF2983BB",
        menuBarBgHex = "#FFBACCD9",

        // ── 数据引擎与数据接口选择 ──
        dataEngineSelection = 0,      // 0:FireWater
        protocolSelection = 0,        // 0:串口

        // ── TCP 客户端参数 ──
        tcpClientServerIp = "127.0.0.1",
        tcpClientNetworkPort = "8086",
        tcpClientHandshake = "",
        tcpClientTimeoutSeconds = 10,

        // ── UDP 参数 ──
        udpRemoteIp = "127.0.0.1",
        udpRemotePort = "8086",
        udpLocalPort = "8086",

        // ── TCP 服务端参数 ──
        tcpServerListenPort = "8086",
        tcpServerHandshake = "",

        // ── 接收区工具栏 ──
        outputShowHex = false,          // Abc
        outputShowTimestamp = false,    // 关
        outputRxHighlight = true,       // Rx 开
        outputTxHighlight = true,       // Tx 开
        outputFontSize = 11f,           // 字号 11
        outputUseGbk = false,           // UTF-8

        // ── 发送区工具栏 ──
        sendHexMode = false,            // Abc
        sendLineEndingSelection = 1,    // \n

        // ── 发送缓冲区 ──
        sendBufferText = ""
    )
}
