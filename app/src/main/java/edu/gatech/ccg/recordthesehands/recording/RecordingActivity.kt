/**
 * This file is part of Record These Hands, licensed under the MIT license.
 *
 * Copyright (c) 2021-2024
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package edu.gatech.ccg.recordthesehands.recording

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.CycleInterpolator
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import androidx.concurrent.futures.await
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import androidx.viewpager2.widget.ViewPager2
import edu.gatech.ccg.recordthesehands.Constants.RESULT_ACTIVITY_STOPPED
import edu.gatech.ccg.recordthesehands.Constants.RESULT_CAMERA_DIED
import edu.gatech.ccg.recordthesehands.Constants.TABLET_SIZE_THRESHOLD_INCHES
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.databinding.ActivityRecordBinding
import edu.gatech.ccg.recordthesehands.padZeroes
import edu.gatech.ccg.recordthesehands.sendEmail
import edu.gatech.ccg.recordthesehands.upload.DataManager
import edu.gatech.ccg.recordthesehands.upload.Prompt
import edu.gatech.ccg.recordthesehands.upload.Prompts
import edu.gatech.ccg.recordthesehands.upload.UploadService
import edu.gatech.ccg.recordthesehands.toHex
import kotlinx.coroutines.CoroutineScope
import java.io.File
import kotlin.collections.ArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.lang.Integer.min
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread
import kotlin.random.Random
import java.util.concurrent.Executor
import android.widget.Toast
import androidx.camera.core.impl.VideoCaptureConfig
import android.os.Build
import android.app.ActivityManager
import edu.gatech.ccg.recordthesehands.recording.SamsungCameraHelper
import android.widget.Button

/**
 * Contains the data for a clip within the greater recording.
 *
 * @param file       (String) The filename for the (overall) video recording.
 * @param videoStart (Instant) The timestamp that the overall video recording started at.
 * @param signStart  (Instant) The timestamp that the clip within the video started at.
 * @param signEnd    (Instant) The timestamp that the clip within the video ended at.
 * @param attempt    (Int) An attempt number for this phrase key in this session.
 */
class ClipDetails(
  val clipId: String, val sessionId: String, val filename: String, val prompt: Prompt, val videoStart: Instant,
) {

  companion object {
    private val TAG = ClipDetails::class.java.simpleName
  }

  var startButtonDownTimestamp: Instant? = null
  var startButtonUpTimestamp: Instant? = null
  var restartButtonDownTimestamp: Instant? = null
  var swipeBackTimestamp: Instant? = null
  var swipeForwardTimestamp: Instant? = null

  var lastModifiedTimestamp: Instant? = null
  var valid = true

  fun toJson(): JSONObject {
    val json = JSONObject()
    json.put("clipId", clipId)
    json.put("sessionId", sessionId)
    json.put("filename", filename)
    json.put("promptData", prompt.toJson())
    json.put("videoStart", DateTimeFormatter.ISO_INSTANT.format(videoStart))
    if (startButtonDownTimestamp != null) {
      json.put(
        "startButtonDownTimestamp",
        DateTimeFormatter.ISO_INSTANT.format(startButtonDownTimestamp)
      )
    }
    if (startButtonUpTimestamp != null) {
      json.put(
        "startButtonUpTimestamp",
        DateTimeFormatter.ISO_INSTANT.format(startButtonUpTimestamp)
      )
    }
    if (restartButtonDownTimestamp != null) {
      json.put(
        "restartButtonDownTimestamp",
        DateTimeFormatter.ISO_INSTANT.format(restartButtonDownTimestamp)
      )
    }
    if (swipeBackTimestamp != null) {
      json.put("swipeBackTimestamp", DateTimeFormatter.ISO_INSTANT.format(swipeBackTimestamp))
    }
    if (swipeForwardTimestamp != null) {
      json.put("swipeForwardTimestamp", DateTimeFormatter.ISO_INSTANT.format(swipeForwardTimestamp))
    }
    if (lastModifiedTimestamp != null) {
      json.put("lastModifiedTimestamp", lastModifiedTimestamp)
    }
    json.put("valid", valid)
    return json
  }

  fun signStart(): Instant? {
    return startButtonDownTimestamp ?: startButtonUpTimestamp
  }

  fun signEnd(): Instant? {
    return restartButtonDownTimestamp ?: swipeForwardTimestamp ?: swipeBackTimestamp
  }

  /**
   * Creates a string representation for this recording.
   */
  override fun toString(): String {
    val json = toJson()
    return json.toString(2)
  }
}

suspend fun DataManager.saveClipData(clipDetails: ClipDetails) {
  val json = clipDetails.toJson()
  // Use a consistent key based on the clipId so that any changes to the clip
  // will be updated on the server.
  addKeyValue("clipData-${clipDetails.clipId}", json, "clip")
}

fun Random.Default.nextHexId(numBytes: Int): String {
  val bytes = ByteArray(numBytes)
  nextBytes(bytes)
  return toHex(bytes)
}

fun Context.filenameToFilepath(filename: String): File {
  return File(
    filesDir,
    File.separator + "upload" + File.separator + filename
  )
}

/**
 * Class to handle all the information about the recording session which should be saved
 * to the server.
 */
class RecordingSessionInfo(
  val sessionId: String, val filename: String, val deviceId: String, val username: String,
  val sessionType: String, val initialPromptIndex: Int, val limitPromptIndex: Int) {
  companion object {
    private val TAG = RecordingSessionInfo::class.java.simpleName
  }

  var result = "ONGOING"
  var startTimestamp: Instant? = null
  var endTimestamp: Instant? = null
  var finalPromptIndex = initialPromptIndex

  fun toJson(): JSONObject {
    val json = JSONObject()
    json.put("sessionId", sessionId)
    json.put("result", result)
    json.put("filename", filename)
    json.put("deviceId", deviceId)
    json.put("username", username)
    json.put("sessionType", sessionType)
    json.put("initialPromptIndex", initialPromptIndex)
    json.put("limitPromptIndex", limitPromptIndex)
    json.put("finalPromptIndex", finalPromptIndex)
    if (startTimestamp != null) {
      json.put(
        "startTimestamp",
        DateTimeFormatter.ISO_INSTANT.format(startTimestamp)
      )
    }
    if (endTimestamp != null) {
      json.put(
        "endTimestamp",
        DateTimeFormatter.ISO_INSTANT.format(endTimestamp)
      )
    }
    return json
  }

  override fun toString(): String {
    val json = toJson()
    return json.toString(2)
  }
}

suspend fun DataManager.saveSessionInfo(sessionInfo: RecordingSessionInfo) {
  val json = sessionInfo.toJson()
  // Use a consistent key so that any changes will be updated on the server.
  addKeyValue("sessionData-${sessionInfo.sessionId}", json, "session")
}

/**
 * This class handles the recording of ASL into videos.
 *
 * @author  Matthew So <matthew.so@gatech.edu>, Sahir Shahryar <contact@sahirshahryar.com>
 * @since   October 4, 2021
 * @version 1.1.0
 */
class RecordingActivity : AppCompatActivity() {
  companion object {
    private val TAG = RecordingActivity::class.java.simpleName

    /**
     * Video quality and resolution settings for CameraX
     */
    private const val RECORDER_VIDEO_BITRATE: Int = 15_000_000

    /**
     * Height, width, and frame rate of the video recording. Using a 4:3 aspect ratio allows us
     * to get the widest possible field of view on a Pixel 4a camera, which has a 4:3 sensor.
     * Any other aspect ratio would result in some degree of cropping.
     * 
     * Note: With CameraX, the precise resolution is selected by the Quality selector, but
     * we keep these constants for reference.
     */
    private const val RECORDING_HEIGHT = 2592
    private const val RECORDING_WIDTH = 1944
    private const val RECORDING_FRAMERATE = 30

    private const val MAXIMUM_RESOLUTION = 6_000_000

    /**
     * The length of the countdown (in milliseconds), after which the recording will end
     * automatically. Currently configured to be 15 minutes.
     */
    private const val COUNTDOWN_DURATION = 15 * 60 * 1000L

    /**
     * The number of prompts to use in each recording session.
     */
    private const val DEFAULT_SESSION_LENGTH = 30
    private const val DEFAULT_TUTORIAL_SESSION_LENGTH = 5
  }


  // UI elements
  /**
   * Big red button used to start/stop a clip. (Note that we are continuously recording;
   * the button only marks when the user started or stopped signing to the camera.)
   *
   * Note that this button can be either a FloatingActionButton or a Button, depending on
   * whether we are on a smartphone or a tablet, respectively.
   */
  lateinit var recordButton: View

  /**
   * The big button to finish the session.
   */
  lateinit var finishedButton: View

  /**
   * The button to restart a recording.
   */
  lateinit var restartButton: View

  /**
   * Reset camera button for recovery from errors.
   */
  private lateinit var resetCameraButton: Button

  /**
   * The UI that allows a user to swipe back and forth and make recordings.
   * The end screens are also included in this ViewPager.
   */
  lateinit var sessionPager: ViewPager2

  /**
   * The UI that shows how much time is left on the recording before it auto-concludes.
   */
  lateinit var countdownText: TextView

  /**
   * The recording light and text.
   */
  private lateinit var recordingLightView: View

  /**
   * The recording preview.
   */
  lateinit var cameraView: PreviewView

  // UI state variables
  /**
   * Marks whether the user is using a tablet (diagonal screen size > 7.0 inches (~17.78 cm)).
   */
  private var isTablet = false


  /**
   * Marks whether or not the recording button is enabled. If not, then the button should be
   * invisible, and it should be neither clickable (tappable) nor focusable.
   */
  private var recordButtonEnabled = false

  /**
   * Marks whether or not the camera has been successfully initialized. This is used to prevent
   * parts of the code related to camera initialization from running multiple times.
   */
  private var cameraInitialized = false

  /**
   * Marks whether or not the user is currently signing a word. This is essentially only true
   * for the duration that the user holds down the Record button.
   */
  private var isSigning = false

  /**
   * Marks whether or not the camera is currently recording or not. We record continuously as soon
   * as the activity launches, so this value will be true in some instances that `isSigning` may
   * be false.
   */
  private var isRecording = false

  /**
   * In the event that the user is holding down the Record button when the timer runs out, this
   * value will be set to true. Once the user releases the button, the session will end
   * immediately.
   */
  private var endSessionOnClipEnd = false

  /**
   * The page of the ViewPager UI that the user is currently on. If there are K words that have
   * been selected for the user to record, indices 0 to K - 1 (inclusive) are the indices
   * corresponding to those words, index K is the index for the "Swipe right to end recording"
   * page, and index K + 1 is the recording summary page.
   */
  private var currentPage: Int = 0

  /**
   * A timer for the recording, which starts with a time limit of `COUNTDOWN_DURATION` and
   * shows its current value in `countdownText`. When the timer expires, the recording
   * automatically stops and the user is taken to the summary screen.
   */
  private lateinit var countdownTimer: CountDownTimer

  // Prompt data
  /**
   * The prompts data.
   */
  lateinit var prompts: Prompts

  // Recording and session data
  /**
   * The filename for the current video recording.
   */
  private lateinit var filename: String

  /**
   * The file handle for the current video recording.
   */
  private lateinit var outputFile: File

  /**
   * Information on all the clips collected in this session.
   */
  val clipData = ArrayList<ClipDetails>()

  /**
   * Information for the current clip (should be the last item in clipData).
   */
  private var currentClipDetails: ClipDetails? = null

  /**
   * An index used to create unique clipIds.
   */
  private var clipIdIndex = 0

  /**
   * The dataManager object for communicating with the server.
   */
  lateinit var dataManager: DataManager

  /**
   * The username.
   */
  private lateinit var username: String

  /**
   * The start index of the session.
   */
  var sessionStartIndex = -1

  /**
   * The limit index for this session.  Meaning, one past the last prompt index.
   */
  var sessionLimit = -1

  /**
   * General information about the recording session.
   */
  private lateinit var sessionInfo: RecordingSessionInfo

  /**
   * If the app is in tutorial mode.
   */
  private var tutorialMode = false

  /**
   * The time at which the recording session started.
   */
  private lateinit var sessionStartTime: Instant

  /**
   * Because the email and password have been put in a .gitignored file for security
   * reasons, the app is designed to not completely fail to compile if those constants are
   * missing. The constants' existence is checked in SplashScreenActivity and if any of them
   * don't exist, sending email confirmations is disabled. See the README for information on
   * how to set these constants, if that functionality is desired.
   */
  private var emailConfirmationEnabled: Boolean = false


  // CameraX variables
  /**
   * Executor for camera operations
   */
  private lateinit var cameraExecutor: Executor

  /**
   * CameraProvider instance for camera lifecycle management
   */
  private var cameraProvider: ProcessCameraProvider? = null

  /**
   * VideoCapture instance for recording video
   */
  private var videoCapture: VideoCapture<Recorder>? = null

  /**
   * Current active recording
   */
  private var recording: Recording? = null

  /**
   * Camera selector for choosing front camera
   */
  private val cameraSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

  /**
   * Samsung-specific camera helper for better compatibility
   */
  private var samsungCameraHelper: SamsungCameraHelper? = null

  /**
   * Window insets controller for hiding and showing the toolbars.
   */
  var windowInsetsController: WindowInsetsControllerCompat? = null

  // Permissions
  /**
   * Marks whether the user has enabled the necessary permissions to record successfully. If
   * we don't check this, the app will crash instead of presenting an error.
   */
  private var permissions: Boolean = true

  /**
   * When the activity starts, this routine checks the CAMERA and WRITE_EXTERNAL_STORAGE
   * permissions. (We do not need the MICROPHONE permission as we are just recording silent
   * videos.)
   */
  val permission =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
      map.entries.forEach { entry ->
        when (entry.key) {
          Manifest.permission.CAMERA ->
            permissions = permissions && entry.value
        }
      }
    }

  /**
   * Retry camera initialization after delay
   */
  private fun retryInitializeCamera() {
    try {
      initializeCamera()
    } catch (e: Exception) {
      Log.e(TAG, "Second attempt to initialize camera failed", e)
      setResult(RESULT_CAMERA_DIED)
      finish()
    }
  }

  /**
   * This code initializes the CameraX API and sets up video recording
   */
  @SuppressLint("ClickableViewAccessibility")
  private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
    if (!checkCameraPermission()) {
      return@launch
    }

    try {
      // For Samsung devices, use our specialized helper
      if (SamsungCameraHelper.isSamsungDevice()) {
        Log.d(TAG, "Using Samsung-specific camera helper for ${Build.MODEL}")
        
        try {
          // Check if activity is still active
          if (isFinishing || isDestroyed) {
            Log.d(TAG, "Activity no longer active, skipping Samsung camera initialization")
            return@launch
          }
          
          // Clean up any existing camera resources
          if (cameraProvider != null) {
            cameraProvider?.unbindAll()
          }
          if (isRecording) {
            recording?.close()
            recording = null
            isRecording = false
          }
          
          // Create and initialize the Samsung camera helper
          samsungCameraHelper = SamsungCameraHelper(
            this@RecordingActivity,
            this@RecordingActivity,
            cameraView
          )
          
          // Show a message that we're initializing the camera
          showToast("Initializing camera, please wait...")
          resetCameraButton.visibility = View.GONE
          
          // Longer timeout for Samsung initialization
          val initTimeout = Handler(Looper.getMainLooper())
          val timeoutRunnable = Runnable {
            if (!isFinishing && !isDestroyed && !cameraInitialized) {
              Log.w(TAG, "Camera initialization timeout, showing reset button")
              showResetCameraButton()
            }
          }
          initTimeout.postDelayed(timeoutRunnable, 10000) // 10 second timeout
          
          samsungCameraHelper?.initializeCamera(
            onSuccess = { 
              // Check if activity is still active
              if (!isFinishing && !isDestroyed) {
                // Camera initialization successful
                cameraInitialized = true
                initTimeout.removeCallbacks(timeoutRunnable)
                
                // Only start recording if videoCapture is properly initialized
                if (samsungCameraHelper?.isVideoCaptureInitialized() == true) {
                  // Start recording immediately
                  startRecording()
                } else {
                  Log.e(TAG, "VideoCapture still not initialized after successful callback")
                  showToast("Camera initialized but recording not ready. Press Reset Camera to try again.")
                  showResetCameraButton()
                }
                
                // Set up touch listeners for buttons
                setupTouchListeners()
              } else {
                Log.d(TAG, "Activity no longer active, not proceeding with camera initialization")
                initTimeout.removeCallbacks(timeoutRunnable)
              }
            },
            onError = { exception: Exception ->
              // Check if activity is still active
              if (!isFinishing && !isDestroyed) {
                Log.e(TAG, "Samsung camera initialization failed", exception)
                initTimeout.removeCallbacks(timeoutRunnable)
                
                runOnUiThread {
                  Toast.makeText(
                    this@RecordingActivity,
                    "Camera initialization failed: ${exception.message}",
                    Toast.LENGTH_LONG
                  ).show()
                  
                  // Show reset button immediately on error
                  showResetCameraButton()
                }
                
                // Try one more time after a delay
                Handler(Looper.getMainLooper()).postDelayed({
                  if (!isFinishing && !isDestroyed) {
                    retryInitializeCamera()
                  }
                }, 1500)
              } else {
                Log.d(TAG, "Activity no longer active, not handling camera error")
                initTimeout.removeCallbacks(timeoutRunnable)
              }
            }
          )
          return@launch
        } catch (e: Exception) {
          Log.e(TAG, "Failed to initialize Samsung camera helper", e)
          showResetCameraButton()
          // Will fall back to standard initialization below
        }
      }
      
      // Standard camera initialization for non-Samsung devices
      // Get a stable reference to the ProcessCameraProvider
      cameraProvider = ProcessCameraProvider.getInstance(this@RecordingActivity).await()
      
      // Set up the preview use case
      val preview: Preview = Preview.Builder()
        .setTargetRotation(cameraView.display.rotation)
        .build()
      
      preview.setSurfaceProvider(cameraView.surfaceProvider)

      // Set up quality selector for video recording
      val qualitySelector = QualitySelector.from(Quality.HIGHEST)
      
      // Create recorder with quality selector
      val recorder = Recorder.Builder()
        .setQualitySelector(qualitySelector)
        .build()
      
      // Create VideoCapture use case with the recorder
      val videoCaptureObj = VideoCapture.withOutput(recorder)
      
      // Store the videoCapture for later use
      this@RecordingActivity.videoCapture = videoCaptureObj
      
      try {
        // Unbind all use cases before rebinding
        cameraProvider?.unbindAll()
        
        // Bind use cases to camera
        cameraProvider?.bindToLifecycle(
          this@RecordingActivity,
          cameraSelector,
          preview,
          videoCaptureObj
        )
        
        // Start recording
        startRecording()

        // Set up touch listeners for buttons
        setupTouchListeners()

      } catch (exc: Exception) {
        Log.e(TAG, "Use case binding failed", exc)
        // Notify the user about camera issues
        runOnUiThread {
          Toast.makeText(this@RecordingActivity, 
            "Camera initialization failed: ${exc.message}", 
            Toast.LENGTH_LONG).show()
          showResetCameraButton()
        }
      }
      
    } catch (exc: Exception) {
      Log.e(TAG, "Camera initialization failed", exc)
      // Notify the user about camera issues
      runOnUiThread {
        Toast.makeText(this@RecordingActivity, 
          "Camera initialization failed: ${exc.message}", 
          Toast.LENGTH_LONG).show()
        showResetCameraButton()
      }
    }
  }

  /**
   * Set up touch listeners for buttons
   */
  private fun setupTouchListeners() {
    recordButton.setOnTouchListener { view, event ->
      return@setOnTouchListener recordButtonOnTouchListener(view, event)
    }

    restartButton.setOnTouchListener { view, event ->
      return@setOnTouchListener restartButtonOnTouchListener(view, event)
    }

    finishedButton.setOnTouchListener { view, event ->
      return@setOnTouchListener finishedButtonOnTouchListener(view, event)
    }

    resetCameraButton = findViewById(R.id.resetCameraButton)
    resetCameraButton.setOnClickListener {
      handleResetCamera()
    }
  }

  private fun newClipId(): String {
    val output = "${sessionInfo.sessionId}-${padZeroes(clipIdIndex, 3)}"
    clipIdIndex += 1
    return output
  }

  private fun recordButtonOnTouchListener(view: View, event: MotionEvent): Boolean {
    /**
     * Do nothing if the record button is disabled.
     */
    if (!recordButtonEnabled) {
      return false
    }

    when (event.action) {
      MotionEvent.ACTION_DOWN -> lifecycleScope.launch(Dispatchers.IO) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val now = Instant.now()
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
        dataManager.logToServerAtTimestamp(timestamp, "recordButton down")
        currentClipDetails =
          ClipDetails(newClipId(), sessionInfo.sessionId, filename,
          prompts.array[sessionStartIndex + currentPage], sessionStartTime)
        currentClipDetails!!.startButtonDownTimestamp = now
        currentClipDetails!!.lastModifiedTimestamp = now
        clipData.add(currentClipDetails!!)
        dataManager.saveClipData(currentClipDetails!!)

        isSigning = true
        runOnUiThread {
          animateGoText()
        }
      }

      MotionEvent.ACTION_UP -> lifecycleScope.launch(Dispatchers.IO) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
        val now = Instant.now()
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
        dataManager.logToServerAtTimestamp(timestamp, "recordButton up")
        if (currentClipDetails != null) {
          currentClipDetails!!.startButtonUpTimestamp = now
          currentClipDetails!!.lastModifiedTimestamp = now
          dataManager.saveClipData(currentClipDetails!!)
        }
        runOnUiThread {
          setButtonState(recordButton, false)
          setButtonState(restartButton, true)
          setButtonState(finishedButton, false)
        }
      }
    }
    return true
  }

  private fun finishedButtonOnTouchListener(view: View, event: MotionEvent): Boolean {

    Log.d(TAG, "finishedButtonOnTouchListener ${event}")
    when (event.action) {
      MotionEvent.ACTION_DOWN -> lifecycleScope.launch(Dispatchers.IO) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        dataManager.logToServer("finishedButton down")
        goToSummaryPage()
      }

      MotionEvent.ACTION_UP -> lifecycleScope.launch(Dispatchers.IO) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
        Log.e(TAG, "Finished button should already be gone.")
        dataManager.logToServer("finishedButton up")
      }
    }
    return true
  }
  private fun restartButtonOnTouchListener(view: View, event: MotionEvent): Boolean {

    when (event.action) {
      MotionEvent.ACTION_DOWN -> lifecycleScope.launch(Dispatchers.IO) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val now = Instant.now()
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
        dataManager.logToServerAtTimestamp(timestamp, "restartButton down")
        val lastClipDetails = currentClipDetails!!
        lastClipDetails.restartButtonDownTimestamp = now
        lastClipDetails.lastModifiedTimestamp = now
        lastClipDetails.valid = false
        dataManager.saveClipData(lastClipDetails)

        currentClipDetails =
          ClipDetails(newClipId(), sessionInfo.sessionId,
            filename, prompts.array[sessionStartIndex + currentPage], sessionStartTime)
        currentClipDetails!!.startButtonDownTimestamp = now
        currentClipDetails!!.lastModifiedTimestamp = now
        clipData.add(currentClipDetails!!)
        dataManager.saveClipData(currentClipDetails!!)

        isSigning = true
        runOnUiThread {
          setButtonState(recordButton, false)
          setButtonState(restartButton, true)
          setButtonState(finishedButton, false)
          animateGoText()
        }
      }

      MotionEvent.ACTION_UP -> lifecycleScope.launch(Dispatchers.IO) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
        val now = Instant.now()
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
        dataManager.logToServerAtTimestamp(timestamp, "restartButton up")
        if (currentClipDetails != null) {
          currentClipDetails!!.startButtonUpTimestamp = now
          currentClipDetails!!.lastModifiedTimestamp = now
          dataManager.saveClipData(currentClipDetails!!)
        }
      }
    }
    return true
  }

  fun goToSummaryPage() {
    isSigning = false

    if (!prompts.useSummaryPage) {
      concludeRecordingSession()
    }
    runOnUiThread {
      // Move to the next prompt and allow the user to swipe back and forth.
      sessionPager.setCurrentItem(sessionLimit - sessionStartIndex + 1, false)
      sessionPager.isUserInputEnabled = false
    }
  }

  /**
   * Starts the camera recording
   */
  private fun startRecording() {
    if (isRecording) return
    
    // Check if activity is still active
    if (isFinishing || isDestroyed) {
      Log.d(TAG, "Activity is finishing or destroyed, not starting recording")
      return
    }
    
    try {
      // Initialize sessionStartTime when recording starts
      if (!::sessionStartTime.isInitialized) {
        sessionStartTime = Instant.now()
      }
      
      val outputFile = createOutputFile()
      if (outputFile == null) {
        showToast("Failed to create output file")
        return
      }
      
      if (SamsungCameraHelper.isSamsungDevice()) {
        // For Samsung, give a small delay before starting
        Handler(Looper.getMainLooper()).postDelayed({
          // Check again if activity is still active
          if (isFinishing || isDestroyed) {
            Log.d(TAG, "Activity is no longer active, aborting delayed recording start")
            return@postDelayed
          }
          
          samsungCameraHelper?.startRecording(
            outputFile,
            onSuccess = {
              // Final activity state check
              if (!isFinishing && !isDestroyed) {
                isRecording = true
                updateUIForRecordingState()
              }
            },
            onError = { e ->
              // Final activity state check
              if (!isFinishing && !isDestroyed) {
                Log.e(TAG, "Samsung recording failed", e)
                showToast("Failed to start recording. Retrying...")
                
                // Reset camera state and try again after delay
                samsungCameraHelper?.resetCameraState()
                Handler(Looper.getMainLooper()).postDelayed({
                  // Check if activity is still active before retry
                  if (!isFinishing && !isDestroyed) {
                    samsungCameraHelper?.startRecording(
                      outputFile,
                      onSuccess = {
                        // Final activity state check
                        if (!isFinishing && !isDestroyed) {
                          isRecording = true
                          updateUIForRecordingState()
                          // Hide reset button on success
                          resetCameraButton.visibility = View.GONE
                        }
                      },
                      onError = { e2 ->
                        // Final activity state check
                        if (!isFinishing && !isDestroyed) {
                          Log.e(TAG, "Samsung recording retry failed", e2)
                          showToast("Failed to start recording: ${e2.message}")
                          // Show reset button after failed retry
                          showResetCameraButton()
                        }
                      }
                    )
                  }
                }, 1000)
              }
            }
          )
        }, 500) // Delay before starting recording on Samsung
      } else {
        // Non-Samsung recording code
        val videoCapture = this.videoCapture ?: throw IllegalStateException("VideoCapture not initialized")
        
        // Configure output options
        val outputOptions = FileOutputOptions.Builder(outputFile)
          .build()
        
        // Start recording
        recording = videoCapture.output
          .prepareRecording(this, outputOptions)
          .start(cameraExecutor) { event ->
            // Final activity state check inside event handler
            if (!isFinishing && !isDestroyed) {
              when (event) {
                is VideoRecordEvent.Start -> {
                  Log.i(TAG, "Recording started")
                  isRecording = true
                  updateUIForRecordingState()
                }
                is VideoRecordEvent.Finalize -> {
                  if (event.hasError()) {
                    Log.e(TAG, "Recording failed: ${event.error}")
                    showToast("Recording failed: ${event.error}")
                  } else {
                    Log.i(TAG, "Recording successfully finalized")
                  }
                }
                else -> {
                  // Status or other events
                  Log.d(TAG, "Sent VideoRecordEvent class ${event.javaClass.simpleName}")
                }
              }
            }
          }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start recording", e)
      showToast("Failed to start recording: ${e.message}")
    }
  }

  private fun stopRecording() {
    if (!isRecording) return
    
    // Check if activity is still active
    if (isFinishing || isDestroyed) {
      Log.d(TAG, "Activity is finishing or destroyed, aborting stopRecording")
      isRecording = false // Force reset the flag
      return
    }
    
    try {
      if (SamsungCameraHelper.isSamsungDevice()) {
        // For Samsung devices, use our specialized helper
        try {
          samsungCameraHelper?.stopRecording()
        } catch (e: Exception) {
          Log.e(TAG, "Error stopping Samsung recording", e)
        }
      } else {
        // Non-Samsung stop recording
        try {
          recording?.stop()
        } catch (e: Exception) {
          Log.e(TAG, "Error stopping recording, trying close", e)
          recording?.close()
        } finally {
          recording = null
        }
      }
      
      isRecording = false
      updateUIForRecordingState()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to stop recording", e)
      
      // Check if activity is still active before showing toast
      if (!isFinishing && !isDestroyed) {
        showToast("Failed to stop recording: ${e.message}")
      }
      
      // Force recording state to false to allow new recordings
      isRecording = false
      updateUIForRecordingState()
    }
  }

  fun setButtonState(button: View, visible: Boolean) {
    if (visible) {
      button.visibility = View.VISIBLE
      // button.isClickable = true
      // button.isFocusable = true
    } else {
      button.visibility = View.GONE
      // button.isClickable = false
      // button.isFocusable = false
    }
  }


  /**
   * Handler code for when the activity restarts. Right now, we return to the splash screen if the
   * user exits mid-session, as the app is continuously recording throughout this activity's
   * lifespan.
   */
  override fun onRestart() {
    super.onRestart()
    Log.e(TAG, "RecordingActivity.onRestart() called which should be impossible.")
    if (isRecording) {
      sessionInfo.result = "RESULT_ACTIVITY_STOPPED"
      stopRecorder()
      setResult(RESULT_ACTIVITY_STOPPED)
    }
    dataManager.logToServer("onRestart called.")
    finish()
  }

  /**
   * Handles stopping the recording session.
   */
  override fun onStop() {
    windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
    Log.d(TAG, "Recording Activity: onStop")
    try {
      dataManager.logToServer("onStop called.")
      if (isRecording) {
        sessionInfo.result = "RESULT_ACTIVITY_STOPPED"
        stopRecorder()
        setResult(RESULT_ACTIVITY_STOPPED)
      }
      /**
       * This is remnant code from when we were attempting to find and fix a memory leak
       * that occurred if the user did too many recording sessions in one sitting. It is
       * unsure whether this helped; however, we will leave it as-is for now.
       */
      sessionPager.adapter = null
      super.onStop()
    } catch (exc: Throwable) {
      Log.e(TAG, "Error in RecordingActivity.onStop()", exc)
    }
    UploadService.pauseUploadTimeout(UploadService.UPLOAD_RESUME_ON_STOP_RECORDING_TIMEOUT)
    CoroutineScope(Dispatchers.IO).launch {
      // It's important that UploadService has a pause signal at this point, so that in the
      // unlikely event that we have been idle for the full amount of time and the video is
      // uploading, it will abort and we can acquire the lock in a reasonable amount of time.
      dataManager.persistData()
    }
    finish()
  }

  /**
   * Handle the activity being destroyed.
   */
  override fun onDestroy() {
    try {
      // Ensure all camera resources are properly released
      if (isRecording) {
        isRecording = false
        try {
          recording?.close()
          recording = null
        } catch (e: Exception) {
          Log.e(TAG, "Error closing recording in onDestroy", e)
        }
      }
      
      // Release camera resources
      try {
        // Release standard camera resources
        cameraProvider?.unbindAll()
        cameraProvider = null
        
        // Release Samsung-specific camera resources if applicable
        samsungCameraHelper?.releaseCamera()
        samsungCameraHelper = null
      } catch (e: Exception) {
        Log.e(TAG, "Error unbinding camera in onDestroy", e)
      }
      
      // Additional cleanup for Samsung devices
      if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
        try {
          // Force garbage collection to help free camera resources
          System.gc()
        } catch (e: Exception) {
          Log.e(TAG, "Error with Samsung-specific cleanup", e)
        }
      }
      
      super.onDestroy()
      
      // Shutdown camera executor
      if (::cameraExecutor.isInitialized) {
        cameraExecutor.toString() // No real shutdown needed for MainExecutor
      }
    } catch (exc: Throwable) {
      Log.e(TAG, "Error in RecordingActivity.onDestroy()", exc)
    }
  }

  private fun stopRecorder() {
    if (isRecording) {
      isRecording = false
      Log.i(TAG, "stopRecorder: stopping recording.")

      // Stop the current recording
      val currentRecording = recording
      if (currentRecording != null) {
        currentRecording.stop()
        recording = null
      }
      
      // Release camera resources
      cameraProvider?.unbindAll()
      
      // Cancel the countdown timer
      if (::countdownTimer.isInitialized) {
      countdownTimer.cancel()
      }

      runOnUiThread {
        recordingLightView.visibility = View.GONE
      }

      CoroutineScope(Dispatchers.IO).launch {
        val now = Instant.now()
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
        val json = JSONObject()
        json.put("filename", filename)
        json.put("endTimestamp", timestamp)
        dataManager.addKeyValue("recording_stopped-${timestamp}", json, "recording")
        dataManager.registerFile(outputFile.relativeTo(applicationContext.filesDir).path)
        prompts.savePromptIndex()
        
        // Ensure startTimestamp is set
        if (sessionInfo.startTimestamp == null) {
          sessionInfo.startTimestamp = if (::sessionStartTime.isInitialized) sessionStartTime else now
        }
        
        sessionInfo.endTimestamp = now
        sessionInfo.finalPromptIndex = prompts.promptIndex
        dataManager.saveSessionInfo(sessionInfo)
        
        // Handle duration calculation with null checks
        if (sessionInfo.startTimestamp != null && sessionInfo.endTimestamp != null) {
        dataManager.updateLifetimeStatistics(
          Duration.between(sessionInfo.startTimestamp, sessionInfo.endTimestamp)
        )
        } else {
          Log.e(TAG, "Cannot calculate duration: startTimestamp=${sessionInfo.startTimestamp}, endTimestamp=${sessionInfo.endTimestamp}")
        }
        
        // Persist the data. This will lock the dataManager for a few seconds, which is
        // only acceptable because we are not recording.
        dataManager.persistData()
      }
      Log.d(TAG, "Email confirmations enabled? = $emailConfirmationEnabled")
      if (emailConfirmationEnabled) {
        sendConfirmationEmail()
      }
      Log.i(TAG, "stopRecorder: finished")
    } else {
      Log.i(TAG, "stopRecorder: called with isRecording == false")
    }
  }

  /**
   * Entry point for the RecordingActivity.
   */
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    windowInsetsController =
      WindowCompat.getInsetsController(window, window.decorView)?.also {
        it.systemBarsBehavior =
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      }

    dataManager = DataManager(applicationContext)

    // Calculate the display size to determine whether to use mobile or tablet layout.
    val displayMetrics = resources.displayMetrics
    val heightInches = displayMetrics.heightPixels / displayMetrics.ydpi
    val widthInches = displayMetrics.widthPixels / displayMetrics.xdpi
    val diagonal = sqrt((heightInches * heightInches) + (widthInches * widthInches))
    Log.i(TAG, "Computed screen size: $diagonal inches")

    val binding: ViewBinding
    isTablet = diagonal > TABLET_SIZE_THRESHOLD_INCHES
    binding = ActivityRecordBinding.inflate(this.layoutInflater)

    val view = binding.root
    setContentView(view)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    // Set up view pager
    this.sessionPager = findViewById(R.id.sessionPager)

    // Fetch word data, user id, etc. from the splash screen activity which
    // initiated this activity
    val bundle = this.intent.extras ?: Bundle()

    emailConfirmationEnabled = bundle.getBoolean("SEND_CONFIRMATION_EMAIL")

    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

    runBlocking {
      prompts = dataManager.getPrompts() ?: throw IllegalStateException("prompts not available.")
      username = dataManager.getUsername() ?: throw IllegalStateException("username not available.")
      tutorialMode = dataManager.getTutorialMode()
      val sessionType = if (tutorialMode) "tutorial" else "normal"
      val sessionId = dataManager.newSessionId()
      if (tutorialMode) {
        filename = "tutorial-${username}-${sessionId}-${timestamp}.mp4"
      } else {
        filename = "${username}-${sessionId}-${timestamp}.mp4"
      }
      sessionStartIndex = prompts.promptIndex
      val sessionLength =
          if (tutorialMode) DEFAULT_TUTORIAL_SESSION_LENGTH else DEFAULT_SESSION_LENGTH
      sessionLimit = min(prompts.array.size, prompts.promptIndex + sessionLength)
      sessionInfo = RecordingSessionInfo(
        sessionId, filename, dataManager.getDeviceId(), username, sessionType,
        sessionStartIndex, sessionLimit
      )
      dataManager.saveSessionInfo(sessionInfo)
    }

    dataManager.logToServer(
        "Setting up recording with filename ${filename} for prompts " +
            "[${prompts.promptIndex}, ${sessionLimit})")

    // Set title bar text
    title = "${prompts.promptIndex + 1} of ${prompts.array.size}"

    // Enable record button
    recordButton = findViewById(R.id.recordButton)
    recordButton.isHapticFeedbackEnabled = true
    setButtonState(recordButton, true)

    restartButton = findViewById(R.id.restartButton)
    restartButton.isHapticFeedbackEnabled = true
    setButtonState(restartButton, false)

    finishedButton = findViewById(R.id.finishedButton)
    finishedButton.isHapticFeedbackEnabled = true
    setButtonState(finishedButton, false)

    sessionPager.adapter = WordPagerAdapter(this, prompts.useSummaryPage)

    // Set up swipe handler for the word selector UI
    sessionPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
      /**
       * Page changed
       */
      override fun onPageSelected(position: Int) {
        Log.d(
          TAG,
          "onPageSelected(${position}) sessionPager.currentItem ${sessionPager.currentItem} currentPage (before updating) ${currentPage}"
        )
        if (currentClipDetails != null) {
          val now = Instant.now()
          val timestamp = DateTimeFormatter.ISO_INSTANT.format(now)
          if (currentPage < sessionPager.currentItem) {
            // Swiped forward (currentPage is still the old value)
            currentClipDetails!!.swipeForwardTimestamp = now
          } else {
            // Swiped backwards (currentPage is still the old value)
            currentClipDetails!!.swipeBackTimestamp = now
          }
          currentClipDetails!!.lastModifiedTimestamp = now
          val saveClipDetails = currentClipDetails!!
          currentClipDetails = null
          CoroutineScope(Dispatchers.IO).launch {
            dataManager.saveClipData(saveClipDetails)
          }
        }
        currentPage = sessionPager.currentItem
        super.onPageSelected(currentPage)
        if (endSessionOnClipEnd) {
          prompts.promptIndex += 1
          goToSummaryPage()
          return
        }
        val promptIndex = sessionStartIndex + currentPage

        if (promptIndex < sessionLimit) {
          dataManager.logToServer("selected page for promptIndex ${promptIndex}")
          prompts.promptIndex = promptIndex
          runOnUiThread {
            title = "${prompts.promptIndex + 1} of ${prompts.array.size}"

            setButtonState(recordButton, true)
            setButtonState(restartButton, false)
            setButtonState(finishedButton, false)
          }
        } else if (promptIndex == sessionLimit) {
          dataManager.logToServer("selected last chance page (promptIndex ${promptIndex})")
          prompts.promptIndex = sessionLimit
          /**
           * Page to give the user a chance to swipe back and record more before
           * finishing.
           */
          title = ""

          setButtonState(recordButton, false)
          setButtonState(restartButton, false)
          setButtonState(finishedButton, true)
        } else {
          dataManager.logToServer("selected corrections page (promptIndex ${promptIndex})")
          if (!prompts.useSummaryPage) {
            // Shouldn't happen, but just in case.
            concludeRecordingSession()
          }
          title = ""

          setButtonState(recordButton, false)
          setButtonState(restartButton, false)
          setButtonState(finishedButton, false)
          sessionPager.isUserInputEnabled = false

          UploadService.pauseUploadTimeout(UploadService.UPLOAD_RESUME_ON_IDLE_TIMEOUT)
          sessionInfo.result = "ON_CORRECTIONS_PAGE"
          stopRecorder()
        }
      }
    })

    // Set up the camera preview
    cameraView = findViewById(R.id.cameraPreview)

    // Initialize sessionStartTime
    sessionStartTime = Instant.now()
    
    // Set session start timestamp
    if (sessionInfo.startTimestamp == null) {
      sessionInfo.startTimestamp = sessionStartTime
      // Save the updated session info
      CoroutineScope(Dispatchers.IO).launch {
        dataManager.saveSessionInfo(sessionInfo)
      }
    }

    val aspectRatioConstraint = findViewById<ConstraintLayout>(R.id.aspectRatioConstraint)
    val layoutParams = aspectRatioConstraint.layoutParams
    layoutParams.height = layoutParams.width * 4 / 3
    aspectRatioConstraint.layoutParams = layoutParams

    recordingLightView = findViewById(R.id.recordingLight)
    recordingLightView.visibility = View.GONE
    
    // Initialize camera executor
    cameraExecutor = ContextCompat.getMainExecutor(this)

    // Apply Samsung-specific optimizations
    if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
      // Ensure camera resources are properly released if this activity is recreated
      if (isRecording) {
        isRecording = false
        videoCapture = null
        cameraProvider?.unbindAll()
      }
      
      // Set specific camera parameters known to work better with Samsung devices
      window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
      
      // Set application to high priority to reduce the chance of resource reclamation
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        try {
          // Process.setProcessImportance is only available in API 33+
          activityManager.appTasks.firstOrNull()?.setExcludeFromRecents(false)
        } catch (e: Exception) {
          Log.e(TAG, "Failed to set process importance", e)
        }
      }
    }

    resetCameraButton = findViewById(R.id.resetCameraButton)
    resetCameraButton.visibility = View.GONE
  }

  private fun animateGoText() {
    val goText = findViewById<TextView>(R.id.goText)
    goText.visibility = View.VISIBLE

    // Set the pivot point for SCALE_X and SCALE_Y transformations to the
    // top-left corner of the zoomed-in view. The default is the center of
    // the view.
    //binding.expandedImage.pivotX = 0f
    //binding.expandedImage.pivotY = 0f

    // Construct and run the parallel animation of the four translation and
    // scale properties: X, Y, SCALE_X, and SCALE_Y.
    var expandAnimator = AnimatorSet().apply {
      play(
        ObjectAnimator.ofFloat(
          goText,
          View.SCALE_X,
          .5f,
          2f
        )
      ).apply {
        with(
          ObjectAnimator.ofFloat(
            goText,
            View.SCALE_Y,
            .5f,
            2f
          )
        )
      }
      duration = 500
      interpolator = CycleInterpolator(0.5f)
      addListener(object : AnimatorListenerAdapter() {

        override fun onAnimationEnd(animation: Animator) {
          // currentAnimator = null
          goText.visibility = View.GONE
        }

        override fun onAnimationCancel(animation: Animator) {
          // currentAnimator = null
          goText.visibility = View.GONE
        }
      })
      start()
    }
    /*
    var contractAnimator = AnimatorSet().apply {
      play(
        ObjectAnimator.ofFloat(
          goText,
          View.SCALE_X,
          2f,
          0f
        )
      ).apply {
        with(
          ObjectAnimator.ofFloat(
            goText,
            View.SCALE_Y,
            2f,
            0f
          )
        )
      }
      duration = 10000
      interpolator = DecelerateInterpolator()
      addListener(object : AnimatorListenerAdapter() {

        override fun onAnimationEnd(animation: Animator) {
          // currentAnimator = null
          goText.visibility = View.GONE
        }

        override fun onAnimationCancel(animation: Animator) {
          // currentAnimator = null
          goText.visibility = View.GONE
        }
      })
      start()
    }
    val animations = AnimationSet(false)
    animations.addAnimation(expandAnimator)
    animations.addAnimation(contractAnimator)
    */
  }

  /**
   * Handle activity resumption (typically from multitasking)
   */
  override fun onResume() {
    super.onResume()

    windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
    
    // Special handling for Samsung devices
    if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
      Log.d(TAG, "Running on Samsung device: ${Build.MODEL}")
      
      // Some Samsung devices need additional time to initialize the camera
      Thread.sleep(100)
    }
    
    /**
     * If we already finished the recording activity, no need to restart the camera
     */
    if (sessionPager.currentItem >= prompts.array.size) {
      return
    } else if (!cameraInitialized) {
      try {
        initializeCamera()
      cameraInitialized = true
      } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize camera on resume", e)
        Toast.makeText(this, "Camera initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
        
        // Try one more time after a short delay on Samsung devices
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
          Handler(Looper.getMainLooper()).postDelayed({
            try {
              initializeCamera()
            } catch (e2: Exception) {
              Log.e(TAG, "Second attempt to initialize camera failed", e2)
            }
          }, 500)
        }
      }
    }
  }

  /**
   * Finish the recording session and close the activity.
   */
  fun concludeRecordingSession() {
    sessionInfo.result = "RESULT_OK"
    stopRecorder()
    runBlocking {
      dataManager.saveSessionInfo(sessionInfo)
    }
    setResult(RESULT_OK)

    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notification = dataManager.createNotification(
      "Recording Session Completed", "still need to upload")
    notificationManager.notify(UploadService.NOTIFICATION_ID, notification)

    finish()
  }

  /**
   * Returns whether the current activity is running in tablet mode. Used by the video previews
   * on the summary screen to determine whether the video preview needs to be swapped to 4:3
   * (instead of 3:4).
   */
  fun isTablet(): Boolean {
    return isTablet
  }


  /**
   * Handles the creation and sending of a confirmation email, allowing us to track
   * the user's progress.
   */
  private fun sendConfirmationEmail() {
    // TODO test this code.
    val output = JSONObject()
    val clips = JSONArray()
    for (i in 0..clipData.size-1) {
      val clipDetails = clipData[i]
      clips.put(i, clipDetails.toJson())
    }
    output.put("sessionInfo", sessionInfo.toJson())
    output.put("clips", clips)

    val subject = "Recording confirmation for $username"

    val body = "The user '$username' recorded ${clipData.size} clips for prompts index range " +
        "${sessionStartIndex} to ${sessionLimit} into " +
        "file $filename\n\n" +
        output.toString(2) + "\n\n"

    thread {
      Log.d(TAG, "Running thread to send email...")

      /**
       * Send the email from `sender` (authorized by `password`) to the emails in
       * `recipients`. The reason we don't use `R.string.confirmation_email_sender` is
       * that the file containing these credentials is not published to the Internet,
       * so people downloading this repository would face compilation errors unless
       * they create this file for themselves (which is detailed in the README).
       *
       * To let people get up and running quickly, we just check for the existence of
       * these string resources manually instead of making people create an app password
       * in Gmail for what is really just an optional component of the app.
       */
      val senderStringId = resources.getIdentifier(
        "confirmation_email_sender",
        "string", packageName
      )
      val passwordStringId = resources.getIdentifier(
        "confirmation_email_password",
        "string", packageName
      )
      val recipientArrayId = resources.getIdentifier(
        "confirmation_email_recipients",
        "array", packageName
      )

      val sender = resources.getString(senderStringId)
      val password = resources.getString(passwordStringId)
      val recipients = ArrayList(listOf(*resources.getStringArray(recipientArrayId)))

      sendEmail(sender, recipients, subject, body, password)
    }
  }

  private fun checkCameraPermission(): Boolean {
    /**
     * First, check camera permissions. If the user has not granted permission to use the
     * camera, give a prompt asking them to grant that permission in the Settings app, then
     * relaunch the app.
     */
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {

      val errorRoot = findViewById<ConstraintLayout>(R.id.main_root)
      val errorMessage = layoutInflater.inflate(
        R.layout.permission_error, errorRoot,
        false
      )
      errorRoot.addView(errorMessage)

      // Since the user hasn't granted camera permissions, we need to stop here.
      return false
    }

    return true
  }

  /**
   * Show a toast message
   */
  private fun showToast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
  }

  /**
   * Create output file for recording
   */
  private fun createOutputFile(): File? {
    try {
      // Create output file
      outputFile = applicationContext.filenameToFilepath(filename)
      if (outputFile.parentFile?.let { !it.exists() } ?: false) {
        Log.i(TAG, "creating directory ${outputFile.parentFile}.")
        outputFile.parentFile?.mkdirs()
      }
      return outputFile
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create output file", e)
      return null
    }
  }

  /**
   * Update UI based on recording state
   */
  private fun updateUIForRecordingState() {
    // Check if activity is still active before updating UI
    if (isFinishing || isDestroyed) {
      Log.d(TAG, "Activity is finishing or destroyed, not updating UI")
      return
    }
    
    // Ensure sessionStartTime is initialized
    if (!::sessionStartTime.isInitialized) {
      sessionStartTime = Instant.now()
    }
    
    runOnUiThread {
      if (isRecording) {
        recordingLightView.visibility = View.VISIBLE
        
        // Set up the countdown timer
        countdownText = findViewById(R.id.timerLabel)
        countdownTimer = object : CountDownTimer(COUNTDOWN_DURATION, 1000) {
          // Update the timer text every second
          override fun onTick(p0: Long) {
            // Check if activity is still active
            if (!isFinishing && !isDestroyed) {
              val rawSeconds = (p0 / 1000).toInt() + 1
              val minutes = padZeroes(rawSeconds / 60, 2)
              val seconds = padZeroes(rawSeconds % 60, 2)
              countdownText.text = "$minutes:$seconds"
            }
          }

          // When the timer expires, handle session end
          override fun onFinish() {
            // Check if activity is still active
            if (!isFinishing && !isDestroyed) {
              if (isSigning) {
                endSessionOnClipEnd = true
              } else {
                goToSummaryPage()
              }
            }
          }
        }
        countdownTimer.start()
        
        // Enable record button if not currently signing
        if (!isSigning) {
          setButtonState(recordButton, true)
          recordButtonEnabled = true
        }
      } else {
        recordingLightView.visibility = View.GONE
        if (::countdownTimer.isInitialized) {
          countdownTimer.cancel()
        }
      }
    }
  }

  private fun handleResetCamera() {
    // Show feedback that we're resetting
    showToast("Resetting camera...")
    
    // If currently recording, stop it
    if (isRecording) {
      try {
        if (SamsungCameraHelper.isSamsungDevice()) {
          samsungCameraHelper?.stopRecording()
        } else {
          recording?.stop()
          recording = null
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error stopping recording during reset", e)
      }
      isRecording = false
    }
    
    // Hide reset button while we're working
    resetCameraButton.visibility = View.GONE
    
    // Release and re-initialize camera
    if (SamsungCameraHelper.isSamsungDevice()) {
      try {
        // Release Samsung helper
        samsungCameraHelper?.releaseCamera()
        samsungCameraHelper = null
        
        // Small delay before re-initialization
        Handler(Looper.getMainLooper()).postDelayed({
          // Check if activity is still active before re-initializing
          if (!isFinishing && !isDestroyed) {
            // Create and initialize the Samsung camera helper
            samsungCameraHelper = SamsungCameraHelper(
              this@RecordingActivity,
              this@RecordingActivity,
              cameraView
            )
            
            samsungCameraHelper?.initializeCamera(
              onSuccess = { 
                // Check if activity is still active before proceeding
                if (!isFinishing && !isDestroyed) {
                  // Camera initialization successful
                  cameraInitialized = true
                  // Start recording immediately
                  startRecording()
                  // Hide reset button
                  resetCameraButton.visibility = View.GONE
                }
              },
              onError = { exception: Exception ->
                // Check if activity is still active before showing error
                if (!isFinishing && !isDestroyed) {
                  Log.e(TAG, "Samsung camera reset failed", exception)
                  showToast("Camera reset failed: ${exception.message}")
                  // Show reset button again since we failed
                  resetCameraButton.visibility = View.VISIBLE
                }
              }
            )
          } else {
            Log.d(TAG, "Activity no longer active, skipping camera re-initialization")
          }
        }, 1000)
      } catch (e: Exception) {
        Log.e(TAG, "Error during camera reset", e)
        showToast("Camera reset failed: ${e.message}")
        resetCameraButton.visibility = View.VISIBLE
      }
    } else {
      // Standard camera re-initialization
      try {
        cameraProvider?.unbindAll()
        cameraProvider = null
        
        Handler(Looper.getMainLooper()).postDelayed({
          // Check if activity is still active
          if (!isFinishing && !isDestroyed) {
            initializeCamera()
          } else {
            Log.d(TAG, "Activity no longer active, skipping camera re-initialization")
          }
        }, 1000)
      } catch (e: Exception) {
        Log.e(TAG, "Error during standard camera reset", e)
        showToast("Camera reset failed: ${e.message}")
        resetCameraButton.visibility = View.VISIBLE
      }
    }
  }

  private fun showResetCameraButton() {
    resetCameraButton.visibility = View.VISIBLE
  }
} // RecordingActivity

