package io.qingledger.automation.shizuku

import android.os.Bundle
import android.os.ParcelFileDescriptor
import io.qingledger.domain.model.ShizukuWindow

internal object ShizukuCaptureProtocol {
    const val OK = "ok"
    const val SKIP = "skip"
    const val UNSUPPORTED = "unsupported"
    fun imageResult(metadata: Bundle, image: ParcelFileDescriptor?) = ReadOnlyCaptureResult(metadata, image)

    fun result(status: String, detail: String? = null, window: ShizukuWindow? = null) = Bundle().apply {
        putString("status", status)
        putString("detail", detail)
        window?.let {
            putString("token", it.token)
            putString("package", it.sourcePackage)
            putString("activity", it.activity)
            putInt("display", it.displayId)
            putBoolean("secure", it.secure)
        }
    }

    fun window(bundle: Bundle): ShizukuWindow? {
        val token = bundle.getString("token") ?: return null
        val source = bundle.getString("package") ?: return null
        val activity = bundle.getString("activity") ?: return null
        val display = bundle.getInt("display", -1)
        if (display < 0) return null
        return ShizukuWindow(token, source, activity, display, bundle.getBoolean("secure", true))
    }
}
