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
package io.github.thibaultbee.streampack.internal.sources.camera

import android.Manifest
import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.CameraDevice.AUDIO_RESTRICTION_NONE
import android.hardware.camera2.CameraDevice.AUDIO_RESTRICTION_VIBRATION_SOUND
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.params.OutputConfiguration
import android.os.Build
import android.util.Range
import android.view.Surface
import androidx.annotation.RequiresPermission
import io.github.thibaultbee.streampack.error.CameraError
import io.github.thibaultbee.streampack.logger.Logger
import io.github.thibaultbee.streampack.utils.getCameraFpsList
import kotlinx.coroutines.*
import java.security.InvalidParameterException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraController(
    private val context: Context,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private var camera: CameraDevice? = null
    val cameraId: String?
        get() = camera?.id

    private var captureSession: CameraCaptureSession? = null
    private var captureRequest: CaptureRequest.Builder? = null

    private val threadManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        CameraExecutorManager()
    } else {
        CameraHandlerManager()
    }

    private fun getClosestFpsRange(cameraId: String, fps: Int): Range<Int> {
        var fpsRangeList = context.getCameraFpsList(cameraId)
        Logger.i(TAG, "Supported FPS range list: $fpsRangeList")

        // Power optimization - try to use a low FPS range to save power
        // First try to find a fixed range at a low FPS (15fps)
        val targetLowFps = 15
        val lowFpsFixedRange = fpsRangeList.find { it.lower == it.upper && it.lower == targetLowFps }
        
        if (lowFpsFixedRange != null) {
            Logger.d(TAG, "Found low fixed fps range: $lowFpsFixedRange")
            return lowFpsFixedRange
        }
        
        // Try to find a range that includes our target fps
        fpsRangeList = fpsRangeList.filter { it.contains(fps) }
        if (fpsRangeList.isEmpty()) {
            // If no range contains our target fps, use the original list
            fpsRangeList = context.getCameraFpsList(cameraId)
        }
        
        // Look for a range with a lower bound not higher than our target fps
        val suitableRanges = fpsRangeList.filter { it.lower <= fps }
        if (suitableRanges.isNotEmpty()) {
            // Get the range with lower bound closest to our target fps
            val selectedRange = suitableRanges.minWith(compareBy { fps - it.lower })
            Logger.d(TAG, "Using range with lower bound close to target fps: $selectedRange")
            return selectedRange
        }
        
        // Fallback - just get the first range
        val selectedFpsRange = fpsRangeList[0]
        Logger.d(TAG, "Fallback fps range: $selectedFpsRange")
        return selectedFpsRange
    }

    private class CameraDeviceCallback(
        private val cont: CancellableContinuation<CameraDevice>,
    ) : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) = cont.resume(device)

        override fun onDisconnected(camera: CameraDevice) {
            Logger.w(TAG, "Camera ${camera.id} has been disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Logger.e(TAG, "Camera ${camera.id} is in error $error")

            val exc = when (error) {
                ERROR_CAMERA_IN_USE -> CameraError("Camera already in use")
                ERROR_MAX_CAMERAS_IN_USE -> CameraError("Max cameras in use")
                ERROR_CAMERA_DISABLED -> CameraError("Camera has been disabled")
                ERROR_CAMERA_DEVICE -> CameraError("Camera device has crashed")
                ERROR_CAMERA_SERVICE -> CameraError("Camera service has crashed")
                else -> CameraError("Unknown error")
            }
            if (cont.isActive) cont.resumeWithException(exc)
        }
    }

    private class CameraCaptureSessionCallback(
        private val cont: CancellableContinuation<CameraCaptureSession>,
    ) : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

        override fun onConfigureFailed(session: CameraCaptureSession) {
            Logger.e(TAG, "Camera Session configuration failed")
            cont.resumeWithException(CameraError("Camera: failed to configure the capture session"))
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        private var frameCount = 0
        private var lastLogTime = System.currentTimeMillis()
        
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            
            // Log frame rate every second to monitor performance
            frameCount++
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastLogTime >= 1000) {
                Logger.d(TAG, "Camera capture framerate: $frameCount fps")
                frameCount = 0
                lastLogTime = currentTime
            }
        }
        
        override fun onCaptureFailed(
            session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure
        ) {
            super.onCaptureFailed(session, request, failure)
            Logger.e(TAG, "Capture failed with code ${failure.reason}")
        }
        
        override fun onCaptureSequenceCompleted(
            session: CameraCaptureSession, 
            sequenceId: Int, 
            frameNumber: Long
        ) {
            super.onCaptureSequenceCompleted(session, sequenceId, frameNumber)
            Logger.d(TAG, "Capture sequence $sequenceId completed at frame $frameNumber")
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun openCamera(
        manager: CameraManager, cameraId: String
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        threadManager.openCamera(
            manager, cameraId, CameraDeviceCallback(cont)
        )
    }

    private suspend fun createCaptureSession(
        camera: CameraDevice,
        targets: List<Surface>,
        dynamicRange: Long,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val outputConfigurations = targets.map {
                OutputConfiguration(it).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        dynamicRangeProfile = dynamicRange
                    }
                }
            }

            threadManager.createCaptureSessionByOutputConfiguration(
                camera, outputConfigurations, CameraCaptureSessionCallback(cont)
            )
        } else {
            threadManager.createCaptureSession(
                camera, targets, CameraCaptureSessionCallback(cont)
            )
        }
    }

    private fun createRequestSession(
        camera: CameraDevice,
        captureSession: CameraCaptureSession,
        fpsRange: Range<Int>,
        surfaces: List<Surface>
    ): CaptureRequest.Builder {
        if (surfaces.isEmpty()) {
            throw RuntimeException("No target surface")
        }

        // Use PREVIEW template for most camera types
        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        
        try {
            // Add all surfaces
            surfaces.forEach { captureBuilder.addTarget(it) }
            
            // Basic settings - balance power and functionality
            captureBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            
            // Save power by disabling features that are CPU intensive
            captureBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) // Auto-focus but continuous video mode uses less CPU than picture mode
            captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO) // Keep auto white balance for usable image
            captureBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            captureBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
            captureBuilder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_FAST)
            
            // Start the repeating request right away to ensure continuous capture
            threadManager.setRepeatingSingleRequest(captureSession, captureBuilder.build(), captureCallback)
            
            return captureBuilder
        } catch (e: Exception) {
            Logger.e(TAG, "Error creating camera request session", e)
            throw e
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun startCamera(
        cameraId: String,
        targets: List<Surface>,
        dynamicRange: Long,
    ) {
        require(targets.isNotEmpty()) { " At least one target is required" }

        withContext(coroutineDispatcher) {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            camera = openCamera(manager, cameraId).also { cameraDevice ->
                captureSession = createCaptureSession(
                    cameraDevice, targets, dynamicRange
                )
            }
        }
    }

    fun startRequestSession(fps: Int, targets: List<Surface>) {
        require(camera != null) { "Camera must not be null" }
        require(captureSession != null) { "Capture session must not be null" }
        require(targets.isNotEmpty()) { " At least one target is required" }

        captureRequest = createRequestSession(
            camera!!, captureSession!!, getClosestFpsRange(camera!!.id, fps), targets
        )
    }

    fun stopCamera() {
        captureRequest = null

        captureSession?.close()
        captureSession = null

        camera?.close()
        camera = null
    }

    fun addTargets(targets: List<Surface>) {
        require(captureRequest != null) { "capture request must not be null" }
        require(targets.isNotEmpty()) { " At least one target is required" }

        targets.forEach {
            captureRequest!!.addTarget(it)
        }
        updateRepeatingSession()
    }

    fun addTarget(target: Surface) {
        require(captureRequest != null) { "capture request must not be null" }

        captureRequest!!.addTarget(target)

        updateRepeatingSession()
    }

    fun removeTarget(target: Surface) {
        require(captureRequest != null) { "capture request must not be null" }

        captureRequest!!.removeTarget(target)
        updateRepeatingSession()
    }

    fun release() {
        threadManager.release()
    }


    fun muteVibrationAndSound() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            camera?.cameraAudioRestriction = AUDIO_RESTRICTION_VIBRATION_SOUND
        }
    }

    fun unmuteVibrationAndSound() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            camera?.cameraAudioRestriction = AUDIO_RESTRICTION_NONE
        }
    }

    fun updateRepeatingSession() {
        try {
            if (captureSession == null) {
                Logger.e(TAG, "Cannot update repeating session: capture session is null")
                return
            }
            if (captureRequest == null) {
                Logger.e(TAG, "Cannot update repeating session: capture request is null")
                return
            }

            // Build the request and set it as a repeating request to ensure continuous capture
            val request = captureRequest!!.build()
            threadManager.setRepeatingSingleRequest(captureSession!!, request, captureCallback)
            Logger.d(TAG, "Updated repeating request")
        } catch (e: Exception) {
            Logger.e(TAG, "Error updating repeating session", e)
        }
    }

    private fun updateBurstSession() {
        try {
            if (captureSession == null) {
                Logger.e(TAG, "Cannot update burst session: capture session is null")
                return
            }
            if (captureRequest == null) {
                Logger.e(TAG, "Cannot update burst session: capture request is null")
                return
            }

            // Build the request and capture it in burst mode
            val request = captureRequest!!.build()
            threadManager.captureBurstRequests(captureSession!!, listOf(request), captureCallback)
            Logger.d(TAG, "Updated burst request")
        } catch (e: Exception) {
            Logger.e(TAG, "Error updating burst session", e)
        }
    }

    fun <T> getSetting(key: CaptureRequest.Key<T>?): T? {
        return captureRequest?.get(key)
    }

    fun <T> setRepeatingSetting(key: CaptureRequest.Key<T>, value: T) {
        captureRequest?.let {
            it.set(key, value)
            updateRepeatingSession()
        }
    }

    fun setRepeatingSettings(settingsMap: Map<CaptureRequest.Key<Any>, Any>) {
        captureRequest?.let {
            for (item in settingsMap) {
                it.set(item.key, item.value)
            }
            updateRepeatingSession()
        }
    }

    fun setBurstSettings(settingsMap: Map<CaptureRequest.Key<Any>, Any>) {
        captureRequest?.let {
            for (item in settingsMap) {
                it.set(item.key, item.value)
            }
            updateBurstSession()
        }
    }

    companion object {
        private const val TAG = "CameraController"
    }
}