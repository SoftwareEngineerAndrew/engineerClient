package com.adm.utils.network

import com.adm.AdmMod
import com.adm.AdmMod.logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

fun webSocket(func: WebSocketConnection.() -> Unit) = WebSocketConnection().apply(func)

class WebSocketConnection {
    private val _webSocket = AtomicReference<WebSocket?>(null)
    private val generation = AtomicInteger()
    private val httpClient by lazy { HttpClient.newHttpClient() }
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "Odin-WebSocket").apply { isDaemon = true } }
    private var onMessageFunc: (String) -> Unit = { }

    fun onMessage(func: (String) -> Unit) {
        onMessageFunc = func
    }

    val connected get() = _webSocket.get() != null

    /** ADM's party link: takes a message first; true when it has it, and sends it through [sendSocket] itself if needed. */
    var bridge: ((String) -> Boolean)? = null

    /** A message that came over ADM's party link, handled as if the socket had brought it. */
    fun receive(message: String) = onMessageFunc(message)

    fun send(message: String): Boolean {
        if (bridge?.invoke(message) == true) return true
        return sendSocket(message)
    }

    fun sendSocket(message: String): Boolean {
        val ws = _webSocket.get()
        if (ws == null) {
            logger.warn("Cannot send message: WebSocket not connected")
            return false
        }
        executor.execute {
            try {
                ws.sendText(message, true).get(10, TimeUnit.SECONDS)
            } catch (e: Exception) {
                logger.error("Failed to send WebSocket message: ${e.message}", e)
            }
        }
        return true
    }

    private fun deliver(message: String) {
        try {
            onMessageFunc(message)
        } catch (e: Exception) {
            logger.error("WebSocket message handler failed: ${e.message}", e)
        }
    }

    fun connect(url: String) {
        val gen = generation.incrementAndGet()
        executor.execute { open(url, gen) }
    }

    private fun open(url: String, gen: Int) {
        if (generation.get() != gen) return
        _webSocket.getAndSet(null)?.sendClose(1000, "Reconnecting")

        val listener = object : WebSocket.Listener {
            private val messageBuilder = StringBuilder()
            private val binaryBuffer = ByteArrayOutputStream()

            override fun onOpen(webSocket: WebSocket) {
                val old = synchronized(generation) {
                    if (generation.get() != gen) null.also { webSocket.sendClose(1000, "Superseded") }
                    else _webSocket.getAndSet(webSocket) ?: webSocket
                } ?: return
                if (old !== webSocket) old.sendClose(1000, "Superseded")
                logger.info("WebSocket connected to $url")
                webSocket.request(1)
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                messageBuilder.append(data)
                if (last) {
                    val message = messageBuilder.toString()
                    messageBuilder.clear()
                    if (_webSocket.get() === webSocket) deliver(message)
                }
                webSocket.request(1)
                return null
            }

            override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                binaryBuffer.write(bytes)
                if (last) {
                    val text = String(binaryBuffer.toByteArray(), StandardCharsets.UTF_8)
                    binaryBuffer.reset()
                    if (_webSocket.get() === webSocket) deliver(text)
                }
                webSocket.request(1)
                return null
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                logger.info("WebSocket closed: $statusCode / $reason")
                _webSocket.compareAndSet(webSocket, null)
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                logger.error("WebSocket error: ${error.message}", error)
                _webSocket.compareAndSet(webSocket, null)
            }
        }

        httpClient.newWebSocketBuilder()
            .header("X-Mod-Version", AdmMod.version.friendlyString)
            .buildAsync(URI.create(url), listener)
            .exceptionally { error ->
                logger.error("Failed to connect WebSocket: ${error.message}", error)
                null
            }
    }

    fun shutdown() {
        val ws = synchronized(generation) {
            generation.incrementAndGet()
            _webSocket.getAndSet(null)
        } ?: return
        executor.execute { ws.sendClose(1000, "Client shutdown") }
    }
}