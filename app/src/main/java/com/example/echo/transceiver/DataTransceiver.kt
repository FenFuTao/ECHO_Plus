package com.example.echo.transceiver

/**
 * 统一数据收发接口
 *
 * 无论使用何种连接（串口、UDP、TCP客户端、TCP服务端、Demo），
 * 均通过此接口进行数据收发。
 *
 * - 接收数据：连接层收到数据后，通过 [setOnDataReceivedListener] 回调，
 *   由输出窗口统一展示。
 * - 发送数据：输出窗口打包数据后调用 [send]，由接口根据当前连接方式
 *   将数据分发至对应的发送程序。
 */
interface DataTransceiver {

    /**
     * 发送数据到连接的设备
     * @param data 要发送的原始字节数据
     */
    fun send(data: ByteArray)

    /**
     * 建立连接（串口打开、TCP连接、服务端启动等）
     * @return true 表示连接操作已发起，false 表示无法连接
     */
    fun connect(): Boolean

    /**
     * 断开连接
     */
    fun disconnect()

    /** 当前是否已连接/运行中 */
    fun isConnected(): Boolean

    /** 设置数据接收回调 */
    fun setOnDataReceivedListener(listener: ((String) -> Unit)?)

    /** 设置连接状态变化回调 */
    fun setOnStateChangedListener(listener: ((Boolean, String) -> Unit)?)

    /** 获取协议名称，如 "串口"、"UDP"、"TCP客户端"、"TCP服务端"、"Demo" */
    fun getProtocolName(): String
}
