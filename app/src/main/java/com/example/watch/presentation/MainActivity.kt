package com.example.watch.presentation

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import okhttp3.*
import java.io.IOException

class MainActivity : Activity(), SensorEventListener {

    // --- Configuration ---
    private val gestureRecordDuration = 3000L // 3 seconds
    private val sensorDelay = 10000 // 10ms = 100Hz
    private val serverUrl = "http://192.168.93.10:5001/post"
    private val gestureFilePrefix = "e"
    private val csvHeader = "timestamp,accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z"

    // --- State ---
    private lateinit var sensorManager: SensorManager
    private var isRecording = false
    private var startTime = 0L

    // Buffers for sensor data
    private var accelerometerValues: FloatArray? = null
    private var gyroscopeValues: FloatArray? = null
    private val gestureData = mutableListOf<String>()
    private var gestureCounter = 0

    // --- UI ---
    private lateinit var mainLayout: FrameLayout
    private lateinit var centerCircle: LinearLayout
    private lateinit var centerText: TextView

    // --- Utilities ---
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()
    private val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        setupUI()
        showStartScreen()
    }

    // --------------------------------
    // UI SETUP
    // --------------------------------
    private fun setupUI() {
        // Main container
        mainLayout = FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.black))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Center circle container
        centerCircle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER

            // Create circular background with ring
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
            }
            background = drawable

            val size = 280
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER
            }
        }

        // Center text
        centerText = TextView(this).apply {
            text = "Start"
            textSize = 48f
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.black))
            gravity = Gravity.CENTER
        }

        centerCircle.addView(centerText)
        mainLayout.addView(centerCircle)

        // Add click listener to the circle
        centerCircle.setOnClickListener {
            if (!isRecording) {
                startCountdownSequence()
            }
        }

        setContentView(mainLayout)
    }

    // --------------------------------
    // SCREEN STATES
    // --------------------------------
    private fun showStartScreen() {
        centerText.text = "Start"
        centerText.textSize = 48f
        setCenterCircleStyle(true) // white background
    }

    private fun showCountdown(number: Int) {
        centerText.text = number.toString()
        centerText.textSize = 72f
        setCenterCircleStyle(false) // black background with ring
    }

    private fun showDone() {
        centerText.text = "Done"
        centerText.textSize = 48f
        setCenterCircleStyle(true) // white background
    }

    private fun setCenterCircleStyle(isWhiteBackground: Boolean) {
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (isWhiteBackground) {
                setColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
            } else {
                setColor(ContextCompat.getColor(this@MainActivity, android.R.color.black))
                setStroke(4, ContextCompat.getColor(this@MainActivity, android.R.color.white))
            }
        }
        centerCircle.background = drawable

        if (isWhiteBackground) {
            centerText.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.black))
        } else {
            centerText.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
        }
    }

    // --------------------------------
    // COUNTDOWN SEQUENCE
    // --------------------------------
    private fun startCountdownSequence() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mainLayout.keepScreenOn = true

        // --- Phase 1: The 3-Second "Get Ready" Delay ---
        showCountdown(3)
        handler.postDelayed({ showCountdown(2) }, 1000)
        handler.postDelayed({ showCountdown(1) }, 2000)

        // --- Phase 2: Start Actual Recording ---
        // We wait 3000ms (3 seconds) before triggering the recording
        handler.postDelayed({

            // Visual cue that recording has started
            centerText.text = "GO"
            centerText.textSize = 64f

            // Audio cue (short beep) so you don't have to look at the screen
            try {
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            } catch (e: Exception) {
                Log.w("MainActivity", "Tone failed: ${e.message}")
            }

            startRecording() // Sensors actually turn on here

            // --- Phase 3: Stop Recording ---
            // Schedule the stop based on your configured gestureRecordDuration
            handler.postDelayed({
                stopRecording()
                processAndUploadData()
                showDone()

                // Reset to start screen after 1 second
                handler.postDelayed({
                    showStartScreen()
                }, 1000)
            }, gestureRecordDuration)

        }, 3000)
    }
    // --------------------------------
    // RECORDING CONTROL
    // --------------------------------
    private fun startRecording() {
        isRecording = true
        startTime = System.currentTimeMillis()

        gestureData.clear()
        accelerometerValues = null
        gyroscopeValues = null


        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        sensorManager.registerListener(this, accel, sensorDelay)
        sensorManager.registerListener(this, gyro, sensorDelay)
    }

    private fun stopRecording() {
        isRecording = false
        sensorManager.unregisterListener(this)

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mainLayout.keepScreenOn = false

        try {
            toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 150)
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to play tone: ${e.message}")
        }
    }

    private fun processAndUploadData() {
        if (gestureData.isEmpty()) {
            Log.w("MainActivity", "No gesture data collected, skipping upload.")
            return
        }

        val csvData = StringBuilder()
        csvData.append(csvHeader).append("\n")
        gestureData.forEach { line ->
            csvData.append(line).append("\n")
        }

        val fileName = "${gestureFilePrefix}_${gestureCounter}.csv"
        sendToServer(csvData.toString(), fileName)
        gestureCounter++
    }


    // --------------------------------
    // SENSOR EVENTS
    // --------------------------------
    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRecording || event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelerometerValues = event.values.clone()
                // When we get a new accelerometer event, record a new data row
                // using the latest gyroscope data. This makes accelerometer events the
                // "driver" for our sampling rate.
                gyroscopeValues?.let { gyro ->
                    val acc = accelerometerValues!!
                    val timestamp = System.currentTimeMillis() - startTime
                    val csvLine = "$timestamp,${acc[0]},${acc[1]},${acc[2]},${gyro[0]},${gyro[1]},${gyro[2]}"
                    gestureData.add(csvLine)
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroscopeValues = event.values.clone()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --------------------------------
    // HTTP SEND
    // --------------------------------
    private fun sendToServer(csvData: String, fileName: String) {
        Log.d("HTTP", "Uploading $fileName, size: ${csvData.length} bytes")

        val body = FormBody.Builder()
            .add("value", csvData)
            .add("fileNum", gestureCounter.toString())
            .add("fileName", fileName)
            .build()

        val request = Request.Builder()
            .url(serverUrl)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("HTTP", "Failed: ${e.message}")
                handler.post {
                    Toast.makeText(this@MainActivity, "Network error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val code = response.code
                    val responseBody = response.body?.string() ?: ""
                    Log.d("HTTP", "Response code=$code body=$responseBody")

                    if (!response.isSuccessful) {
                        handler.post {
                            Toast.makeText(this@MainActivity, "Server error: $code", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            mainLayout.keepScreenOn = false
            sensorManager.unregisterListener(this)
            toneGen.release()
        } catch (e: Exception) {
            Log.w("MainActivity", "Cleanup failed: ${e.message}")
        }
        handler.removeCallbacksAndMessages(null)
    }
}
