package com.fenfutao.echo.transceiver

import com.fenfutao.echo.TcpClientManager
import com.fenfutao.echo.util.AppLogger

/**
 * TCP 客户端数据收发器
 *
 * 封装 [TcpClientManager]，通过 [DataTransceiver] 统一接口对外提供服务。
 * 输出窗口只需调用 [send] 发送数据，无需关心底层 TCP 连接细节。
 */
class TcpClientTransceiver : DataTransceiver {

    private val manager = TcpClientManager()
    private var dataListener: ((String) -> Unit)? = null
    private var rawDataListener: ((ByteArray) -> Unit)? = null
    private var stateListener: ((Boolean, String) -> Unit)? = null

    // ── 连接参数 ──
    var host: String = ""
    var port: Int = 0
    var handshake: String = ""
    var timeoutMs: Int = 10000

    init {
        // 将 Manager 的回调桥接到统一接口的回调
        manager.setOnDataReceivedListener(TcpClientManager.OnDataReceivedListener { data ->
            dataListener?.invoke(data)
        })
        manager.setOnRawDataReceivedListener(TcpClientManager.OnRawDataReceivedListener { data ->
            rawDataListener?.invoke(data)
        })
        manager.setOnConnectionStateListener(TcpClientManager.OnConnectionStateListener { connected, message ->
            stateListener?.invoke(connected, message)
        })
    }

    override fun send(data: ByteArray) {
        manager.send(data)
    }

    override fun connect(): Boolean {
        if (manager.isConnected()) {
            AppLogger.w("TcpClientTransceiver", "TCP客户端已连接")
            return false
        }
        return manager.connect(host, port, handshake, timeoutMs)
    }

    override fun disconnect() {
        rawDataListener = null
        manager.disconnect()
    }

    override fun isConnected(): Boolean = manager.isConnected()

    /** 当前是否正在尝试连接中 */
    fun isConnecting(): Boolean = manager.isConnecting()

    override fun setOnDataReceivedListener(listener: ((String) -> Unit)?) {
        dataListener = listener
    }

    override fun setOnRawDataReceivedListener(listener: ((ByteArray) -> Unit)?) {
        rawDataListener = listener
    }

    override fun setOnStateChangedListener(listener: ((Boolean, String) -> Unit)?) {
        stateListener = listener
    }

    override fun getProtocolName(): String = "TCP客户端"

    /** 获取底层 TcpClientManager 引用（用于需要直接访问的场景） */
    fun getManager(): TcpClientManager = manager
}
