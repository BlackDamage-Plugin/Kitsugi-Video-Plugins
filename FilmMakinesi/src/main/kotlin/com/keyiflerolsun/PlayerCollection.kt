package com.keyiflerolsun

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Deliver each successful result immediately, without waiting for slower siblings. */
internal suspend fun <T, R : Any> collectPlayerResults(
    inputs: List<T>, timeoutMs: Long, resolve: suspend (T) -> R?, emit: (R) -> Unit
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
                    }
                }
            }
        }
    }
    return found
}

/** Keep the synchronous JS interpreter off the coroutine dispatcher handling network completions. */
internal suspend fun <T> runPlayerDecoding(decode: () -> T): T =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { decode() }
