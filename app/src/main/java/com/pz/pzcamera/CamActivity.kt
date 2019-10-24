package com.pz.pzcamera

import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.media.MediaScannerConnection
import android.os.Bundle
import android.view.View
import android.view.View.OnClickListener
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.otaliastudios.cameraview.*
import com.otaliastudios.cameraview.controls.Facing
import com.otaliastudios.cameraview.controls.Mode
import com.otaliastudios.cameraview.controls.Preview
import com.otaliastudios.cameraview.filter.Filters
import com.otaliastudios.cameraview.frame.Frame
import com.otaliastudios.cameraview.frame.FrameProcessor
import com.pz.pzcamera.R.id
import com.pz.pzcamera.R.layout
import com.pz.pzcamera.options.Option
import com.pz.pzcamera.options.OptionView.Callback
import com.pz.pzcamera.ui.EXTENSION_WHITELIST
import com.pz.pzcamera.ui.PicturePreviewActivity
import com.pz.pzcamera.ui.VideoPreviewActivity
import kotlinx.android.synthetic.main.activity_camera.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
class CamActivity : AppCompatActivity(), OnClickListener, Callback {

    private var mCaptureTime: Long = 0
    private var mCurrentFilter = 0
    private val mAllFilters: Array<Filters> = Filters.values()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(layout.activity_camera)
        CameraLogger.setLogLevel(CameraLogger.LEVEL_VERBOSE)

        camera!!.setLifecycleOwner(this)

        camera!!.addCameraListener(Listener())

        // Set listeners
        capturePicture!!.setOnClickListener(this)
        toggleVideo!!.setOnClickListener(this)
        toggleCamera!!.setOnClickListener(this)
        changeFilter!!.setOnClickListener(this)
        thumbnail!!.setOnClickListener(this)
        flashButton!!.setOnClickListener(this)
        focusButton!!.setOnClickListener(this)
        exposureButton!!.setOnClickListener(this)
        wbButton!!.setOnClickListener(this)
        aiButton!!.setOnClickListener(this)
        settingsButton!!.setOnClickListener(this)

        // Animate the watermark just to show we record the animation in video snapshots
        val animator: ValueAnimator? = ValueAnimator.ofFloat(1f, 0.8f)
        animator!!.duration = 300
        animator.repeatCount = ValueAnimator.INFINITE
        animator.repeatMode = ValueAnimator.REVERSE
        animator.addUpdateListener { animation ->
            val scale = animation!!.animatedValue as Float
            watermark.scaleX = scale
            watermark.scaleY = scale
            watermark.rotation = watermark.rotation + 2
        }
        animator.start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun hideSystemUI() {
        // Enables regular immersive mode.
        // For "lean back" mode, remove SYSTEM_UI_FLAG_IMMERSIVE.
        // Or for "sticky immersive," replace it with SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                // Set the content to appear under the system bars so that the
                // content doesn't resize when the system bars hide and show.
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                // Hide the nav bar and status bar
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
    }

    private fun message(content: String, important: Boolean) {
        if (important) {
            LOG!!.w(content)
            Toast.makeText(this, content, Toast.LENGTH_LONG).show()
        } else {
            LOG!!.i(content)
            Toast.makeText(this, content, Toast.LENGTH_SHORT).show()
        }
    }

    private inner class Listener : CameraListener() {

        override fun onCameraOpened(options: CameraOptions) {

            // In the background, load latest photo taken (if any) for gallery thumbnail
            lifecycleScope.launch(Dispatchers.IO) {
                filesDir.listFiles { file ->
                    EXTENSION_WHITELIST.contains(file.extension.toUpperCase())
                }.sorted().reversed().firstOrNull()?.let { setGalleryThumbnail(it) }
            }

            if (USE_FRAME_PROCESSOR) {
                camera!!.addFrameProcessor(object : FrameProcessor {
                    private var lastTime = System.currentTimeMillis()
                    override fun process(frame: Frame) {
                        val newTime = frame.time
                        val delay = newTime - lastTime
                        lastTime = newTime
                        LOG!!.e("Frame delayMillis:", delay, "FPS:", 1000 / delay)
                        if (DECODE_BITMAP) {
                            val yuvImage = YuvImage(frame.data, ImageFormat.NV21,
                                    frame.size.width,
                                    frame.size.height,
                                    null)

                            val jpegStream = ByteArrayOutputStream()
                            yuvImage.compressToJpeg(Rect(0, 0,
                                    frame.size.width,
                                    frame.size.height), 100, jpegStream)

                            val jpegByteArray: ByteArray? = jpegStream.toByteArray()
                            val bitmap: Bitmap? = BitmapFactory.decodeByteArray(jpegByteArray, 0, jpegByteArray!!.size)
                            bitmap.toString()
                        }
                    }
                })
            }
        }

        private fun setGalleryThumbnail(file: File) {
            // Run the operations in the view's thread
            thumbnail.post {

                // Remove thumbnail padding
                thumbnail.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())

                // Load thumbnail into circular button using Glide
                Glide.with(thumbnail)
                        .load(file)
                        .apply(RequestOptions.circleCropTransform())
                        .into(thumbnail)
            }
        }

        override fun onCameraError(exception: CameraException) {
            super.onCameraError(exception)
            message("Got CameraException #" + exception.reason, true)
        }

        override fun onPictureTaken(result: PictureResult) {
            super.onPictureTaken(result)
            if (camera!!.isTakingVideo) {
                message("Captured while taking video. Size=" + result.size, false)
                return
            }

            // Create output file to hold the picture
            val photoFile = createFile(filesDir, FILENAME, PHOTO_EXTENSION)

            result.toFile(photoFile, FileCallback { fileError() })

            // Update the gallery thumbnail with latest picture taken
            setGalleryThumbnail(photoFile)

            // If the folder selected is an external media directory, this is unnecessary
            // but otherwise other apps will not be able to access our images unless we
            // scan them using [MediaScannerConnection]
            val mimeType = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(photoFile.extension)
            MediaScannerConnection.scanFile(
                    this@CamActivity, arrayOf(photoFile.absolutePath), arrayOf(mimeType), null)

            // This can happen if picture was taken with a gesture.
            val callbackTime = System.currentTimeMillis()
            if (mCaptureTime == 0L) mCaptureTime = callbackTime - 300

            LOG!!.w("onPictureTaken called! Launching PicturePreviewActivity. Delay:", callbackTime - mCaptureTime)
            PicturePreviewActivity.setPictureResult(result)

            val intent = Intent(this@CamActivity, PicturePreviewActivity::class.java)
            intent.putExtra("delay", callbackTime - mCaptureTime)
            startActivity(intent)

            mCaptureTime = 0
        }

        private fun fileError() {
            LOG!!.e("File Error.TODO????????")
        }

        override fun onVideoTaken(result: VideoResult) {
            super.onVideoTaken(result)
            LOG!!.i("onVideoTaken called! Launching  VideoPreviewActivity.")
            VideoPreviewActivity.setVideoResult(result)
            val intent = Intent(this@CamActivity, VideoPreviewActivity::class.java)
            startActivity(intent)
        }

        override fun onVideoRecordingStart() {
            super.onVideoRecordingStart()
            capturePicture.setImageDrawable(getDrawable(R.drawable.ic_stop_24dp))
            LOG!!.i("onVideoRecordingStart!")
        }

        override fun onVideoRecordingEnd() {
            super.onVideoRecordingEnd()
            capturePicture.setImageDrawable(getDrawable(R.drawable.watermark))
            message("Video taken. Processing...", false)
            LOG!!.i("onVideoRecordingEnd!")
        }

/*
        override fun onExposureCorrectionChanged(newValue: Float, bounds: FloatArray, fingers: Array<PointF?>?) {
            super.onExposureCorrectionChanged(newValue, bounds, fingers)
            message("Exposure correction:$newValue", false)
        }
*/

        override fun onZoomChanged(newValue: Float, bounds: FloatArray, fingers: Array<PointF?>?) {
            super.onZoomChanged(newValue, bounds, fingers)
            message("Zoom:$newValue", false)
        }
    }

    override fun onClick(view: View?) {
        when (view!!.id) {
            id.capturePicture -> capturePicture()
            id.toggleVideo -> toggleVideo()
            id.toggleCamera -> toggleCamera()
            id.changeFilter -> changeCurrentFilter()
            id.flashButton -> changeFlash()
            id.focusButton -> changeFocus()
            id.exposureButton -> changeExposure()
            id.wbButton -> changeWB()
            id.aiButton -> changeAI()
            id.settingsButton -> changeSettings()
        }
    }

    private fun capturePicture() {
        if (camera!!.isTakingPicture) return

        if (camera!!.mode == Mode.VIDEO) {
            if (camera!!.isTakingVideo) {
                //... stop recording. This will trigger onVideoTaken()
                camera.stopVideo()
                return
            }

            // Create output file to hold the video
            val videoFile = createFile(filesDir, FILENAME, VIDEO_EXTENSION)
            message("Recording video.......", true)
            camera!!.takeVideo(videoFile)

        } else {
            // Take picture
            mCaptureTime = System.currentTimeMillis()
            message("Capturing picture...", false)
            camera!!.takePicture()
        }
    }

    private fun toggleVideo() {
        if (camera!!.mode == Mode.PICTURE) {
            message("Changes to VIDEO mode.", false)
            camera!!.mode = Mode.VIDEO
            toggleVideo.setImageDrawable(getDrawable(R.drawable.ic_video))
        } else {
            message("Changes to PICTURE mode.", false)
            camera!!.mode = Mode.PICTURE
            toggleVideo.setImageDrawable(getDrawable(R.drawable.ic_photo))
        }
    }

    private fun toggleCamera() {
        if (camera!!.isTakingPicture || camera!!.isTakingVideo) return
        when (camera!!.toggleFacing()) {
            Facing.BACK -> message("Switched to back camera!", false)
            Facing.FRONT -> message("Switched to front camera!", false)
        }
    }

    private fun changeCurrentFilter() {
        if (camera!!.preview != Preview.GL_SURFACE) {
            message("Filters are supported only when preview is Preview.GL_SURFACE.", true)
            return
        }
        if (mCurrentFilter < mAllFilters.size - 1) {
            mCurrentFilter++
        } else {
            mCurrentFilter = 0
        }
        val filter = mAllFilters[mCurrentFilter]
        camera!!.filter = filter.newInstance()
        message(filter.toString(), false)

        // To test MultiFilter:
        // DuotoneFilter duotone = new DuotoneFilter();
        // duotone.setFirstColor(Color.RED);
        // duotone.setSecondColor(Color.GREEN);
        // camera.setFilter(new MultiFilter(duotone, filter.newInstance()));
    }

    private fun changeSettings() {
        message("Settings", false)
    }

    private fun changeAI() {
        message("AI", false)
    }

    private fun changeWB() {
        message("White balance", false)
    }

    private fun changeExposure() {
        message("Exposure", false)
    }

    private fun changeFocus() {
        message("Focus", false)
    }

    private fun changeFlash() {
        message("Flash", false)
    }

    override fun <T> onValueChanged(option: Option<T?>, value: T, name: String): Boolean {
        option.set(camera!!, value)
        return true
    }

    //region Permissions
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String?>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        var valid = true
        for (grantResult in grantResults) {
            valid = valid && grantResult == PackageManager.PERMISSION_GRANTED
        }
        if (valid && !camera!!.isOpened) {
            camera!!.open()
        }
    }

    companion object {
        private val LOG: CameraLogger? = CameraLogger.create("***pzCamera")
        private const val USE_FRAME_PROCESSOR = false
        private const val DECODE_BITMAP = true
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpg"
        private const val VIDEO_EXTENSION = ".mp4"

        /** Helper function used to create a timestamped file */
        private fun createFile(baseFolder: File, format: String, extension: String) =
                File(baseFolder, SimpleDateFormat(format, Locale.US)
                        .format(System.currentTimeMillis()) + extension)
    }
}

