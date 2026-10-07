package dev.moduforge.host.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.moduforge.sandbox.DeviceCallException
import dev.moduforge.sandbox.ModuleStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Photos from the device's cameras. A photo is taken with the system camera stack (so Android shows
 * its camera indicator), shrunk to fit the module's storage and written there; the module gets the
 * file name and size, never a handle to the camera.
 */
internal class CameraService(private val context: Context, private val storage: ModuleStorage) {

    private val one = Mutex()

    /** The lenses of this device: `back`, `front`, `external`. */
    fun list(): List<Map<String, Any?>> {
        val manager = context.getSystemService(CameraManager::class.java)
        return manager.cameraIdList.mapNotNull { id ->
            val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            val lens = when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                else -> null
            }
            lens?.let { mapOf("lens" to it, "id" to id) }
        }
    }

    /**
     * Takes a photo and stores it as [path] in the module's storage.
     *
     * @param size longest side in pixels, at most [MAX_SIZE].
     * @param quality JPEG quality 20..95; lowered further when the file would not fit the storage limit.
     */
    suspend fun photo(moduleId: String, path: String, lens: String, size: Int, quality: Int, flash: String): Map<String, Any?> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw DeviceCallException("Android has not allowed ModuForge to use the camera: open Settings → Device control and allow it")
        }
        val selector = when (lens) {
            "back" -> CameraSelector.DEFAULT_BACK_CAMERA
            "front" -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> throw DeviceCallException("lens must be \"back\" or \"front\"")
        }
        val flashMode = when (flash) {
            "off" -> ImageCapture.FLASH_MODE_OFF
            "on" -> ImageCapture.FLASH_MODE_ON
            "auto" -> ImageCapture.FLASH_MODE_AUTO
            else -> throw DeviceCallException("flash must be \"off\", \"on\" or \"auto\"")
        }
        return one.withLock {
            val raw = File(context.cacheDir, "photo-${System.nanoTime()}.jpg")
            try {
                capture(selector, flashMode, raw)
                val (jpeg, width, height) = withContext(Dispatchers.Default) { shrink(raw, size.coerceIn(64, MAX_SIZE), quality.coerceIn(20, 95)) }
                try {
                    withContext(Dispatchers.IO) { storage.write(moduleId, path, jpeg) }
                } catch (e: IllegalArgumentException) {
                    throw DeviceCallException(e.message ?: "the photo cannot be stored")
                }
                mapOf("ok" to true, "path" to path, "width" to width, "height" to height, "bytes" to jpeg.size)
            } finally {
                raw.delete()
            }
        }
    }

    private suspend fun capture(selector: CameraSelector, flashMode: Int, target: File) = withContext(Dispatchers.Main) {
        val provider = suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    continuation.resume(future.get())
                } catch (e: Exception) {
                    continuation.resumeWithException(DeviceCallException("the camera is not available: ${e.message}"))
                }
            }, ContextCompat.getMainExecutor(context))
        }
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setFlashMode(flashMode).build()
        try {
            owner.registry.currentState = Lifecycle.State.STARTED
            provider.unbindAll()
            try {
                provider.bindToLifecycle(owner, selector, imageCapture)
            } catch (e: IllegalArgumentException) {
                throw DeviceCallException("this device has no such camera")
            } catch (e: IllegalStateException) {
                throw DeviceCallException("the camera cannot be used right now: ${e.message}")
            }
            suspendCancellableCoroutine { continuation ->
                imageCapture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(target).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) = continuation.resume(Unit)

                        override fun onError(exception: ImageCaptureException) =
                            continuation.resumeWithException(DeviceCallException("the photo failed: ${exception.message}"))
                    },
                )
            }
        } finally {
            provider.unbindAll()
            owner.registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    /** The photo scaled to [maxSide], turned upright, as JPEG that fits a storage file. */
    private fun shrink(file: File, maxSide: Int, quality: Int): Triple<ByteArray, Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw DeviceCallException("the photo could not be read back")
        val rotation = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val scale = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0f) postRotate(rotation)
        }
        val bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        var level = quality
        while (true) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, level, out)
            if (out.size() <= ModuleStorage.MAX_FILE_BYTES || level <= MIN_QUALITY) {
                if (out.size() > ModuleStorage.MAX_FILE_BYTES) throw DeviceCallException("the photo does not fit the storage limit even at the lowest quality: ask for a smaller size")
                return Triple(out.toByteArray(), bitmap.width, bitmap.height)
            }
            level -= QUALITY_STEP
        }
    }

    companion object {
        const val MAX_SIZE = 2048
        const val DEFAULT_SIZE = 1280
        const val DEFAULT_QUALITY = 80
        private const val MIN_QUALITY = 20
        private const val QUALITY_STEP = 10
    }
}
