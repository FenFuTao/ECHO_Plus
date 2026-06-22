package com.fenfutao.echo.transceiver

import com.fenfutao.echo.TcpServerManager
import com.fenfutao.echo.util.AppLogger

/**
 * TCP 服务端数据收发器
 *
 * 封装 [TcpServerManager]，通过 [DataTransceiver] 统一接口对外提供服务。
 * 输出窗口只需调用 [send] 发送数据，服务端会自动广播给所有已连接的客户端。
 */
class TcpServerTransceiver : DataTransceiver {

    private val manager = TcpServerManager()
    private var dataListener: ((String) -> Unit)? = null
    private var rawDataListener: ((ByteArray) -> Unit)? = null
    private var stateListener: ((Boolean, String) -> Unit)? = null

    // ── 连接参数 ──
    var listenPort: Int = 0
    var handshake: String = ""
        set(value) {
            field = value
            manager.handshake = value
        }
    private var countListener: ((Int) -> Unit)? = null
    private var listListener: ((List<String>) -> Unit)? = null

    /** 设置连接数变化回调 */
    fun setOnConnectionCountChangeListener(listener: ((Int) -> Unit)?) {
        countListener = listener
    }

    /** 设置客户端列表变化回调 */
    fun setOnConnectionListChangeListener(listener: ((List<String>) -> Unit)?) {
        listListener = listener
    }

    init {
        manager.setOnDataReceivedListener(TcpServerManager.OnDataReceivedListener { data ->
            dataListener?.invoke(data)
        })
        manager.setOnRawDataReceivedListener(TcpServerManager.OnRawDataReceivedListener { data, _ ->
            rawDataListener?.invoke(data)
        })
        manager.setOnServerStateChangeListener(TcpServerManager.OnServerStateChangeListener { running, message ->
            stateListener?.invoke(running, message)
        })
        manager.setOnConnectionCountChangeListener(TcpServerManager.OnConnectionCountChangeListener { count ->
            AppLogger.i("TcpServerTransceiver", "客户端连接数变化: $count")
            countListener?.invoke(count)
        })
        manager.setOnConnectionListChangeListener(TcpServerManager.OnConnectionListChangeListener { addresses ->
            listListener?.invoke(addresses)
        })
    }

    override fun send(data: ByteArray) {
        manager.broadcast(data)
    }

    override fun connect(): Boolean {
        if (manager.isRunning()) {
            AppLogger.w("TcpServerTransceiver", "TCP服务端已在运行")
            return false
        }
        return manager.start(listenPort)
    }

    override fun disconnect() {
        rawDataListener = null
        manager.stop()
    }

    override fun isConnected(): Boolean = manager.isRunning()

    override fun setOnDataReceivedListener(listener: ((String) -> Unit)?) {
        dataListener = listener
    }

    override fun setOnRawDataReceivedListener(listener: ((ByteArray) -> Unit)?) {
        rawDataListener = listener
    }

    override fun setOnStateChangedListener(listener: ((Boolean, String) -> Unit)?) {
        stateListener = listener
    }

    override fun getProtocolName(): String = "TCP服务端"

    /** 获取底层 TcpServerManager 引用（用于需要直接访问的场景） */
    fun getManager(): TcpServerManager = manager
}
