package com.capacitorcommunity.videorecorder

import android.Manifest
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import co.fitcom.fancycamera.CameraEventListenerUI
import co.fitcom.fancycamera.EventType
import co.fitcom.fancycamera.FancyCamera
import co.fitcom.fancycamera.PhotoEvent
import co.fitcom.fancycamera.VideoEvent
import com.getcapacitor.FileUtils
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginException
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import java.util.Timer
import java.util.TimerTask
import org.json.JSONException
import org.json.JSONObject

@CapacitorPlugin(
    name = "VideoRecorder",
    permissions = [
        Permission(strings = [Manifest.permission.CAMERA], alias = VideoRecorderPlugin.CAMERA_ALIAS),
        Permission(strings = [Manifest.permission.RECORD_AUDIO], alias = VideoRecorderPlugin.MICROPHONE_ALIAS)
    ]
)
public class VideoRecorderPlugin : Plugin() {
    // These are set by initialize(). Using the plugin before that throws, as it always did.
    private lateinit var fancyCamera: FancyCamera
    private lateinit var previewFrameConfigs: HashMap<String?, FrameConfig>
    private lateinit var currentFrameConfig: FrameConfig

    private var call: PluginCall? = null

    // Track camera position ourselves: 0 = back, 1 = front
    private var currentCameraPositionInt = 1
    private var audioFeedbackTimer: Timer? = null
    private var timerStarted = false
    private var videoBitrate = 3000000
    private var flashEnabled = false
    private var previousBackgroundColor: Int? = null

    private val isCameraStarted: Boolean
        get() = ::fancyCamera.isInitialized && fancyCamera.cameraStarted()

    @PermissionCallback
    private fun handlePermissionResult(savedCall: PluginCall?) {
        val pendingCall = call ?: savedCall
        if (fancyCamera.hasPermission()) {
            pendingCall?.resolve()
            startCamera()
        } else {
            pendingCall?.reject("")
        }
    }

    private fun startCamera() {
        if (!::fancyCamera.isInitialized || fancyCamera.cameraStarted()) return
        fancyCamera.start()
    }

    private fun startTimer() {
        if (timerStarted) {
            return
        }

        audioFeedbackTimer?.cancel()

        audioFeedbackTimer =
            Timer().also {
                it.scheduleAtFixedRate(
                    object : TimerTask() {
                        override fun run() {
                            timerStarted = true
                            activity.runOnUiThread {
                                // The volume is read but not sent: the "onVolumeInput" event is switched off.
                                val data = JSObject()
                                val db = if (::fancyCamera.isInitialized) fancyCamera.db else 0.0
                                data.put("value", db)
                            }
                        }
                    },
                    0,
                    100
                )
            }
    }

    private fun stopTimer() {
        audioFeedbackTimer?.cancel()
        audioFeedbackTimer = null
        timerStarted = false
    }

    @PluginMethod
    public fun initialize(call: PluginCall) {
        var defaultFrame = JSObject()
        defaultFrame.put("id", "default")
        currentFrameConfig = FrameConfig(defaultFrame)
        previewFrameConfigs = HashMap()

        videoBitrate = call.getInt("videoBitrate") ?: 3000000

        // flash is turned off by default when initializing camera
        flashEnabled = false

        val camera = FancyCamera(context)
        fancyCamera = camera
        camera.maxVideoBitrate = videoBitrate
        camera.disableHEVC = true
        camera.setListener(
            object : CameraEventListenerUI() {
                override fun onCameraOpenUI() {
                    this@VideoRecorderPlugin.call?.resolve()
                    startTimer()
                    updateCameraView(currentFrameConfig)
                    for (f in previewFrameConfigs.values) {
                        updateCameraView(f)
                    }
                }

                override fun onCameraCloseUI() {
                    this@VideoRecorderPlugin.call?.resolve()
                    stopTimer()
                }

                override fun onPhotoEventUI(event: PhotoEvent) {}

                override fun onVideoEventUI(event: VideoEvent) {
                    if (event.type != EventType.INFO) return
                    if (event.message.contains(VideoEvent.EventInfo.RECORDING_FINISHED.toString())) {
                        val pendingCall = this@VideoRecorderPlugin.call
                        if (pendingCall != null) {
                            val data = JSObject()
                            val path = FileUtils.getPortablePath(context, bridge.localUrl, Uri.fromFile(event.file))
                            data.put("videoUrl", path)
                            pendingCall.resolve(data)
                        }
                    } else if (event.message.contains(VideoEvent.EventInfo.RECORDING_STARTED.toString())) {
                        this@VideoRecorderPlugin.call?.resolve()
                    }
                }
            }
        )
        val cameraPreviewParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        camera.layoutParams = cameraPreviewParams

        defaultFrame = JSObject()
        defaultFrame.put("id", "default")
        val defaultArray = JSArray()
        defaultArray.put(defaultFrame)
        val array = call.getArray("previewFrames", defaultArray) ?: defaultArray
        for (i in 0 until array.length()) {
            try {
                val config = FrameConfig(JSObject.fromJSONObject(array.get(i) as JSONObject))
                previewFrameConfigs[config.id] = config

                // Set the first preview frame as the current frame config
                if (i == 0) {
                    currentFrameConfig = config
                }
            } catch (ignored: JSONException) {
            }
        }

        camera.setCameraPosition(1)
        currentCameraPositionInt = 1 // Set our tracked position to front camera
        if (camera.hasPermission()) {
            // Swapping these around since it is the other way for iOS and the plugin interface needs to stay consistent
            if (call.getInt("camera") == 1) {
                camera.setCameraPosition(0)
                currentCameraPositionInt = 0 // Back camera
            } else {
                camera.setCameraPosition(1)
                currentCameraPositionInt = 1 // Front camera
            }
        }

        if (!camera.cameraStarted()) {
            startCamera()
        }

        this.call = call

        // FancyCamera asks for the camera and the microphone by itself once its surface exists, but the runtime no
        // longer hands the result of such a request to plugins. Asking through the runtime before the view is added
        // gets the result to handlePermissionResult, and Android drops the view's request while this one is open.
        if (!camera.hasPermission()) {
            requestPermissionForAliases(arrayOf(CAMERA_ALIAS, MICROPHONE_ALIAS), call, "handlePermissionResult")
        }

        activity.runOnUiThread {
            val parent = bridge.webView.parent as CoordinatorLayout
            parent.addView(camera, cameraPreviewParams)
            bridge.webView.bringToFront()
            parent.requestLayout()
            parent.invalidate()
        }
    }

    @PluginMethod
    public fun destroy(call: PluginCall) {
        makeOpaque()

        activity.runOnUiThread {
            val parent = fancyCamera.parent
            if (parent is ViewGroup) {
                previousBackgroundColor?.let {
                    parent.setBackgroundColor(it)
                    previousBackgroundColor = null
                }
                parent.removeView(fancyCamera)
            }
        }

        fancyCamera.release()
        call.resolve()
    }

    private fun makeOpaque() {
        bridge.webView.setBackgroundColor(Color.WHITE)
    }

    @PluginMethod
    public fun showPreviewFrame(call: PluginCall) {
        // Both options are required. Leaving one out throws, as it always did.
        val position = call.getInt("position")!!
        val quality = call.getInt("quality")!!
        fancyCamera.setCameraPosition(position)
        currentCameraPositionInt = position // Update our tracked position
        fancyCamera.setQuality(quality)
        bridge.webView.setBackgroundColor(Color.argb(0, 0, 0, 0))
        if (!fancyCamera.cameraStarted()) {
            startCamera()
            this.call = call
        } else {
            // Update camera view to apply mirroring settings after camera position change
            activity.runOnUiThread { updateCameraView(currentFrameConfig) }
            call.resolve()
        }
    }

    @PluginMethod
    public fun hidePreviewFrame(call: PluginCall) {
        makeOpaque()
        fancyCamera.stop()
        this.call = call
    }

    @Suppress("UNUSED_PARAMETER")
    @PluginMethod
    public fun togglePip(call: PluginCall) {
    }

    @PluginMethod
    public fun startRecording(call: PluginCall) {
        fancyCamera.autoFocus = true

        // turn on flash if flash is enabled and camera is back camera
        if (flashEnabled && fancyCamera.cameraPosition == 0) {
            fancyCamera.enableFlash()
        }

        fancyCamera.startRecording()
        call.resolve()
    }

    @PluginMethod
    public fun stopRecording(call: PluginCall) {
        this.call = call

        // turn off flash if flash is enabled and camera is back camera
        if (flashEnabled && fancyCamera.cameraPosition == 0) {
            fancyCamera.disableFlash()
        }

        fancyCamera.stopRecording()
    }

    @PluginMethod
    public fun flipCamera(call: PluginCall) {
        fancyCamera.toggleCamera()

        // Update our tracked camera position
        currentCameraPositionInt = if (currentCameraPositionInt == 0) 1 else 0
        Log.d(TAG, "Camera flipped to position: $currentCameraPositionInt")

        // Update camera view to apply correct mirroring for the new camera position
        // Add a small delay to ensure the camera position has been updated
        Handler(Looper.getMainLooper()).postDelayed({ updateCameraView(currentFrameConfig) }, 100)

        call.resolve()
    }

    @PluginMethod
    public fun enableFlash(call: PluginCall) {
        flashEnabled = true
        call.resolve()
    }

    @PluginMethod
    public fun disableFlash(call: PluginCall) {
        flashEnabled = false
        call.resolve()
    }

    @PluginMethod
    public fun toggleFlash(call: PluginCall) {
        flashEnabled = !flashEnabled
        call.resolve()
    }

    @PluginMethod
    public fun isFlashEnabled(call: PluginCall) {
        val data = JSObject()
        data.put("isEnabled", flashEnabled)
        call.resolve(data)
    }

    @PluginMethod
    public fun isFlashAvailable(call: PluginCall) {
        val data = JSObject()
        data.put("isAvailable", fancyCamera.hasFlash())
        call.resolve(data)
    }

    @PluginMethod
    public fun getDuration(call: PluginCall) {
        val data = JSObject()
        data.put("value", fancyCamera.duration)
        call.resolve(data)
    }

    @PluginMethod
    public fun setPosition(call: PluginCall) {
        // The option is required. Leaving it out throws, as it always did.
        val position = call.getInt("position")!!
        fancyCamera.setCameraPosition(position)
        currentCameraPositionInt = position // Update our tracked position
    }

    @PluginMethod
    public fun setQuality(call: PluginCall) {
        // The option is required. Leaving it out throws, as it always did.
        val quality = call.getInt("quality")!!
        fancyCamera.setQuality(quality)
    }

    @PluginMethod
    public fun addPreviewFrameConfig(call: PluginCall) {
        if (isCameraStarted) {
            // The id is required. Leaving it out throws, as it always did.
            val layerId = call.getString("id")!!
            if (layerId.isEmpty()) {
                throw PluginException("Must provide layer id")
            }

            val config = FrameConfig(call.data)

            if (previewFrameConfigs.containsKey(layerId)) {
                editPreviewFrameConfig(call)
                return
            } else {
                previewFrameConfigs[layerId] = config
            }
            call.resolve()
        }
    }

    @PluginMethod
    public fun editPreviewFrameConfig(call: PluginCall) {
        if (isCameraStarted) {
            // The id is required. Leaving it out throws, as it always did.
            val layerId = call.getString("id")!!
            if (layerId.isEmpty()) {
                throw PluginException("Must provide layer id")
            }

            val updatedConfig = FrameConfig(call.data)
            previewFrameConfigs[layerId] = updatedConfig

            if (currentFrameConfig.id == layerId) {
                currentFrameConfig = updatedConfig
                // The preview is a view: lay it out on the main thread, as the other callers do
                activity.runOnUiThread { updateCameraView(updatedConfig) }
            }

            call.resolve()
        }
    }

    @PluginMethod
    public fun switchToPreviewFrame(call: PluginCall) {
        if (isCameraStarted) {
            // The id is required. Leaving it out throws, as it always did.
            val layerId = call.getString("id")!!
            if (layerId.isEmpty()) {
                throw PluginException("Must provide layer id")
            }
            val existingConfig = previewFrameConfigs[layerId] ?: throw PluginException("Frame config does not exist")
            if (existingConfig.id != currentFrameConfig.id) {
                currentFrameConfig = existingConfig
                // The preview is a view: lay it out on the main thread, as the other callers do
                activity.runOnUiThread { updateCameraView(existingConfig) }
            }
            call.resolve()
        }
    }

    private fun getPixels(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun updateCameraView(frameConfig: FrameConfig) {
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.getMetrics(displayMetrics)
        val deviceHeight = displayMetrics.heightPixels
        val deviceWidth = displayMetrics.widthPixels
        val isLandscape = deviceWidth > deviceHeight

        if (fancyCamera.layoutParams == null) {
            fancyCamera.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Calculate the aspect ratio dimensions
        var width = deviceWidth
        var height = if (isLandscape) (deviceWidth * 9.0 / 16.0).toInt() else (deviceWidth * 4.0 / 3.0).toInt()

        // If the calculated height is greater than the device height, adjust the width and height
        if (height > deviceHeight) {
            height = deviceHeight
            width = if (isLandscape) (deviceHeight * 16.0 / 9.0).toInt() else (deviceHeight * 3.0 / 4.0).toInt()
        }

        val oldParams = fancyCamera.layoutParams

        if (frameConfig.width != -1) {
            width = getPixels(frameConfig.width)
        }

        if (frameConfig.height != -1) {
            height = getPixels(frameConfig.height)
        }

        oldParams.width = width
        oldParams.height = height
        fancyCamera.layoutParams = oldParams

        // Center the preview frame vertically if y is 0 and height and width are -1
        if (frameConfig.y == 0f && frameConfig.height == -1 && frameConfig.width == -1) {
            fancyCamera.y = ((deviceHeight - height) / 2).toFloat()
        } else {
            fancyCamera.y = getPixels(frameConfig.y.toInt()).toFloat()
        }

        // Center the preview frame horizontally if x is 0 and height and width are -1
        if (isLandscape && frameConfig.x == 0f && frameConfig.height == -1 && frameConfig.width == -1) {
            fancyCamera.x = (deviceWidth - width).toFloat() / 2
        } else {
            fancyCamera.x = getPixels(frameConfig.x.toInt()).toFloat()
        }

        // Set the background color to black
        val parent = fancyCamera.parent
        if (parent is ViewGroup) {
            val background = parent.background
            if (background is ColorDrawable) {
                previousBackgroundColor = background.color
            }
            parent.setBackgroundColor(Color.BLACK)
        }

        fancyCamera.elevation = 9f
        bridge.webView.elevation = 9f
        bridge.webView.setBackgroundColor(Color.argb(0, 0, 0, 0))
        if (frameConfig.stackPosition == "front") {
            activity.runOnUiThread {
                fancyCamera.bringToFront()
                bridge.webView.parent.requestLayout()
                (bridge.webView.parent as CoordinatorLayout).invalidate()
            }
        } else if (frameConfig.stackPosition == "back") {
            activity.runOnUiThread {
                bridge.webView.bringToFront()
                bridge.webView.parent.requestLayout()
                (bridge.webView.parent as CoordinatorLayout).invalidate()
            }
        }

        // Apply mirroring if needed (front camera and mirrorFrontCam true)
        // Add a small delay to ensure camera position is stable
        Handler(Looper.getMainLooper()).postDelayed({
            // Use our tracked camera position instead of fancyCamera.getCameraPosition()
            val trackedCameraPosition = currentCameraPositionInt
            val fancyCameraPosition = fancyCamera.cameraPosition

            Log.d(
                TAG,
                "Applying mirroring - Tracked: $trackedCameraPosition, FancyCamera: $fancyCameraPosition, " +
                    "Mirror: ${frameConfig.mirrorFrontCam}"
            )

            // The front camera keeps scaleX 1 when mirrorFrontCam is set and is flipped when it is not.
            // The back camera is never flipped.
            if (trackedCameraPosition == 1) {
                if (frameConfig.mirrorFrontCam) {
                    Log.d(TAG, "Front camera: Applying mirror effect")
                    fancyCamera.scaleX = 1f
                } else {
                    Log.d(TAG, "Front camera: No mirror (mirrorFrontCam=false)")
                    fancyCamera.scaleX = -1f
                }
            } else {
                Log.d(TAG, "Back camera: No mirror effect")
                fancyCamera.scaleX = 1f
            }

            // Force a layout update to ensure the changes are applied
            fancyCamera.requestLayout()
            fancyCamera.invalidate()
        }, 50)
    }

    private class FrameConfig(config: JSObject) {
        val id: String? = config.getString("id")
        val stackPosition: String? = config.getString("stackPosition", "back")
        val x: Float = (config.getInteger("x") ?: 0).toFloat()
        val y: Float = (config.getInteger("y") ?: 0).toFloat()
        val width: Int = config.getInteger("width") ?: -1
        val height: Int = config.getInteger("height") ?: -1
        val borderRadius: Float = (config.getInteger("borderRadius") ?: 0).toFloat()
        val dropShadow: DropShadow = DropShadow(config.getJSObject("dropShadow") ?: JSObject())
        val mirrorFrontCam: Boolean = config.getBoolean("mirrorFrontCam", true) ?: true

        class DropShadow(config: JSObject) {
            val opacity: Float = (config.getInteger("opacity") ?: 0).toFloat()
            val radius: Float = (config.getInteger("radius") ?: 0).toFloat()
        }
    }

    internal companion object {
        const val CAMERA_ALIAS = "camera"
        const val MICROPHONE_ALIAS = "microphone"

        private const val TAG = "VideoRecorder"
    }
}
