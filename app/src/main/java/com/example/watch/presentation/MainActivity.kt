package com.example.watch.presentation

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.core.content.ContextCompat
import okhttp3.*
import java.io.IOException
import kotlin.math.sqrt

class MainActivity : Activity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometerValues: FloatArray? = null
    private var gyroscopeValues: FloatArray? = null

    private lateinit var mainLayout: FrameLayout
    private lateinit var centerCircle: LinearLayout
    private lateinit var centerText: TextView
    private lateinit var titleText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()
    private val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    private val serverUrl = "http://192.168.1.9:5001/post"

    private var gestureCounter = 0
    private var lastMovementTime = System.currentTimeMillis()
    private val stillnessThreshold = 0.3f
    private val stillnessDuration = 2000L
    private var isRecording = false

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

        // Title at top
        titleText = TextView(this).apply {
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 40
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
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
        mainLayout.addView(titleText)
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
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mainLayout.keepScreenOn = true

        // Show 3, start recording, and send first data
        showCountdown(3)
        startRecording()
        displayAndSendValues()

        // Schedule UI updates and data sends for 2 and 1
        handler.postDelayed({
            showCountdown(2)
            displayAndSendValues()
        }, 1000)

        handler.postDelayed({
            showCountdown(1)
            displayAndSendValues()
        }, 2000)

        // Stop recording after 3 seconds
        handler.postDelayed({
            stopRecording()
            showDone()
            handler.postDelayed({
                // Go back to Start after 500ms
                showStartScreen()
            }, 500) // Show "Done" for 0.5 seconds
        }, 3000)
    }

    // --------------------------------
    // RECORDING CONTROL
    // --------------------------------
    private fun startRecording() {
        isRecording = true

        try {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            } ?: run {
                Toast.makeText(this, "Accelerometer not available", Toast.LENGTH_SHORT).show()
                return
            }

            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            } ?: run {
                Toast.makeText(this, "Gyroscope not available", Toast.LENGTH_SHORT).show()
                return
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Sensor init failed: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("MainActivity", "Sensor registration failed", e)
            return
        }
    }

    private fun stopRecording() {
        isRecording = false

        // Clear screen-on flags
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mainLayout.keepScreenOn = false

        try {
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {
            Log.e("MainActivity", "Sensor unregister failed: ${e.message}")
        }

        try {
            toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 150)
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to play tone: ${e.message}")
        }
    }

    // --------------------------------
    // SENSOR EVENTS
    // --------------------------------
    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> accelerometerValues = it.values.clone()
                Sensor.TYPE_GYROSCOPE -> gyroscopeValues = it.values.clone()
            }

            val movement = accelerometerValues?.let { acc ->
                sqrt((acc[0] * acc[0] + acc[1] * acc[1] + acc[2] * acc[2]).toDouble()).toFloat()
            } ?: 0f

            if (movement > stillnessThreshold) {
                lastMovementTime = System.currentTimeMillis()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --------------------------------
    // DATA DISPLAY + SEND
    // --------------------------------
    private fun displayAndSendValues() {
        val acc = accelerometerValues
        val gyro = gyroscopeValues
        if (acc == null || gyro == null) return

        val accText = "x = %.2f   y = %.2f   z = %.2f".format(acc[0], acc[1], acc[2])
        val gyroText = "x = %.2f   y = %.2f   z = %.2f".format(gyro[0], gyro[1], gyro[2])

        sendToServer("""ACC: $accText
GYRO: $gyroText""")

        if (System.currentTimeMillis() - lastMovementTime > stillnessDuration) {
            try {
                toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to play gesture tone: ${e.message}")
            }
            gestureCounter++
            sendToServer("GESTURE_END_$gestureCounter")
            lastMovementTime = System.currentTimeMillis()
        }
    }

    // --------------------------------
    // HTTP SEND
    // --------------------------------
    private fun sendToServer(data: String) {
        Log.d("HTTP", "Sending: ${data.replace("", "\n")}")
        val body = FormBody.Builder()
            .add("value", data)
            .add("fileNum", gestureCounter.toString())
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
