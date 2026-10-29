package cn.edu.cppu.libraryautobooker

import java.util.concurrent.atomic.AtomicBoolean

/** One proxy-cookie writer; already-started read probes must drain before authentication. */
internal object WebVpnAuthOwnership {
    private val lock = Any()
    @Volatile private var owner: Any? = null
    private var probes = 0
    val busy get() = owner != null
    fun reserve(candidate: Any): Boolean = synchronized(lock) {
        if (owner != null) false else { owner = candidate; true }
    }
    fun owns(candidate: Any): Boolean = owner === candidate
    fun ready(candidate: Any): Boolean = synchronized(lock) { owner === candidate && probes == 0 }
    fun release(candidate: Any) = synchronized(lock) { if (owner === candidate) owner = null }
    fun beginProbe(): AutoCloseable? = synchronized(lock) {
        if (owner != null) return@synchronized null
        probes++
        val closed = AtomicBoolean()
        AutoCloseable { if (closed.compareAndSet(false, true)) synchronized(lock) { probes-- } }
    }
}
