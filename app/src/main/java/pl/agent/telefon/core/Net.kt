package pl.agent.telefon.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Net {
    class HttpResult(val code: Int, val body: String, val retryAfter: String?)

    /** Zapytanie HTTPS. Anulowanie korutyny (np. przycisk „Natychmiast zatrzymaj”) od razu zrywa połączenie. */
    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        readTimeoutMs: Int = 120_000
    ): HttpResult = suspendCancellableCoroutine { cont ->
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            cont.resumeWithException(e)
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation {
            try { conn.disconnect() } catch (_: Throwable) { }
        }
        thread(name = "agent-http", isDaemon = true) {
            try {
                conn.requestMethod = method
                conn.connectTimeout = 20_000
                conn.readTimeout = readTimeoutMs
                for ((k, v) in headers) conn.setRequestProperty(k, v)
                if (body != null) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val result = HttpResult(code, text, conn.getHeaderField("Retry-After"))
                if (cont.isActive) cont.resume(result)
            } catch (e: Throwable) {
                if (cont.isActive) cont.resumeWithException(e)
            } finally {
                try { conn.disconnect() } catch (_: Throwable) { }
            }
        }
    }
}

/** Śledzi dostępność sieci (domyślna sieć systemowa). */
object NetMonitor {
    @Volatile
    var online: Boolean = true
        private set

    fun start(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        fun hasNet(n: Network?): Boolean =
            n != null && cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        publish(hasNet(cm.activeNetwork))
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    publish(true)
                }

                override fun onLost(network: Network) {
                    publish(hasNet(cm.activeNetwork))
                }
            })
        } catch (e: Exception) {
            // brak monitorowania — zakładamy, że sieć jest
        }
    }

    private fun publish(v: Boolean) {
        online = v
        AgentRuntime.update { it.copy(online = v) }
    }
}
