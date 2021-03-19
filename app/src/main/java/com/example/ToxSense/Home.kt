package com.example.ToxSense

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.util.Base64
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import kotlinx.android.synthetic.main.home.*
import org.json.JSONException
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.collections.ArrayList
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin


class Home : AppCompatActivity(), LocationListener, SensorEventListener {


    private lateinit var locationManager: LocationManager
    private lateinit var tvGpsLocation: TextView
    private val locationPermissionCode = 2

    private var tfLiteClassifier: TFLiteClassifier = TFLiteClassifier(this@Home)

    private lateinit var mmDevice: BluetoothDevice
    private lateinit var mmInputStream: InputStream
    private lateinit var mmOutputStream: OutputStream
    private lateinit var mmSocket: BluetoothSocket
    private var stopWorker = false

    private lateinit var gpsLat:String
    private lateinit var gpsLon:String

    lateinit var label: TextView
    lateinit var selfaqi: TextView
    lateinit var compassN: TextView
    lateinit var compassE: TextView
    lateinit var compassS: TextView
    lateinit var compassW: TextView
    lateinit var compassL: RelativeLayout
    lateinit var bild: ImageView
    lateinit var displayinfo:TextView
    lateinit var connectB:Button

    //NEW
    private lateinit var sensorManager: SensorManager
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var sensorAngle: Int = 0
    private var sensorLastVals: MutableList<Double> = ArrayList()
    private var compassAngle: Int = 0


    lateinit var btBitmap: Bitmap

    private val executorService: ExecutorService = Executors.newCachedThreadPool()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.home)


        label = findViewById<TextView>(R.id.label)
        selfaqi = findViewById<TextView>(R.id.selfaqi)
        compassN = findViewById<TextView>(R.id.compassN)
        compassS = findViewById<TextView>(R.id.compassS)
        compassE = findViewById<TextView>(R.id.compassE)
        compassW = findViewById<TextView>(R.id.compassW)
        compassL = findViewById<RelativeLayout>(R.id.compassL)
        bild = findViewById<ImageView>(R.id.bild)
        displayinfo=findViewById<TextView>(R.id.serverinfo)
        connectB = findViewById<Button>(R.id.connectB)


        getLocation()

        connectB.setOnClickListener {
        try {
            this.connect_device()
                connectB.text = "Device connected"
                connectB.alpha = .5f
                connectB.isClickable = false
        } catch (e: IOException) {
        }
        }



        title = "ToxSense"

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer ->
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { magneticField ->
            sensorManager.registerListener(this, magneticField, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI)
        }


        val homebutton = findViewById<Button>(R.id.HomeButton)
        homebutton.setOnClickListener {
            val intent = Intent(this, Home::class.java)
            startActivity(intent)
        }

/*        val bluetoothbutton = findViewById<Button>(R.id.BluetoothButton)
        bluetoothbutton.setOnClickListener {
            val intent = Intent(this, Bluetooth::class.java)
            startActivity(intent)
        }*/

        val aqibutton = findViewById<Button>(R.id.AQIButton)
        aqibutton.setOnClickListener {
            Toast.makeText(applicationContext, "noch nicht verbunden", Toast.LENGTH_SHORT)
            val intent = Intent(this, AQI::class.java)
            startActivity(intent)
        }



        tfLiteClassifier
                .initialize()
                .addOnSuccessListener { }
                .addOnFailureListener { e ->
                    Log.e(
                            "tfclassifier",
                            "Error in setting up the classifier.",
                            e
                    )
                }
    }

    @SuppressLint("SetTextI18n")
    fun connect_device() {
        val mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (mBluetoothAdapter == null) {
            label.text = "No bluetooth adapter available"
        }

        if (!mBluetoothAdapter.isEnabled) {
            val enableBluetooth = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBluetooth, 0)
        }

        val pairedDevices: Set<BluetoothDevice> = mBluetoothAdapter.bondedDevices
        if (pairedDevices.isNotEmpty()) {
            for (device in pairedDevices) {
                if (device.name == "ESP32") {
                    mmDevice = device
                    break
                }
            }
        }
        label.text = "Connection Error!"

        val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") //Standard SerialPortService ID

        val mmSocket = mmDevice.createRfcommSocketToServiceRecord(uuid)
        mmSocket.connect()
        mmOutputStream = mmSocket.outputStream
        mmInputStream = mmSocket.inputStream

        beginListenForData()

        label.text = "Bluetooth Opened"
    }

    @SuppressLint("SetTextI18n")
    fun beginListenForData() {
        val handler = Handler()
        val delimiter: Byte = 10 //This is the ASCII code for a newline character
        var stopWorker = false
        var readBufferPosition = 0
        var readBuffer = ByteArray(40960)
        val workerThread = Thread {
            while (!Thread.currentThread().isInterrupted && !stopWorker) {
                try {
                    val bytesAvailable: Int = mmInputStream.available()
                    if (bytesAvailable > 0) {
                        val packetBytes = ByteArray(bytesAvailable)
                        mmInputStream.read(packetBytes)
                        for (i in 0 until bytesAvailable) {
                            val b = packetBytes[i]
                            if (b == delimiter) {
                                val encodedBytes = ByteArray(readBufferPosition)
                                val buf = ByteBuffer.wrap(encodedBytes)
                                System.arraycopy(
                                        readBuffer,
                                        0,
                                        encodedBytes,
                                        0,
                                        encodedBytes.size
                                )
                                val data = String(encodedBytes)
                                readBufferPosition = 0
                                //println(data)
                                try {
                                    var dataClean = data
                                    if (data.contains("/9j/") && data.substring(0, 4) != "/9j/") {
                                        dataClean = "/9j/" + data.split("/9j/")[1]
                                    }
                                    if (data.contains("getHeading")){
                                        dataClean = dataClean.replace("getHeading", "")
                                    }
                                    val imageBytes = Base64.decode(dataClean, Base64.DEFAULT)
                                    btBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                                    btBitmap = btBitmap.rotate(270f) //CHANGE HERE!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!<--------------------------------------
                                    handler.post {
                                        var aqiTxt: String = "Error!"
                                        var aqiBmp: Bitmap? = null
                                        tfLiteClassifier.classifyAsync(btBitmap)
                                                .addOnSuccessListener { resultText ->
                                                    val aqi = resultText[0].toString()
                                                    selfaqi.text = aqi.toFloat().toInt().toString() //????????????????
                                                    selfaqi.setTextColor(Color.WHITE)
                                                    bild.setImageBitmap(resultText[1] as Bitmap)

                                                    val predImg: Bitmap = resultText[1] as Bitmap

                                                    val imgresultbaos = ByteArrayOutputStream()
                                                    predImg.compress(Bitmap.CompressFormat.JPEG, 50, imgresultbaos)
                                                    val imgresultstr = String(imgresultbaos.toByteArray(), StandardCharsets.UTF_8)

                                                    val postUrl = "https://api.toxsense.de"
                                                    val requestQueue = Volley.newRequestQueue(this)

                                                    val postData = JSONObject()
                                                    try {
                                                        postData.put("lat", gpsLat)
                                                        postData.put("lon", gpsLon)
                                                        postData.put("aqi", resultText[0])
                                                        postData.put("img", imgresultstr)
                                                    } catch (e: JSONException) {
                                                        e.printStackTrace()
                                                    }

                                                    val jsonObjectRequest = JsonObjectRequest(Request.Method.POST, postUrl, postData,
                                                            { response ->
                                                                val sResponse = response.toString()
                                                                val sResJson = JSONObject(sResponse)
                                                                val sInfo = sResJson.getString("info")
                                                                val sNorth = sResJson.getString("aqiN")
                                                                val sSouth = sResJson.getString("aqiS")
                                                                val sWest = sResJson.getString("aqiW")
                                                                val sEast = sResJson.getString("aqiE")
                                                                val sSelfAqi = sResJson.getString("selfaqi")

                                                                //MAKE LABELINFO
                                                                displayinfo.text = sInfo + "\ndirection:$sNorth,$sEast,$sSouth,$sWest"
                                                                selfaqi.text = sSelfAqi.toInt().toString()

                                                                //send to device
                                                                val direction = "d$sNorth,$sEast,$sSouth,${sWest}e"
                                                                sendData(direction)

                                                                compassN.text = sNorth
                                                                compassE.text = sEast
                                                                compassS.text = sSouth
                                                                compassW.text = sWest

                                                            },
                                                            { error ->
                                                                error.printStackTrace()
                                                            })
                                                    jsonObjectRequest.retryPolicy = DefaultRetryPolicy(
                                                            30000,
                                                            DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                                                            DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
                                                    )

                                                    requestQueue.add(jsonObjectRequest)

                                                    //Tasks.call(executorService, Callable<wert> {  POSTrequest(resultText[0].toString(), resultText[1] as Bitmap) }).addOnSuccessListener { wert ->

                                                    //}
                                                }
                                                .addOnFailureListener { error ->
                                                    label.text = error.toString()
                                                }
                                    }
                                } catch (t: Throwable) {
                                    if (data.contains("getHeading")){
                                        //println("send heading:$sensorAngle")
                                        sendData("h${sensorAngle}e")
                                    }
                                    if (data.contains("info:")) {
                                        println(data)
                                    }
                                }



                            } else {
                                readBuffer[readBufferPosition++] = b
                            }
                        }
                    }
                } catch (ex: IOException) {
                    stopWorker = true
                }
            }
        }
        workerThread.start()
    }

    @Throws(IOException::class)
    fun sendData(cmd: String?) {
        var command = cmd
        command += "\n"
        mmOutputStream.write(command?.toByteArray())
    }



    fun getLocation() {
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if ((ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
            ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    locationPermissionCode
            )
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000, 5f, this)
    }

    override fun onLocationChanged(location: Location) {
        try {
            tvGpsLocation = findViewById(R.id.textView)
            gpsLat=location.latitude.toString()
            gpsLon=location.longitude.toString()
            val locTxt = " Lat: " + location.latitude + " | Lon: " + location.longitude
            tvGpsLocation.text = locTxt
            println("sendSnap")
            sendData("snap")


        } catch (e: Exception) {
        }

    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        TODO("Not yet implemented")
    }

    override fun onProviderEnabled(provider: String?) {
        TODO("Not yet implemented")
    }

    override fun onProviderDisabled(provider: String?) {
        TODO("Not yet implemented")
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        if (requestCode == locationPermissionCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission Granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    @Throws(IOException::class)
    fun closeBT() {
        stopWorker = true
        if (this::mmOutputStream.isInitialized){
            mmOutputStream.close()
        }
        if (this::mmInputStream.isInitialized){
            mmOutputStream.close()
        }
        if (this::mmSocket.isInitialized){
            mmSocket.close()
        }
    }


    //ORIENTATION SENSOR
    override fun onSensorChanged(event: SensorEvent?) {
        // 1
        if (event == null) {
            return
        }
// 2
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // 3
            System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
        }
        updateOrientationAngles()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // not in use
    }

    private fun updateOrientationAngles() {
        // 1
        SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)
        // 2
        val orientation = SensorManager.getOrientation(rotationMatrix, orientationAngles)
        // 3
        val degrees = (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0

        sensorLastVals.add(orientation[0].toDouble())

        if (sensorLastVals.size > 5){
            sensorLastVals.removeAt(0)
        }

        var x: Double = 0.0
        var y: Double = 0.0
        for (an in sensorLastVals) {
            x += cos(an)
            y += sin(an)
        }
        val degreesAvg = Math.toDegrees(atan2(y, x))

        // 4
        val angleAvg: Int = ((degreesAvg * 100).roundToInt() / 100)

        sensorAngle = degrees.toInt()


        compassL.rotation = - angleAvg.toFloat()
        compassN.rotation = angleAvg.toFloat()
        compassE.rotation = angleAvg.toFloat()
        compassS.rotation = angleAvg.toFloat()
        compassW.rotation = angleAvg.toFloat()

    }

    private fun Bitmap.rotate(degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }



    override fun onDestroy() {
        tfLiteClassifier.close()
        closeBT();
        super.onDestroy()
    }
}