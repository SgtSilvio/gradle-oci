package io.github.sgtsilvio.gradle.oci.internal

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * @author Silvio Giebl
 */
internal abstract class Resource<T : Any> {

    private val holder = AtomicReference<Holder<T>>()

    private class Holder<T>(val instance: T) {
        val acquireCount = AtomicLong(1)
    }

    fun acquire(): T {
        while (true) {
            val currentHolder = holder.get()
            if ((currentHolder != null) && (currentHolder.acquireCount.getAndIncrement() > 0)) {
                return currentHolder.instance
            }
            val instance = create()
            if (holder.compareAndSet(null, Holder(instance))) {
                return instance
            } else {
                instance.destroy()
            }
        }
    }

    fun release() {
        val currentHolder = holder.get() ?: throw IllegalStateException()
        val acquireCount = currentHolder.acquireCount
        if ((acquireCount.decrementAndGet() == 0L) && acquireCount.compareAndSet(0, Long.MIN_VALUE)) {
            holder.compareAndSet(currentHolder, null)
            currentHolder.instance.destroy()
        }
    }

    protected abstract fun create(): T

    protected abstract fun T.destroy()
}
