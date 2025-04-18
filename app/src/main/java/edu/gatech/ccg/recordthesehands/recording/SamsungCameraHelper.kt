package edu.gatech.ccg.recordthesehands.recording

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * A helper class specifically designed to work around Samsung camera issues
 */
class SamsungCameraHelper(
    private val context: Context, 
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {
    companion object {
        private const val TAG = "SamsungCameraHelper"
        
        /**
         * Check if this is a Samsung device that needs special handling
         */
        fun isSamsungDevice(): Boolean {
            return Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        }
        
        /**
         * Check if this is specifically a Samsung Galaxy A34
         */
        fun isSamsungGalaxyA34(): Boolean {
            return isSamsungDevice() && Build.MODEL.contains("SM-A346", ignoreCase = true)
        }
    }
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    
    // Track recording state to avoid invalid state transitions
    private enum class RecordingState {
        IDLE, INITIALIZING, RECORDING, STOPPING
    }
    private var recordingState = RecordingState.IDLE
    private var retryAttempts = 0
    private val maxRetryAttempts = 3
    
    /**
     * Initialize the camera with video recording support
     */
    @SuppressLint("RestrictedApi")
    fun initializeCamera(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        try {
            // Reset state
            recordingState = RecordingState.INITIALIZING
            
            // First, try with a timeout to prevent hanging
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            
            try {
                // Special handling for Samsung devices - add timeout
                cameraProvider = try {
                    Log.d(TAG, "Attempting to get camera provider with timeout")
                    cameraProviderFuture.get(3, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    Log.e(TAG, "Timeout getting camera provider, trying without timeout", e)
                    cameraProviderFuture.get() // Try without timeout as fallback
                }
                
                // Unbind any previous use cases
                cameraProvider?.unbindAll()
                
                // Create a simple preview use case
                val preview = Preview.Builder()
                    .build()
                
                // Configure the preview view
                preview.setSurfaceProvider(previewView.surfaceProvider)
                
                // Set up quality selector for video recording
                val qualitySelector = QualitySelector.from(Quality.HIGHEST)
                
                // Create recorder with quality selector
                val recorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build()
                
                // Create VideoCapture use case with the recorder
                videoCapture = VideoCapture.withOutput(recorder)
                
                // Use front camera
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                
                // Bind the use cases
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    videoCapture
                )
                
                // Add small delay before calling onSuccess for Samsung
                Handler(Looper.getMainLooper()).postDelayed({
                    // Success!
                    recordingState = RecordingState.IDLE
                    Log.d(TAG, "Samsung camera initialized successfully")
                    onSuccess()
                }, 300)
                
            } catch (e: Exception) {
                recordingState = RecordingState.IDLE
                Log.e(TAG, "Failed to initialize Samsung camera: ${e.message}", e)
                onError(e)
            }
        } catch (e: Exception) {
            recordingState = RecordingState.IDLE
            Log.e(TAG, "Critical camera error: ${e.message}", e)
            onError(e)
        }
    }
    
    /**
     * Re-initialize camera if it wasn't properly set up
     */
    private fun reinitializeCamera(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        try {
            // First clean up any existing resources
            try {
                cameraProvider?.unbindAll()
                cameraProvider = null
                videoCapture = null
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up camera resources", e)
            }
            
            // Check if lifecycle is still active before proceeding
            if (!isLifecycleValid()) {
                Log.e(TAG, "Lifecycle is not valid for camera operations")
                onError(IllegalStateException("Lifecycle is not active or valid"))
                return
            }
            
            // Now re-initialize
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            
            try {
                // Special handling for Samsung devices - add timeout
                cameraProvider = try {
                    Log.d(TAG, "Re-initializing: Attempting to get camera provider with timeout")
                    cameraProviderFuture.get(3, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    Log.e(TAG, "Re-initializing: Timeout getting camera provider, trying without timeout", e)
                    cameraProviderFuture.get() // Try without timeout as fallback
                }
                
                // Check lifecycle again before binding
                if (!isLifecycleValid()) {
                    Log.e(TAG, "Lifecycle became invalid during camera initialization")
                    onError(IllegalStateException("Lifecycle became invalid during initialization"))
                    return
                }
                
                // Create a simple preview use case
                val preview = Preview.Builder()
                    .build()
                
                // Configure the preview view
                preview.setSurfaceProvider(previewView.surfaceProvider)
                
                // Set up quality selector for video recording
                val qualitySelector = QualitySelector.from(Quality.HIGHEST)
                
                // Create recorder with quality selector
                val recorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build()
                
                // Create VideoCapture use case with the recorder
                videoCapture = VideoCapture.withOutput(recorder)
                
                Log.d(TAG, "Re-initializing: Created new videoCapture instance: $videoCapture")
                
                // Use front camera
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                
                // Final lifecycle check before binding
                if (!isLifecycleValid()) {
                    Log.e(TAG, "Lifecycle became invalid before binding camera")
                    onError(IllegalStateException("Lifecycle became invalid before binding"))
                    return
                }
                
                try {
                    // Bind the use cases
                    cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        videoCapture
                    )
                    
                    // Success!
                    recordingState = RecordingState.IDLE
                    Log.d(TAG, "Re-initializing: Samsung camera initialized successfully")
                    onSuccess()
                } catch (e: IllegalArgumentException) {
                    // This specific exception happens with destroyed lifecycle
                    if (e.message?.contains("lifecycle", ignoreCase = true) == true) {
                        Log.e(TAG, "Lifecycle error during binding: ${e.message}")
                        onError(IllegalStateException("Camera lifecycle error: ${e.message}"))
                    } else {
                        throw e
                    }
                }
                
            } catch (e: Exception) {
                recordingState = RecordingState.IDLE
                Log.e(TAG, "Re-initializing: Failed to initialize Samsung camera: ${e.message}", e)
                onError(e)
            }
        } catch (e: Exception) {
            recordingState = RecordingState.IDLE
            Log.e(TAG, "Re-initializing: Critical camera error: ${e.message}", e)
            onError(e)
        }
    }
    
    /**
     * Check if the lifecycle owner is in a valid state for camera operations
     */
    private fun isLifecycleValid(): Boolean {
        return try {
            // Check if the lifecycle is in a valid state
            val lifecycle = lifecycleOwner.lifecycle
            val state = lifecycle.currentState
            
            // The lifecycle must be at least CREATED to use the camera
            state.isAtLeast(androidx.lifecycle.Lifecycle.State.CREATED)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking lifecycle state", e)
            false
        }
    }
    
    /**
     * Start video recording with retry mechanism
     */
    fun startRecording(outputFile: File, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        if (recordingState == RecordingState.RECORDING) {
            Log.d(TAG, "Already recording, ignoring startRecording request")
            onSuccess() // Just report success since we're already recording
            return
        }
        
        if (recordingState == RecordingState.STOPPING) {
            Log.d(TAG, "Currently stopping recording, will retry in 500ms")
            // Wait for stopping to complete and retry
            Handler(Looper.getMainLooper()).postDelayed({
                startRecording(outputFile, onSuccess, onError)
            }, 500)
            return
        }
        
        // Check lifecycle validity first
        if (!isLifecycleValid()) {
            Log.e(TAG, "Cannot start recording - lifecycle is not valid")
            onError(IllegalStateException("Activity lifecycle is not valid for recording"))
            return
        }
        
        recordingState = RecordingState.INITIALIZING
        retryAttempts = 0
        
        try {
            // Check if videoCapture is null or not properly initialized
            if (videoCapture == null) {
                Log.e(TAG, "VideoCapture is null, attempting to re-initialize camera")
                // Try to re-initialize camera
                reinitializeCamera(
                    onSuccess = {
                        // Now try recording again after camera is initialized
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (isLifecycleValid()) {
                                startRecording(outputFile, onSuccess, onError)
                            } else {
                                recordingState = RecordingState.IDLE
                                onError(IllegalStateException("Activity lifecycle became invalid"))
                            }
                        }, 500)
                    },
                    onError = { e ->
                        recordingState = RecordingState.IDLE
                        Log.e(TAG, "Camera re-initialization failed", e)
                        onError(IllegalStateException("VideoCapture initialization failed: ${e.message}"))
                    }
                )
                return
            }
            
            val captureObject = videoCapture ?: throw IllegalStateException("VideoCapture still null after check")
            
            Log.d(TAG, "Starting recording with videoCapture: $captureObject to file: ${outputFile.absolutePath}")
            
            // Wait a moment before starting recording
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // Check lifecycle again before starting recording
                    if (!isLifecycleValid()) {
                        Log.e(TAG, "Lifecycle became invalid before recording could start")
                        recordingState = RecordingState.IDLE
                        onError(IllegalStateException("Activity lifecycle became invalid"))
                        return@postDelayed
                    }
                    
                    // Configure output options
                    val outputOptions = FileOutputOptions.Builder(outputFile)
                        .build()
                    
                    Log.d(TAG, "Prepared recording with options: $outputOptions")
                    
                    // Start recording
                    recording = captureObject.output
                        .prepareRecording(context, outputOptions)
                        .start(mainExecutor) { event ->
                            when (event) {
                                is VideoRecordEvent.Start -> {
                                    Log.i(TAG, "Recording started")
                                    recordingState = RecordingState.RECORDING
                                    retryAttempts = 0 // Reset retry counter on success
                                    onSuccess()
                                }
                                is VideoRecordEvent.Finalize -> {
                                    recordingState = RecordingState.IDLE
                                    if (event.hasError()) {
                                        Log.e(TAG, "Recording failed: ${event.error}")
                                        
                                        // Retry if we haven't exceeded max retries
                                        if (retryAttempts < maxRetryAttempts) {
                                            retryAttempts++
                                            Log.d(TAG, "Retrying recording (attempt $retryAttempts of $maxRetryAttempts)")
                                            
                                            // Wait a moment before retrying
                                            Handler(Looper.getMainLooper()).postDelayed({
                                                startRecording(outputFile, onSuccess, onError)
                                            }, 1000)
                                        } else {
                                            onError(Exception("Recording failed: ${event.error}"))
                                        }
                                    } else {
                                        Log.i(TAG, "Recording successfully finalized")
                                    }
                                }
                                else -> {
                                    Log.d(TAG, "Sent VideoRecordEvent class ${event.javaClass.simpleName}")
                                }
                            }
                        }
                } catch (e: Exception) {
                    recordingState = RecordingState.IDLE
                    Log.e(TAG, "Error in delayed recording start", e)
                    onError(e)
                }
            }, 200) // Small delay before starting recording
            
        } catch (e: Exception) {
            recordingState = RecordingState.IDLE
            Log.e(TAG, "Failed to start recording", e)
            onError(e)
        }
    }
    
    /**
     * Stop video recording
     */
    fun stopRecording() {
        if (recordingState != RecordingState.RECORDING) {
            Log.d(TAG, "Not recording, nothing to stop (state: $recordingState)")
            return
        }
        
        recordingState = RecordingState.STOPPING
        
        try {
            // Get local copy of recording reference
            val currentRecording = recording
            
            if (currentRecording != null) {
                try {
                    // First try normal stop
                    currentRecording.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Error with normal recording stop, trying close", e)
                    try {
                        // If stop fails, try close
                        currentRecording.close()
                    } catch (e2: Exception) {
                        Log.e(TAG, "Error with recording close, ignoring", e2)
                    }
                }
            }
            
            // Clear recording reference
            recording = null
            
            // Force state back to IDLE after a delay regardless of callbacks
            Handler(Looper.getMainLooper()).postDelayed({
                recordingState = RecordingState.IDLE
            }, 1000)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
            // Force state back to IDLE to allow new recording attempts
            recordingState = RecordingState.IDLE
        }
    }
    
    /**
     * Reset camera state
     */
    fun resetCameraState() {
        try {
            stopRecording()
            
            // Unbind and rebind camera uses cases
            cameraProvider?.unbindAll()
            
            if (videoCapture != null) {
                // Use front camera
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                
                // Create a simple preview use case
                val preview = Preview.Builder()
                    .build()
                
                // Configure the preview view
                preview.setSurfaceProvider(previewView.surfaceProvider)
                
                // Rebind use cases
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    videoCapture
                )
            }
            
            // Force garbage collection to help Samsung
            System.gc()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting camera state", e)
        } finally {
            recordingState = RecordingState.IDLE
        }
    }
    
    /**
     * Release all camera resources
     */
    fun releaseCamera() {
        try {
            // Try to stop recording if active
            if (recordingState == RecordingState.RECORDING) {
                stopRecording()
            }
            
            // Wait a moment before unbinding
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    cameraProvider?.unbindAll()
                    cameraProvider = null
                    videoCapture = null
                    recordingState = RecordingState.IDLE
                } catch (e: Exception) {
                    Log.e(TAG, "Error in delayed camera release", e)
                }
            }, 200)
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing camera resources", e)
        }
    }
    
    /**
     * Show a toast message about camera status
     */
    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    
    /**
     * Check if the videoCapture is properly initialized
     */
    fun isVideoCaptureInitialized(): Boolean {
        return videoCapture != null
    }
} 