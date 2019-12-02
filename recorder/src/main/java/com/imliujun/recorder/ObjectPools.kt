package com.imliujun.recorder

import androidx.core.util.Pools
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * 项目名称：rxAudioRecorder
 * 类描述：
 * 创建人：liujun
 * 创建时间：2019-11-25 18:41
 * 修改人：liujun
 * 修改时间：2019-11-25 18:41
 * 修改备注：
 * @version
 */
object ObjectPools {

    private val directByteMap = ConcurrentHashMap<Int, Pools.SynchronizedPool<ByteBuffer>>()

    private val byteBufferMap = ConcurrentHashMap<Int, Pools.SynchronizedPool<ByteBuffer>>()

    private val directShortMap = ConcurrentHashMap<Int, Pools.SynchronizedPool<ShortBuffer>>()

    private val shortBufferMap = ConcurrentHashMap<Int, Pools.SynchronizedPool<ShortBuffer>>()

    private val shortPoolMap = ConcurrentHashMap<Int, Pools.SynchronizedPool<ShortArray>>()

    private val bytePoolMap = ConcurrentHashMap<Int, Pools.SynchronizedPool<ByteArray>>()


    fun getShortBuffer(capacity: Int): ShortBuffer {
        val pool = shortBufferMap[capacity]
        return if (pool == null) {
            shortBufferMap[capacity] = Pools.SynchronizedPool(10)
            ShortBuffer.allocate(capacity)
        } else {
            pool.acquire()?.clear() as? ShortBuffer ?: ShortBuffer.allocate(capacity)
        }
    }

    /**
     * 获取 Java 和 Jni 层共享内存的 ShortBuffer
     *
     */
    fun getDirectShortBuffer(capacity: Int): ShortBuffer {
        val size = capacity * 2
        val pool = directShortMap[size]
        return if (pool == null) {
            directShortMap[size] = Pools.SynchronizedPool(10)
            ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        } else {
            pool.acquire()?.clear() as? ShortBuffer
                ?: ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        }
    }

    fun release(buffer: ShortBuffer) {
        try {
            buffer.clear()
            if (buffer.isDirect) {
                directShortMap[buffer.capacity()]?.release(buffer)
            } else {
                shortBufferMap[buffer.capacity()]?.release(buffer)
            }
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    /**
     * 获取 Java 和 Jni 层共享内存的 ByteBuffer
     */
    fun getDirectByteBuffer(capacity: Int): ByteBuffer {
        var pool = directByteMap[capacity]
        return if (pool == null) {
            pool = Pools.SynchronizedPool(10)
            directByteMap[capacity] = pool
            ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN)
        } else {
            pool.acquire()?.clear() as? ByteBuffer
                ?: ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    fun getByteBuffer(capacity: Int): ByteBuffer {
        var pool = byteBufferMap[capacity]
        return if (pool == null) {
            pool = Pools.SynchronizedPool(10)
            byteBufferMap[capacity] = pool
            ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)
        } else {
            pool.acquire()?.clear() as? ByteBuffer
                ?: ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    fun release(buffer: ByteBuffer) {
        try {
            buffer.clear()
            if (buffer.isDirect) {
                val pool = directByteMap[buffer.capacity()] ?: return
                pool.release(buffer)
            } else {
                val pool = byteBufferMap[buffer.capacity()] ?: return
                pool.release(buffer)
            }
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    fun getShortArray(capacity: Int): ShortArray {
        var pool = shortPoolMap[capacity]
        return if (pool == null) {
            pool = Pools.SynchronizedPool(10)
            shortPoolMap[capacity] = pool
            ShortArray(capacity)
        } else {
            pool.acquire() ?: ShortArray(capacity)
        }
    }

    fun release(shorts: ShortArray) {
        val pool = shortPoolMap[shorts.size] ?: return
        try {
            pool.release(shorts)
        } catch (e: Exception) {
            Timber.e(e)
        }

    }

    fun getByteArray(capacity: Int): ByteArray {
        var pool = bytePoolMap[capacity]
        return if (pool == null) {
            pool = Pools.SynchronizedPool(10)
            bytePoolMap[capacity] = pool
            ByteArray(capacity)
        } else {
            pool.acquire() ?: ByteArray(capacity)
        }
    }

    fun release(byteArray: ByteArray) {
        val pool = bytePoolMap[byteArray.size] ?: return
        try {
            pool.release(byteArray)
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    fun clearAll() {
        clearMap(directByteMap)
        clearMap(directShortMap)
        clearMap(shortBufferMap)
        clearMap(shortPoolMap)
        clearMap(bytePoolMap)
    }

    private fun <T> clearMap(map: ConcurrentHashMap<Int, Pools.SynchronizedPool<T>>) {
        for (pool in map.values) {
            while (pool.acquire() != null) {
            }
        }
        map.clear()
    }
}
