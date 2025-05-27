/*
 * Copyright (C) 2021 Thibault B.
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
package io.github.thibaultbee.streampack.internal.encoders

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Size
import android.view.Surface
import io.github.thibaultbee.streampack.data.Config
import io.github.thibaultbee.streampack.data.VideoConfig
import io.github.thibaultbee.streampack.internal.gl.EglWindowSurface
import io.github.thibaultbee.streampack.internal.gl.FullFrameRect
import io.github.thibaultbee.streampack.internal.gl.Texture2DProgram
import io.github.thibaultbee.streampack.internal.orientation.ISourceOrientationListener
import io.github.thibaultbee.streampack.internal.orientation.ISourceOrientationProvider
import io.github.thibaultbee.streampack.internal.utils.av.video.DynamicRangeProfile
import io.github.thibaultbee.streampack.listeners.OnErrorListener
import io.github.thibaultbee.streampack.logger.Logger
import java.util.concurrent.Executors

/**
 * Encoder for video using MediaCodec.
 *
 * @param useSurfaceMode to get video frames, if [Boolean.true],the encoder will use Surface mode, else Buffer mode with [IEncoderListener.onInputFrame].
 * @param orientationProvider to get the orientation of the source. If null, the source will keep its original dimensions.
 */
class VideoMediaCodecEncoder(
    encoderListener: IEncoderListener,
    override val onInternalErrorListener: OnErrorListener,
    private val useSurfaceMode: Boolean,
    private val orientationProvider: ISourceOrientationProvider?
) :
    MediaCodecEncoder<VideoConfig>(encoderListener) {
    val codecSurface = if (useSurfaceMode) {
        CodecSurface(orientationProvider)
    } else {
        null
    }

    private var _bitrate: Int? = null
    override var bitrate: Int = 0
        get() = _bitrate ?: super.bitrate
        set(value) {
            val bundle = Bundle()
            bundle.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, value)
            mediaCodec?.setParameters(bundle)
            field = value
            _bitrate = value
        }

    override fun onNewMediaCodec(mediaCodec: MediaCodec) {
        try {
            val mimeType = mediaCodec.outputFormat.getString(MediaFormat.KEY_MIME)!!
            val profile = mediaCodec.outputFormat.getInteger(MediaFormat.KEY_PROFILE)
            codecSurface?.useHighBitDepth =
                DynamicRangeProfile.fromProfile(mimeType, profile).isHdr
        } catch (_: Exception) {
            codecSurface?.useHighBitDepth = false
        }

        codecSurface?.outputSurface = mediaCodec.createInputSurface()
    }

    override fun createMediaFormat(config: Config, withProfileLevel: Boolean): MediaFormat {
        val videoFormat = super.createMediaFormat(config, withProfileLevel)

        if (useSurfaceMode) {
            videoFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
        } else {
            val colorFormat = if ((config as VideoConfig).dynamicRangeProfile.isHdr) {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
            } else {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            }
            videoFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                colorFormat
            )
        }
        return videoFormat
    }

    override fun extendMediaFormat(config: Config, format: MediaFormat) {
        val videoConfig = config as VideoConfig
        orientationProvider?.let {
            it.getOrientedSize(videoConfig.resolution).apply {
                // Override previous format
                format.setInteger(MediaFormat.KEY_WIDTH, width)
                format.setInteger(MediaFormat.KEY_HEIGHT, height)
            }
        }
    }

    override fun startStream() {
        codecSurface?.startStream()
        super.startStream()
    }

    override fun stopStream() {
        codecSurface?.stopStream()
        super.stopStream()
    }

    val inputSurface: Surface?
        get() = codecSurface?.inputSurface

    class CodecSurface(
        private val orientationProvider: ISourceOrientationProvider?
    ) :
        SurfaceTexture.OnFrameAvailableListener, ISourceOrientationListener {
        private var eglSurface: EglWindowSurface? = null
        private var fullFrameRect: FullFrameRect? = null
        private var textureId = -1
        // Single thread with minimal priority executor for power savings
        private val executor = Executors.newSingleThreadExecutor { r -> 
            Thread(r).apply { 
                priority = Thread.MIN_PRIORITY 
                name = "encoder-power-save-thread"
            } 
        }
        private var isRunning = false
        private var surfaceTexture: SurfaceTexture? = null
        private val stMatrix = FloatArray(16)
        
        // Power optimization: batch frame processing to reduce wake-ups - strict 24fps cap
        private var lastFrameTimeMs = 0L
        private val minFrameIntervalMs = 41L // ~24fps max to match video encoding settings

        private var _inputSurface: Surface? = null
        val inputSurface: Surface?
            get() = _inputSurface

        /**
         * If true, the encoder will use high bit depth (10 bits) for encoding.
         */
        var useHighBitDepth = false

        var outputSurface: Surface? = null
            set(value) {
                /**
                 * When surface is called twice without the stopStream(). When configure() is
                 * called twice for example,
                 */
                executor.submit {
                    if (eglSurface != null) {
                        detachSurfaceTexture()
                    }
                    synchronized(this) {
                        value?.let {
                            initOrUpdateSurfaceTexture(it)
                        }
                    }

                }.get() // Wait till executor returns
                field = value
            }

        init {
            orientationProvider?.addListener(this)
        }

        private fun initOrUpdateSurfaceTexture(surface: Surface) {
            eglSurface = ensureGlContext(EglWindowSurface(surface, useHighBitDepth)) {
                val width = it.getWidth()
                val height = it.getHeight()
                val size =
                    orientationProvider?.getOrientedSize(Size(width, height)) ?: Size(width, height)
                val orientation = orientationProvider?.orientation ?: 0
                fullFrameRect = FullFrameRect(Texture2DProgram()).apply {
                    textureId = createTextureObject()
                    setMVPMatrixAndViewPort(
                        orientation.toFloat(),
                        size,
                        orientationProvider?.mirroredVertically ?: false
                    )
                }

                val defaultBufferSize =
                    orientationProvider?.getDefaultBufferSize(size) ?: Size(width, height)
                surfaceTexture = attachOrBuildSurfaceTexture(surfaceTexture).apply {
                    setDefaultBufferSize(defaultBufferSize.width, defaultBufferSize.height)
                    setOnFrameAvailableListener(this@CodecSurface)
                }
            }
        }

        @SuppressLint("Recycle")
        private fun attachOrBuildSurfaceTexture(surfaceTexture: SurfaceTexture?): SurfaceTexture {
            return if (surfaceTexture == null) {
                SurfaceTexture(textureId).apply {
                    _inputSurface = Surface(this)
                }
            } else {
                surfaceTexture.attachToGLContext(textureId)
                surfaceTexture
            }
        }

        private fun ensureGlContext(
            surface: EglWindowSurface?,
            action: (EglWindowSurface) -> Unit
        ): EglWindowSurface? {
            surface?.let {
                it.makeCurrent()
                action(it)
                it.makeUnCurrent()
            }
            return surface
        }

        override fun onOrientationChanged() {
            executor.execute {
                synchronized(this) {
                    ensureGlContext(eglSurface) {
                        val width = it.getWidth()
                        val height = it.getHeight()

                        fullFrameRect?.setMVPMatrixAndViewPort(
                            (orientationProvider?.orientation ?: 0).toFloat(),
                            orientationProvider?.getOrientedSize(Size(width, height)) ?: Size(
                                width,
                                height
                            ),
                            orientationProvider?.mirroredVertically ?: false
                        )

                        /**
                         * Flushing spurious latest camera frames that block SurfaceTexture buffer
                         * to avoid having a misoriented frame.
                         */
                        surfaceTexture?.updateTexImage()
                        surfaceTexture?.releaseTexImage()
                    }
                }
            }
        }

        // Track how many frames we're dropping
        private var totalFramesReceived = 0L
        private var totalFramesProcessed = 0L
        private var lastLogTime = 0L
        
        // System time when the first frame was received
        private var streamStartTimeMs = 0L
        
        override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
            if (!isRunning) {
                return
            }
            
            // Initialize stream start time if needed
            if (streamStartTimeMs == 0L) {
                streamStartTimeMs = System.currentTimeMillis()
            }
            
            // Count incoming frames for statistics
            totalFramesReceived++
            
            // Get system time for frame rate control
            val currentTimeMs = System.currentTimeMillis()
            val timeSinceLastFrame = currentTimeMs - lastFrameTimeMs
            
            // CRITICAL: Skip enqueueing to executor if we're falling behind
            // This prevents executor queue buildup which is a major cause of latency
            if (timeSinceLastFrame < minFrameIntervalMs && lastFrameTimeMs > 0) {
                // Skip this frame entirely - don't even queue it
                return
            }
            
            // Queue for processing only if we're not backed up
            executor.execute {
                synchronized(this) {
                    // Check running state again after potential queue delay
                    if (!isRunning) return@synchronized
                    
                    eglSurface?.let {
                        it.makeCurrent()
                        
                        // Critical: Aggressively flush ALL pending frames to get to latest
                        // This ensures we stay current even with a burst of frames
                        var frameCount = 0
                        var lastTimestamp: Long
                        do {
                            lastTimestamp = surfaceTexture.timestamp
                            surfaceTexture.updateTexImage()
                            frameCount++
                        } while (frameCount < 20 && // Limit loop iterations for safety
                               surfaceTexture.timestamp != 0L && 
                               surfaceTexture.timestamp != lastTimestamp) // Stop when no new frames
                        
                        // Get latest transform matrix
                        surfaceTexture.getTransformMatrix(stMatrix)

                        // Draw and send the frame
                        fullFrameRect?.drawFrame(textureId, stMatrix)
                        it.setPresentationTime(surfaceTexture.timestamp)
                        it.swapBuffers()
                        lastFrameTimeMs = currentTimeMs
                        totalFramesProcessed++
                        
                        // Release texture image
                        surfaceTexture.releaseTexImage()
                        
                        // Log statistics every 5 seconds
                        if (currentTimeMs - lastLogTime > 5000) {
                            val streamTimeSeconds = (currentTimeMs - streamStartTimeMs) / 1000.0
                            val droppedFrames = totalFramesReceived - totalFramesProcessed
                            val droppedPercent = if (totalFramesReceived > 0) 
                                (droppedFrames * 100.0 / totalFramesReceived) else 0.0
                            
                            Logger.d(TAG, "Stream stats: Received=${totalFramesReceived}, " +
                                         "Processed=${totalFramesProcessed}, " +
                                         "Dropped=${droppedFrames} (${droppedPercent.toInt()}%), " +
                                         "Avg FPS=${totalFramesProcessed / streamTimeSeconds}")
                            lastLogTime = currentTimeMs
                        }
                    }
                }
            }
        }

        fun startStream() {
            // Flushing spurious latest camera frames that block SurfaceTexture buffer.
            ensureGlContext(eglSurface) {
                surfaceTexture?.updateTexImage()
            }
            isRunning = true
        }

        private fun detachSurfaceTexture() {
            ensureGlContext(eglSurface) {
                surfaceTexture?.detachFromGLContext()
                fullFrameRect?.release(true)
            }
            eglSurface?.release()
            eglSurface = null
            fullFrameRect = null
        }

        fun stopStream() {
            executor.submit {
                synchronized(this) {
                    isRunning = false
                    detachSurfaceTexture()
                }
            }.get()
        }

        fun release() {
            orientationProvider?.removeListener(this)
            stopStream()
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
            surfaceTexture = null
        }
        
        companion object {
            private const val TAG = "CodecSurface"
        }
    }
}