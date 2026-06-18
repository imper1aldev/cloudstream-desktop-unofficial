package com.lagradost.cloudstream3.desktop.player

import com.lagradost.common.logging.AppLogger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

interface MpvLibrary : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(handle: Pointer): Int
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_command_string(ctx: Pointer, args: String): Int
    fun mpv_observe_property(ctx: Pointer, reply_userdata: Long, name: String, format: Int): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer?
    fun mpv_free(data: Pointer)
    fun mpv_terminate_destroy(handle: Pointer)

    @Structure.FieldOrder("event_id", "error", "reply_userdata", "data")
    open class MpvEvent(p: Pointer? = null) : Structure(p) {
        @JvmField var event_id: Int = 0
        @JvmField var error: Int = 0
        @JvmField var reply_userdata: Long = 0
        @JvmField var data: Pointer? = null
        init {
            p?.let { read() }
        }
    }

    @Structure.FieldOrder("name", "format", "data")
    open class MpvEventProperty(p: Pointer? = null) : Structure(p) {
        @JvmField var name: String? = null
        @JvmField var format: Int = 0
        @JvmField var data: Pointer? = null
        init {
            p?.let { read() }
        }
    }

    companion object {
        fun getPropertyString(ctx: Pointer, name: String): String? {
            val ptr = INSTANCE.mpv_get_property_string(ctx, name) ?: return null
            val str = ptr.getString(0)
            INSTANCE.mpv_free(ptr)
            return str
        }

        val INSTANCE: MpvLibrary by lazy {
            val targets = listOf("libmpv-2", "mpv-2", "mpv-1", "mpv", "libmpv", "libmpv.so.1", "libmpv.so.2", "mpv-3.dll")
            var loaded: MpvLibrary? = null
            for (target in targets) {
                try {
                    loaded = Native.load(target, MpvLibrary::class.java) as MpvLibrary
                    AppLogger.i("Successfully loaded native mpv library: $target")
                    break
                } catch (e: UnsatisfiedLinkError) {
                    // Try next
                } catch (e: IllegalArgumentException) {
                    // Try next
                }
            }
            loaded ?: throw RuntimeException("Failed to load native MPV library. Please ensure mpv is installed and in your system PATH.")
        }
    }
}
