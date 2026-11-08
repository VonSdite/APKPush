package dev.tvapkpush.core

import java.io.Closeable
import java.util.concurrent.CancellationException

class JobControl {
    @Volatile var cancelled = false
        private set
    private val resources = mutableSetOf<Closeable>()

    @Synchronized fun attach(resource: Closeable) {
        if (cancelled) {
            runCatching { resource.close() }
            throw CancellationException("Task cancelled")
        }
        resources.add(resource)
    }

    @Synchronized fun detach(resource: Closeable) {
        resources.remove(resource)
    }

    fun check() {
        if (cancelled) throw CancellationException("Task cancelled")
    }

    fun cancel() {
        val active = synchronized(this) {
            cancelled = true
            resources.toList()
        }
        active.forEach { runCatching { it.close() } }
    }

    fun requestCancellation() {
        cancelled = true
    }
}
