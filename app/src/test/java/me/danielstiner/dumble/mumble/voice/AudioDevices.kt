package me.danielstiner.dumble.mumble.voice

import android.media.AudioDeviceInfo
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers

/**
 * A device with the id and name a real one carries, which the arrival diff and the menu need.
 * Robolectric's builder gives every device id 0 and no name, so both are set by reflection — the
 * one place an SDK or Robolectric bump can break these tests. An empty name reads as `Build.MODEL`.
 */
internal fun audioDevice(type: Int, id: Int, name: String = ""): AudioDeviceInfo {
    val device = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
    val port = ReflectionHelpers.getField<Any>(device, "mPort")
    ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(port, "mHandle"), "mId", id)
    ReflectionHelpers.setField(Class.forName("android.media.AudioPort"), port, "mName", name)
    return device
}
