package com.cinemaplus.rebuilt.data

import kotlinx.coroutines.delay
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A non-successful HTTP answer from a source, carrying an Arabic message that fits the status code. */
class SourceHttpException(val code: Int, source: String = "المصدر") : IOException(NetworkErrors.http(code, source))

/** Turns network failures into short Arabic messages and retries brief hiccups once. */
object NetworkErrors {
    fun http(code: Int, source: String = "المصدر"): String = when {
        code == 401 || code == 403 -> "رفض $source الطلب ($code). سجّل الدخول من الإعدادات إن كان العمل يتطلب حسابًا."
        code == 404 -> "لم يجد $source المطلوب ($code)."
        code == 429 -> "$source مشغول بطلبات كثيرة ($code). انتظر قليلًا ثم أعد المحاولة."
        code >= 500 -> "$source لا يستجيب حاليًا ($code). أعد المحاولة بعد قليل."
        else -> "تعذّر الوصول إلى $source ($code)."
    }

    /** Only transient failures are worth a second attempt; DNS, TLS and client errors would just fail again. */
    fun shouldRetry(e: Throwable): Boolean = when (e) {
        is SourceHttpException -> e.code >= 500
        is UnknownHostException, is SSLException -> false
        is InterruptedIOException, is ConnectException, is SocketException, is EOFException -> true
        else -> false
    }

    /** Runs an idempotent request, trying once more after a short pause when the failure looks transient. */
    suspend fun <T> retryOnce(block: suspend () -> T): T = try {
        block()
    } catch (e: IOException) {
        if (!shouldRetry(e)) throw e
        delay(500)
        block()
    }

    private fun hasArabic(text: String?) = text != null && text.any { it in '؀'..'ۿ' }

    /** Messages the app wrote itself (Arabic) pass through; raw library text is replaced by [fallback]. */
    fun message(e: Throwable, fallback: String): String = when (e) {
        is SourceHttpException -> e.message ?: fallback
        is UnknownHostException -> "تعذّر الوصول إلى الخادم. تحقق من اتصال الإنترنت أو الشبكة المحلية."
        is SSLException -> "تعذّر إنشاء اتصال آمن. تأكد من تاريخ ووقت الجهاز."
        is InterruptedIOException -> "انتهت مهلة الاتصال. أعد المحاولة."
        is SocketException -> "انقطع الاتصال بالخادم. أعد المحاولة."
        else -> e.message?.takeIf(::hasArabic) ?: fallback
    }
}
