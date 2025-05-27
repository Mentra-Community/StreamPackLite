/*
 * Copyright (C) 2022 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.thibaultbee.streampack.ext.rtmp.internal.endpoints

import io.github.thibaultbee.streampack.ext.rtmp.data.RtmpConnectionDescriptor
import io.github.thibaultbee.streampack.internal.data.Packet
import io.github.thibaultbee.streampack.internal.endpoints.ILiveEndpoint
import io.github.thibaultbee.streampack.listeners.OnConnectionListener
import io.github.thibaultbee.streampack.logger.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import video.api.rtmpdroid.Rtmp

class RtmpProducer(
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ILiveEndpoint {
    override var onConnectionListener: OnConnectionListener? = null

    private var socket = Rtmp()
    private var isOnError = false

    private var _isConnected = false
    override val isConnected: Boolean
        get() = _isConnected

    /**
     * Sets/gets supported video codecs.
     */
    var supportedVideoCodecs: List<String>
        get() = socket.supportedVideoCodecs
        set(value) {
            socket.supportedVideoCodecs = value
        }

    override fun configure(config: Int) {
    }

    override suspend fun connect(url: String) {
        RtmpConnectionDescriptor.fromUrl(url) // URL validation

        withContext(coroutineDispatcher) {
            try {
                isOnError = false
                socket.connect("$url live=1 flashver=FMLE/3.0\\20(compatible;\\20FMSc/1.0)")
                _isConnected = true
                onConnectionListener?.onSuccess()
            } catch (e: Exception) {
                socket = Rtmp()
                _isConnected = false
                onConnectionListener?.onFailed(e.message ?: "Unknown error")
                throw e
            }
        }
    }

    override fun disconnect() {
        synchronized(this) {
            socket.close()
            _isConnected = false
            socket = Rtmp()
        }
    }

    // Track last video packet time to manage packet queuing
    private var lastVideoTimeMs = 0L
    private var isBackpressured = false
    private var packetCounter = 0
    private var lastLogTime = 0L
    private val MAX_BACKLOG_MS = 500 // Drop frames if we're more than 500ms behind

    override fun write(packet: Packet) {
        synchronized(this) {
            if (isOnError) {
                return
            }

            if (!isConnected) {
                Logger.w(TAG, "Socket is not connected, dropping packet")
                return
            }

            val now = System.currentTimeMillis()
            
            // For video packets, implement backpressure detection and recovery
            if (packet.isVideo) {
                // Log statistics periodically
                if (packetCounter++ % 100 == 0) {
                    if (now - lastLogTime > 1000) { // Max once per second
                        Logger.d(TAG, "RTMP stats: backpressured=${isBackpressured}, " +
                                     "timeSinceLastVideo=${now - lastVideoTimeMs}ms")
                        lastLogTime = now
                    }
                }
                
                // Critical latency management: if we're severely backlogged,
                // selectively drop packets to catch up
                if (isBackpressured) {
                    // Skip some video frames when backpressured
                    // We can't easily detect keyframes in this class, so we'll use
                    // a simple sampling approach - keep 1 out of every 3 packets
                    if (packetCounter % 3 != 0) {
                        return
                    }
                }
                
                // Check if we're falling behind based on video packet timing
                if (lastVideoTimeMs > 0) {
                    val elapsed = now - lastVideoTimeMs
                    // If time between video packets is very large, we're falling behind
                    isBackpressured = elapsed > MAX_BACKLOG_MS
                }
                lastVideoTimeMs = now
            }

            try {
                socket.write(packet.buffer)
            } catch (e: Exception) {
                disconnect()
                isOnError = true
                _isConnected = false
                onConnectionListener?.onLost(e.message ?: "Socket error")
                Logger.e(TAG, "Error while writing packet to socket", e)
                throw e
            }
        }
    }

    override suspend fun startStream() {
        withContext(coroutineDispatcher) {
            synchronized(this) {
                socket.connectStream()
            }
        }
    }

    override suspend fun stopStream() {
        // No need to stop stream
    }

    override fun release() {
    }

    companion object {
        private const val TAG = "RtmpProducer"
    }
}
