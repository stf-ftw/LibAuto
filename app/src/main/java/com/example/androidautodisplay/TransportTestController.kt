package com.example.androidautodisplay

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TransportTestController(
    private val listener: TransportTestListener,
    private val logger: TransportTestLogger
) {
    interface TransportTestListener {
        fun onStatusUpdate(status: String)
        fun onCountersUpdate(connections: Long, bytesIn: Long, bytesOut: Long)
    }

    interface TransportTestLogger {
        fun log(message: String)
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var serverPort: Int = Constants.DEFAULT_TRANSPORT_PORT

    private val connectionCount = AtomicLong(0)
    private val bytesReceived = AtomicLong(0)
    private val bytesSent = AtomicLong(0)

    fun start(port: Int) {
        if (running.getAndSet(true)) return
        serverPort = port
        connectionCount.set(0)
        bytesReceived.set(0)
        bytesSent.set(0)
        listener.onStatusUpdate("listening")
        serverThread = Thread {
            try {
                ServerSocket().use { server ->
                    serverSocket = server
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(serverPort))
                    Log.i("TransportTest", "Server listening on $serverPort")
                    logger.log("Server listening on $serverPort")
                    while (running.get()) {
                        val socket = server.accept()
                        handleClient(socket)
                    }
                }
            } catch (ex: IOException) {
                Log.e("TransportTest", "Server error: ${ex.message}")
                logger.log("Server error: ${ex.message}")
            } finally {
                running.set(false)
                listener.onStatusUpdate("stopped")
            }
        }.also { it.start() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            serverSocket?.close()
        } catch (ex: IOException) {
            Log.w("TransportTest", "Failed to close server socket: ${ex.message}")
            logger.log("Failed to close server socket: ${ex.message}")
        }
        listener.onStatusUpdate("stopped")
        logger.log("Server stopped")
    }

    fun sendTestPacket(targetHost: String, targetPort: Int, payload: ByteArray = "ping".toByteArray()) {
        Thread {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(targetHost, targetPort), 2000)
                    socket.getOutputStream().use { output ->
                        output.write(payload)
                        output.flush()
                        bytesSent.addAndGet(payload.size.toLong())
                        listener.onCountersUpdate(
                            connectionCount.get(),
                            bytesReceived.get(),
                            bytesSent.get()
                        )
                        Log.i("TransportTest", "Sent ${payload.size} bytes to $targetHost:$targetPort")
                        logger.log("Sent ${payload.size} bytes to $targetHost:$targetPort")
                    }
                }
            } catch (ex: IOException) {
                Log.e("TransportTest", "Send failed: ${ex.message}")
                logger.log("Send failed: ${ex.message}")
            }
        }.start()
    }

    fun ping(targetHost: String, targetPort: Int, callback: (Boolean, Long) -> Unit) {
        Thread {
            val start = System.currentTimeMillis()
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(targetHost, targetPort), 2000)
                }
                val latency = System.currentTimeMillis() - start
                logger.log("Connect OK to $targetHost:$targetPort in ${latency}ms")
                callback(true, latency)
            } catch (ex: IOException) {
                logger.log("Connect failed to $targetHost:$targetPort: ${ex.message}")
                callback(false, -1)
            }
        }.start()
    }

    private fun handleClient(socket: Socket) {
        Thread {
            val id = connectionCount.incrementAndGet()
            listener.onCountersUpdate(id, bytesReceived.get(), bytesSent.get())
            Log.i("TransportTest", "Client connected: ${socket.inetAddress.hostAddress}")
            logger.log("Client connected: ${socket.inetAddress.hostAddress}")
            try {
                socket.getInputStream().use { input ->
                    val buffer = ByteArray(4096)
                    while (running.get()) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        bytesReceived.addAndGet(read.toLong())
                        listener.onCountersUpdate(
                            connectionCount.get(),
                            bytesReceived.get(),
                            bytesSent.get()
                        )
                        Log.i("TransportTest", "Received $read bytes")
                        logger.log("Received $read bytes")
                    }
                }
            } catch (ex: IOException) {
                Log.w("TransportTest", "Client error: ${ex.message}")
                logger.log("Client error: ${ex.message}")
            } finally {
                try {
                    socket.close()
                } catch (_: IOException) {
                    Unit
                }
                Log.i("TransportTest", "Client disconnected")
                logger.log("Client disconnected")
            }
        }.start()
    }
}
