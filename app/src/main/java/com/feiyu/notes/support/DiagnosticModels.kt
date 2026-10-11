package com.feiyu.notes.support

import com.feiyu.notes.ai.AiError
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.Serializable
import java.io.IOException
import java.io.InterruptedIOException

/*
 * Diagnostics schema version 1 (plan 0.3.2 诊断接口). Every field is a fixed enum, a number or a
 * code identifier; no free-form message, URL, path or user text can enter a record.
 */

@Serializable
enum class DiagnosticOperation { APP_START, GENERATE, SUMMARIZE, TITLE, CONNECTION_TEST, IMAGE_IMPORT, NOTE_EXPORT, NOTE_SHARE, UPDATE_CHECK, FEEDBACK_SUBMIT, FEEDBACK_SHARE }

@Serializable
enum class DiagnosticResult { OK, FAILED, CANCELLED }

@Serializable
enum class ErrorKind {
    MISSING_KEY, AUTH, QUOTA, TOO_LARGE, SERVER, NETWORK, TIMEOUT, EMPTY_ANSWER, BAD_RESPONSE, IMAGE_UNREADABLE, IO, INVALID, UNKNOWN;

    companion object {
        /** Maps a failure to its kind and HTTP status. Never reads the exception message. */
        fun classify(e: Throwable): Pair<ErrorKind, Int?> = when (e) {
            is AiError.MissingKey -> MISSING_KEY to null
            is AiError.Auth -> AUTH to e.code
            is AiError.Quota -> QUOTA to e.code
            is AiError.TooLarge -> TOO_LARGE to e.code
            is AiError.Server -> SERVER to e.code
            is AiError.Network -> (if (e.cause is InterruptedIOException) TIMEOUT else NETWORK) to null
            is AiError.EmptyAnswer -> EMPTY_ANSWER to null
            is AiError.BadResponse -> BAD_RESPONSE to null
            is AiError.ImageUnreadable -> IMAGE_UNREADABLE to null
            is TimeoutCancellationException, is InterruptedIOException -> TIMEOUT to null
            is IOException -> IO to null
            else -> UNKNOWN to null
        }
    }
}

@Serializable
data class DiagnosticEvent(
    /** UTC, second precision. */
    val time: String,
    val operation: DiagnosticOperation,
    val result: DiagnosticResult,
    val error: ErrorKind? = null,
    val httpStatus: Int? = null,
    val durationMs: Long? = null,
)

@Serializable
data class AppInfo(val versionName: String, val versionCode: Long)

@Serializable
data class DeviceInfo(val manufacturer: String, val model: String, val androidSdk: Int)

@Serializable
data class FrameRecord(val className: String, val method: String, val file: String? = null, val line: Int? = null)

/** One exception in the cause chain: type and frames only, never its message. */
@Serializable
data class ThrowableRecord(val type: String, val frames: List<FrameRecord>)

@Serializable
data class CrashRecord(val time: String, val chain: List<ThrowableRecord>) {
    companion object {
        const val MAX_CHAIN = 4
        const val MAX_FRAMES = 64
        const val MAX_NAME = 200

        fun from(e: Throwable, time: String, maxFrames: Int = MAX_FRAMES): CrashRecord {
            val chain = mutableListOf<ThrowableRecord>()
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            var budget = maxFrames
            var current: Throwable? = e
            while (current != null && chain.size < MAX_CHAIN && seen.add(current)) {
                val frames = current.stackTrace.take(budget).map {
                    FrameRecord(it.className.take(MAX_NAME), it.methodName.take(MAX_NAME), it.fileName?.substringAfterLast('/')?.take(MAX_NAME), it.lineNumber.takeIf { n -> n >= 0 })
                }
                budget -= frames.size
                chain += ThrowableRecord(current.javaClass.name.take(MAX_NAME), frames)
                current = current.cause
            }
            return CrashRecord(time, chain)
        }
    }
}

@Serializable
data class DiagnosticSnapshot(
    val schemaVersion: Int = 1,
    val app: AppInfo,
    val device: DeviceInfo,
    val events: List<DiagnosticEvent>,
    val crash: CrashRecord?,
    val truncated: Boolean,
)
