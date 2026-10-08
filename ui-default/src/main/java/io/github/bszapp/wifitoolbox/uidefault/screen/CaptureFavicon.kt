package io.github.bszapp.wifitoolbox.uidefault.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.IDN
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 仅查询域名；图标成功落盘后跨页面、跨应用重启复用。 */
private object CaptureFavicons {
    private val memory = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val requests = Semaphore(4)
    private val failed = ConcurrentHashMap.newKeySet<String>()
    suspend fun load(context: Context, input: String): Bitmap? = withContext(Dispatchers.IO) {
        val domain = runCatching { IDN.toASCII(input.trim().lowercase()) }.getOrNull() ?: return@withContext null
        if (!domain.contains('.') || domain.length > 253 || domain.all { it.isDigit() || it == '.' } ||
            !domain.matches(Regex("[a-z0-9.-]+"))) return@withContext null
        loadDomain(context, domain)?.let { return@withContext it }
        // 先尝试原始域名，失败后逐级去掉子域；成功图标同时缓存到原域名。
        val labels = domain.split('.')
        for (start in 1..(labels.size - 2)) {
            val parent = labels.drop(start).joinToString(".")
            val bitmap = loadDomain(context, parent) ?: continue
            memory.put(domain, bitmap)
            cacheFile(context, domain).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return@withContext bitmap
        }
        null
    }

    private fun cacheFile(context: Context, domain: String): File {
        val directory = File(context.filesDir, "capture-favicons")
        check(directory.isDirectory || directory.mkdirs())
        val key = MessageDigest.getInstance("SHA-256").digest(domain.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, key)
    }

    private suspend fun loadDomain(context: Context, domain: String): Bitmap? {
        memory.get(domain)?.let { return it }
        return locks.getOrPut(domain) { Mutex() }.withLock {
            memory.get(domain)?.let { return@withLock it }
            val file = cacheFile(context, domain)
            if (file.isFile) BitmapFactory.decodeFile(file.path)?.let { memory.put(domain, it); return@withLock it }
            if (domain in failed) return@withLock null
            requests.withPermit { runCatching {
                val connection = URL("https://a.favicon.im/$domain?larger=true").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5000; connection.readTimeout = 5000
                    check(connection.responseCode == 200)
                    val bytes = connection.inputStream.use { inputStream ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = inputStream.read(buffer)
                            if (count < 0) break
                            check(output.size() + count <= 512 * 1024)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    check(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 128).coerceAtLeast(1)
                    }
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    memory.put(domain, bitmap)
                    bitmap
                } finally { connection.disconnect() }
            }.getOrElse { failed.add(domain); null } }
        }
    }
}

@Composable
internal fun CaptureFavicon(domain: String) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<Bitmap?>(null, domain) { value = CaptureFavicons.load(context, domain) }
    val modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = modifier) }
        ?: Icon(Icons.Rounded.Language, contentDescription = null, modifier = modifier, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}
