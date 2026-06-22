package com.fenfutao.echo.transceiver

import com.fenfutao.echo.util.AppLogger

/**
 * 空数据收发器
 *
 * 用于串口、UDP、Demo 等尚未实现具体协议的占位。
 * 调用 [send] 和 [connect] 时不做实际操作，仅输出日志。
 */
class NullTransceiver(
    private val protocolName: String = "未实现"
) : DataTransceiver {

    private var connected = false
    private var dataListener: ((String) -> Unit)? = null
    private var stateListener: ((Boolean, String) -> Unit)? = null

    override fun send(data: ByteArray) {
        AppLogger.w("NullTransceiver", "[$protocolName] send 被调用，但该协议尚未实现，数据丢弃: ${data.size} bytes")
    }

    override fun connect(): Boolean {
        AppLogger.w("NullTransceiver", "[$protocolName] connect 被调用，但该协议尚未实现")
        connected = true
        stateListener?.invoke(true, "$protocolName (演示模式)")
        return true
    }

    override fun disconnect() {
        connected = false
        stateListener?.invoke(false, "$protocolName 已断开")
    }

    override fun isConnected(): Boolean = connected

    override fun setOnDataReceivedListener(listener: ((String) -> Unit)?) {
        dataListener = listener
    }

    override fun setOnStateChangedListener(listener: ((Boolean, String) -> Unit)?) {
        stateListener = listener
    }

    override fun getProtocolName(): String = protocolName
}
