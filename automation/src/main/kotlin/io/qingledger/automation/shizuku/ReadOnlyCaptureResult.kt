package io.qingledger.automation.shizuku

import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable

/** Typed AIDL return so the descriptor receives RETURN_VALUE and closes at the sending end. */
class ReadOnlyCaptureResult(val metadata: Bundle, val image: ParcelFileDescriptor?) : Parcelable {
    override fun describeContents(): Int = if (image == null) 0 else Parcelable.CONTENTS_FILE_DESCRIPTOR

    override fun writeToParcel(destination: Parcel, flags: Int) {
        destination.writeBundle(metadata)
        // Bundle.writeToParcel does not propagate its flags to nested parcelables.
        destination.writeTypedObject(image, flags)
    }

    companion object {
        @JvmField val CREATOR: Parcelable.Creator<ReadOnlyCaptureResult> = object : Parcelable.Creator<ReadOnlyCaptureResult> {
            override fun createFromParcel(source: Parcel) = ReadOnlyCaptureResult(
                source.readBundle(Bundle::class.java.classLoader) ?: Bundle(),
                source.readTypedObject(ParcelFileDescriptor.CREATOR),
            )
            override fun newArray(size: Int): Array<ReadOnlyCaptureResult?> = arrayOfNulls(size)
        }
    }
}
