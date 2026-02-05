package io.github.tabssh.network.portknock

import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.delay
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

/**
 * Port knocker for opening firewall ports via knock sequences
 * Supports both TCP and UDP knocking
 */
class PortKnocker {
    
    data class KnockConfig(
        val port: Int,
        val protocol: Protocol
    )
    
    enum class Protocol {
        TCP, UDP
    }
    
    companion object {
        private const val KNOCK_TIMEOUT_MS = 2000
        private const val UDP_PAYLOAD = "KNOCK"
    }
    
    /**
     * Execute a port knock sequence
     */
    suspend fun executeKnockSequence(
        host: String,
        sequence: List<KnockConfig>,
        delayBetweenKnocks: Int = 100
    ): Boolean {
        if (sequence.isEmpty()) {
            Logger.w("PortKnocker", "Empty knock sequence")
            return true
        }
        
        Logger.i("PortKnocker", "Starting knock sequence on $host: ${sequence.size} knocks")
        
        try {
            val address = InetAddress.getByName(host)
            
            sequence.forEachIndexed { index, knock ->
                val success = when (knock.protocol) {
                    Protocol.TCP -> knockTCP(address, knock.port)
                    Protocol.UDP -> knockUDP(address, knock.port)
                }
                
                if (success) {
                    Logger.d("PortKnocker", "Knock ${index + 1}: ${knock.protocol} port ${knock.port} - OK")
                } else {
                    Logger.w("PortKnocker", "Knock ${index + 1}: ${knock.protocol} port ${knock.port} - FAILED")
                    return false
                }
                
                if (index < sequence.size - 1) {
                    delay(delayBetweenKnocks.toLong())
                }
            }
            
            Logger.i("PortKnocker", "Knock sequence completed on $host")
            return true
            
        } catch (e: Exception) {
            Logger.e("PortKnocker", "Failed to execute knock sequence", e)
            return false
        }
    }
    
    private fun knockTCP(address: InetAddress, port: Int): Boolean {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(java.net.InetSocketAddress(address, port), KNOCK_TIMEOUT_MS)
            return true
        } catch (e: Exception) {
            Logger.d("PortKnocker", "TCP knock on port $port: ${e.message}")
            return true // Knock was sent even if connection failed
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
    
    private fun knockUDP(address: InetAddress, port: Int): Boolean {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = KNOCK_TIMEOUT_MS
            
            val payload = UDP_PAYLOAD.toByteArray()
            val packet = DatagramPacket(payload, payload.size, address, port)
            socket.send(packet)
            
            Logger.d("PortKnocker", "UDP knock sent to port $port")
            return true
            
        } catch (e: Exception) {
            Logger.e("PortKnocker", "UDP knock failed", e)
            return false
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
    
    fun parseKnockSequence(json: String): List<KnockConfig> {
        try {
            val list = mutableListOf<KnockConfig>()
            val pattern = """"port"\s*:\s*(\d+)\s*,\s*"protocol"\s*:\s*"(TCP|UDP)"""".toRegex()
            val matches = pattern.findAll(json)
            
            matches.forEach { match ->
                val port = match.groupValues[1].toInt()
                val protocol = Protocol.valueOf(match.groupValues[2])
                list.add(KnockConfig(port, protocol))
            }
            
            return list
        } catch (e: Exception) {
            Logger.e("PortKnocker", "Failed to parse knock sequence", e)
            return emptyList()
        }
    }
    
    fun serializeKnockSequence(sequence: List<KnockConfig>): String {
        val items = sequence.joinToString(",") { knock ->
            """{"port":${knock.port},"protocol":"${knock.protocol.name}"}"""
        }
        return "[$items]"
    }
}
