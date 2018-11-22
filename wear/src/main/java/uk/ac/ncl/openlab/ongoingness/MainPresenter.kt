package uk.ac.ncl.openlab.ongoingness

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.NetworkInterface
import java.util.*
import kotlin.collections.ArrayList

class MainPresenter {
    private var view: View? = null
    private val apiUrl = "http://46.101.47.18:3000/api"
    private var index = -1
    private val imageList: ArrayList<Bitmap>? = ArrayList()
    private var presentImage: Bitmap? = null
    private val minBandwidthKbps: Int = 320
    private var token: String? = null
    private val client: OkHttpClient = OkHttpClient.Builder()
            .connectionSpecs(Arrays.asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
            .build()
    private var presentId: String? = null
    private var context: Context? = null

    /**
     * Attach the view to the presenter
     *
     * @param view View to attach
     */
    fun attachView(view: View) {
        this.view = view
    }

    /**
     * Detach the view from the presenter
     * Call this on view's onDestroy method.
     */
    fun detachView() {
        this.view = null
    }

    /**
     * Set the context for http requests
     *
     * @param context
     */
    fun setContext(context: Context) {
        this.context = context
    }

    /**
     * Cycle to next image in the semantic set.
     *
     * @param direction Which direction to increment the images by.
     */
    fun cycle(direction: Direction) {
        // Return if there are no more images in cluster
        if (imageList == null || imageList.isEmpty()) {
            return
        }

        var bitmapToDraw: Bitmap? = null

        when (direction) {
            Direction.BACKWARDS -> {
                index += -1
                if (index < 0) {
                    bitmapToDraw = presentImage
                    index = -1
                } else {
                    bitmapToDraw = imageList[index]
                }
            }
            Direction.FORWARD -> {
                index += 1
                if (index == imageList.size) {
                    index = imageList.size - 1
                    bitmapToDraw = imageList[index]
                } else {
                    bitmapToDraw = imageList[index]
                }
            }
        }

        view?.updateBackground(bitmapToDraw!!)
        storeBitmap(bitmapToDraw!!)
    }

    /**
     * Get an id of an image from the present,
     * get links of new item of media
     * draw the image of the present on the background.
     *
     * Requires a client to get the next present image id from api, get new images, and download
     * the next image to show.
     */
    fun updateSemanticContext() {
        val url = "$apiUrl/media/request/present"
        val gson = Gson()
        val request = Request.Builder()
                .url(url)
                .header("x-access-token", token)
                .build()

        if (token == null) throw Error("Token cannot be empty")
        if (!hasConnection()) return

        Log.d("updateSemanticContext", "Updating semantic context")

        client.newCall(request)
                .enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        e.printStackTrace()
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val genericResponse: GenericResponse = gson.fromJson(response.body()?.string(), GenericResponse::class.java)
                        val id = genericResponse.payload
                        presentId = id

                        populateImages(presentId!!)
                        fetchPresentImage(presentId!!)
                    }
                })
    }

    /**
     * Prefetch images from the past and store in an array of bitmaps.
     *
     * @param presentId
     */
    fun populateImages(presentId: String) {
        if (token == null) throw Error("Token cannot be empty")
        if (!hasConnection()) return

        val url = "$apiUrl/media/links/$presentId"
        val gson = Gson()
        val request = Request.Builder()
                .url(url)
                .header("x-access-token", token!!)
                .build()

        Log.d("getImageIdsInSet", "Getting ids in set")

        client.newCall(request)
                .enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e("getImageIdsInSet", "Error in request")
                        e.printStackTrace()
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val linkResponse: LinkResponse = gson.fromJson(response.body()?.string(), LinkResponse::class.java)
                        val links = linkResponse.payload

                        // Clear array of image bitmaps.
                        imageList?.clear()
                        index = -1

                        // Pre fetch images
                        fetchBitmaps(links)
                    }
                })
    }

    /**
     * Fetch the present image from the api and tell view to update display.
     *
     * @param presentId Id of present image.
     */
    private fun fetchPresentImage(presentId: String) {
        if (token == null) throw Error("Token cannot be empty")
        if (!hasConnection()) return

        val url = "$apiUrl/media/show/$presentId/$token"
        val request = Request.Builder().url(url).build()

        Log.d("fetchPresentImage", "Fetching image from $url")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call?, e: IOException?) {
                e?.printStackTrace()
            }

            override fun onResponse(call: Call?, response: Response) {
                try {
                    // Create an input stream from the image.
                    val inputStream = response.body()?.byteStream()

                    // Scale it to match device size.
                    presentImage = Bitmap.createScaledBitmap(BitmapFactory.decodeStream(inputStream), view?.getScreenSize()!!, view?.getScreenSize()!!, false)

                    // Update background to downloaded image.
                    view?.updateBackground(presentImage!!)
                } catch (error: Error) {
                    error.printStackTrace()
                }
            }
        })
    }

    /**
     * Fetch images of of the past and add to an array list.
     *
     * @param links Array of links to fetch bitmaps from.
     */
    private fun fetchBitmaps(links: Array<String>) {
        if (token == null) throw Error("Token cannot be empty")
        if (!hasConnection()) return

        for (id in links) {
            val url = "$apiUrl/media/show/$id/$token"
            val request = Request.Builder().url(url).build()

            Log.d("fetchBitmaps", "Fetching bitmap from $url")

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call?, e: IOException?) {
                    e?.printStackTrace()
                }

                override fun onResponse(call: Call?, response: Response) {
                    try {
                        val inputStream = response.body()?.byteStream()
                        imageList?.add(Bitmap.createScaledBitmap(BitmapFactory.decodeStream(inputStream), view?.getScreenSize()!!, view?.getScreenSize()!!, false))
                    } catch (error: Error) {
                        error.printStackTrace()
                    }
                }
            })
        }
    }

    /**
     * Generate a token from the api using the devices mac address.
     *
     * @param callback function to call after generating a token
     */
    fun generateToken(callback: () -> Unit) {
        val url = "$apiUrl/auth/mac"
        val gson = Gson()
        val mac: String = getMacAddress() // Get mac address
        val formBody = FormBody.Builder()
                .add("mac", mac)
                .build()
        val request = Request.Builder()
                .url(url)
                .post(formBody)
                .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                val genericResponse: GenericResponse = gson.fromJson(response.body()?.string(), GenericResponse::class.java)

                // Set token
                token = genericResponse.payload

                callback()
            }
        })
    }

    /**
     * Get mac address from IPv6 address
     *
     * @return device mac address
     */
    private fun getMacAddress(): String {
        try {
            val all: List<NetworkInterface> = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif: NetworkInterface in all) {
                if (nif.name.toLowerCase() != "wlan0") continue

                val macBytes: ByteArray = nif.hardwareAddress ?: return ""

                val res1 = StringBuilder()
                for (b: Byte in macBytes) {
                    res1.append(String.format("%02X", b))
                }

                return res1.toString()
            }
        } catch (ex: Exception) {
            println(ex.stackTrace)
        }
        return ""
    }

    /**
     * Check that the activity has an active network connection.
     *
     * @return boolean if device has connection
     */
    private fun hasConnection(): Boolean {
        // Check a network is available
        val mConnectivityManager: ConnectivityManager = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork: Network? = mConnectivityManager.activeNetwork

        if (activeNetwork != null) {
            val bandwidth = mConnectivityManager.getNetworkCapabilities(activeNetwork).linkDownstreamBandwidthKbps
            if (bandwidth < minBandwidthKbps) {
                // Request a high-bandwidth network
                Log.d("OnCreate", "Request high-bandwidth network")
                return false
            }
            return true
        } else {
            return false
        }
    }

    /**
     * Store a bitmap to file
     * @param bitmap Bitmap to store.
     *
     * @return bitmap path.
     */
    private fun storeBitmap(bitmap: Bitmap): String {
        val cw = ContextWrapper(context)
        val directory: File = cw.getDir("imageDir", Context.MODE_PRIVATE)
        val path = File(directory, "last-image.png")
        var fos: FileOutputStream? = null

        try {
            fos = FileOutputStream(path)
            // Use the compress method on the BitMap object to write image to the OutputStream
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                fos?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return directory.absolutePath
    }

    interface View {
        fun updateBackground(bitmap: Bitmap)
        fun getScreenSize(): Int
    }
}