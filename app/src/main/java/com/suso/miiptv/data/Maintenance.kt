package com.suso.miiptv.data

import android.content.Context
import android.os.Build
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro de errores: lo último que ha fallado (cierres de la app y fallos al reproducir), para poder enseñarlo. */
object ErrorLog {
    private const val MAX_BYTES = 60_000L
    private var file: File? = null

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "errores.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            add("CIERRE de la app: ${e.javaClass.simpleName}: ${e.message}\n" + e.stackTrace.take(8).joinToString("\n") { "   en $it" })
            previous?.uncaughtException(t, e)
        }
    }

    @Synchronized
    fun add(msg: String) {
        val f = file ?: return
        try {
            val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale("es", "ES")).format(Date())
            f.appendText("[$stamp] $msg\n")
            if (f.length() > MAX_BYTES) {
                val keep = f.readText().takeLast((MAX_BYTES / 2).toInt())
                f.writeText(keep.substringAfter('\n'))
            }
        } catch (e: Exception) {
            // Sin importancia.
        }
    }

    fun read(): String = try {
        file?.takeIf { it.exists() }?.readText().orEmpty()
    } catch (e: Exception) {
        ""
    }

    fun clear() {
        try {
            file?.delete()
        } catch (e: Exception) {
            // Sin importancia.
        }
    }

    /** Datos del aparato, para acompañar al registro. */
    fun device(): String = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
}

/** Copia de seguridad de ajustes, lista, favoritos y perfiles en un archivo de la carpeta Download. */
object Backup {
    const val FILE_NAME = "pepino-iptv-copia.json"

    fun file(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FILE_NAME)

    fun export(ctx: Context): File {
        val sp = ctx.getSharedPreferences("miiptv", Context.MODE_PRIVATE)
        val o = JSONObject()
        for ((k, v) in sp.all) {
            val t = when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is String -> JSONObject().put("t", "s").put("v", v)
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            o.put(k, t)
        }
        val root = JSONObject().put("app", "pepino-iptv").put("fecha", System.currentTimeMillis()).put("datos", o)
        val f = file()
        f.parentFile?.mkdirs()
        f.writeText(root.toString(1))
        return f
    }

    /** Restaura la copia. Lanza una excepción con un mensaje legible si el archivo no vale. */
    fun restore(ctx: Context, f: File = file()) {
        if (!f.exists()) throw IllegalStateException("No hay ninguna copia en la carpeta Download ($FILE_NAME)")
        val root = JSONObject(f.readText())
        if (root.optString("app") != "pepino-iptv") throw IllegalStateException("Ese archivo no es una copia de Pepino IPTV")
        val d = root.getJSONObject("datos")
        val e = ctx.getSharedPreferences("miiptv", Context.MODE_PRIVATE).edit().clear()
        for (k in d.keys()) {
            val o = d.getJSONObject(k)
            when (o.optString("t")) {
                "b" -> e.putBoolean(k, o.getBoolean("v"))
                "i" -> e.putInt(k, o.getInt("v"))
                "l" -> e.putLong(k, o.getLong("v"))
                "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                "s" -> e.putString(k, o.getString("v"))
                "set" -> {
                    val a = o.getJSONArray("v")
                    e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                }
            }
        }
        e.commit()
    }

    fun date(f: File = file()): Long? = try {
        if (f.exists()) JSONObject(f.readText()).optLong("fecha").takeIf { it > 0 } else null
    } catch (e: Exception) {
        null
    }
}

/** Modo ligero: menos animaciones y efectos para aparatos con poca potencia (como la Mi Box). */
object Perf {
    @Volatile
    var light = false
        private set

    fun init(ctx: Context) {
        val mode = ctx.getSharedPreferences("miiptv", Context.MODE_PRIVATE).getString("light_mode", "auto") ?: "auto"
        light = when (mode) {
            "on" -> true
            "off" -> false
            else -> isWeak(ctx)
        }
    }

    /** Aparatos modestos: poca memoria para la app o modelos conocidos (Mi Box). */
    fun isWeak(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val lowRam = am?.isLowRamDevice == true
        val mem = am?.memoryClass ?: 512
        val model = (Build.MODEL ?: "").uppercase()
        return lowRam || mem <= 192 || model.contains("MIBOX") || model.contains("MI BOX")
    }
}

/** Tipo de aparato: tele (mando) o táctil (móvil, tablet). */
object Device {
    /** true en móviles y tablets (pantalla táctil y sin interfaz de tele). */
    @Volatile
    var touch = false
        private set

    fun init(ctx: Context) {
        val pm = ctx.packageManager
        val tv = pm.hasSystemFeature("android.software.leanback") || pm.hasSystemFeature("android.hardware.type.television")
        touch = !tv && pm.hasSystemFeature("android.hardware.touchscreen")
    }
}
