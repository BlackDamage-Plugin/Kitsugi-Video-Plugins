package com.keyiflerolsun

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Deliver each successful result immediately, without waiting for slower siblings. */
internal suspend fun <T, R : Any> collectPlayerResults(
    inputs: List<T>, timeoutMs: Long, resolve: suspend (T) -> R?, emit: (R) -> Unit,
    onMiss: ((T) -> Unit)? = null
): Boolean {
    val lock = Mutex()
    var found = false
    withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            inputs.forEach { input ->
                launch {
                    val result = try {
                        resolve(input)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (result != null) lock.withLock {
                        emit(result)
                        found = true
                    } else {
                        onMiss?.invoke(input)
                    }
                }
            }
        }
    }
    return found
}

/** Share one in-flight request for the same embed; signed stream URLs can differ per fetch. */
internal class SingleFlight<K, V : Any> {
    private val lock = Mutex()
    private val requests = HashMap<K, kotlinx.coroutines.CompletableDeferred<V?>>()

    suspend fun get(key: K, load: suspend () -> V?): V? {
        var owner = false
        val pending = lock.withLock {
            requests[key] ?: kotlinx.coroutines.CompletableDeferred<V?>().also {
                requests[key] = it
                owner = true
            }
        }
        if (owner) {
            try {
                pending.complete(load())
            } catch (e: CancellationException) {
                pending.complete(null)
                throw e
            } catch (e: Exception) {
                pending.complete(null)
            }
        }
        return pending.await()
    }
}

/** Keep the synchronous JS interpreter off the coroutine dispatcher handling network completions. */
internal suspend fun <T> runPlayerDecoding(decode: () -> T): T =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { decode() }
