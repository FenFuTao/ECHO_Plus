package com.fenfutao.echo.transceiver

import com.fenfutao.echo.UdpManager
import com.fenfutao.echo.util.AppLogger

/**
 * UDP 数据收发器
 *
 * 封装 [UdpManager]，通过 [DataTransceiver] 统一接口对外提供服务。
 * 输出窗口只需调用 [send] 发送数据，无需关心底层 UDP 细节。
 */
class UdpTransceiver : DataTransceiver {

    private val manager = UdpManager()
    private var dataListener: ((String) -> Unit)? = null
    private var stateListener: ((Boolean, String) -> Unit)? = null

    // ── 连接参数 ──
    var remoteHost: String = ""
    var remotePort: Int = 0
    var localPort: Int = 0

    init {
        // 将 Manager 的回调桥接到统一接口的回调
        manager.setOnDataReceivedListener(UdpManager.OnDataReceivedListener { data ->
            dataListener?.invoke(data)
        })
        manager.setOnConnectionStateListener(UdpManager.OnConnectionStateListener { connected, message ->
            stateListener?.invoke(connected, message)
        })
    }

    override fun send(data: ByteArray) {
        manager.send(data)
    }

    override fun connect(): Boolean {
        if (manager.isConnected()) {
            AppLogger.w("UdpTransceiver", "UDP 已连接")
            return false
        }
        return manager.connect(remoteHost, remotePort, localPort)
    }

    override fun disconnect() {
        manager.disconnect()
    }

    override fun isConnected(): Boolean = manager.isConnected()

    /** 当前是否正在尝试连接中 */
    fun isConnecting(): Boolean = manager.isConnecting()

    override fun setOnDataReceivedListener(listener: ((String) -> Unit)?) {
        dataListener = listener
    }

    override fun setOnStateChangedListener(listener: ((Boolean, String) -> Unit)?) {
        stateListener = listener
    }

    override fun getProtocolName(): String = "UDP"

    /** 获取底层 UdpManager 引用（用于需要直接访问的场景） */
    fun getManager(): UdpManager = manager
}
