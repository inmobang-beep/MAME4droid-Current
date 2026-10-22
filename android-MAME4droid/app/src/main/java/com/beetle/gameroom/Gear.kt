package com.beetle.gameroom

import android.content.Context
import android.util.Log
import dalvik.system.InMemoryDexClassLoader
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.nio.ByteBuffer
import java.util.zip.ZipFile

/**
 * The gear, for the release build's rule «games only in P» ([Gear.LOCK]).
 *
 * Read the way Beetle Launcher reads it: BYD's vehicle SDK is not on the boot
 * classpath of the Sealion 7, so its dex is loaded in memory from the installed
 * com.byd.data.collect apk (with a process-local hidden API exemption for the
 * adapter layer it calls into), then BodyworkManagerImpl.getShiftMode()
 * (0/1 = P, 2 = R, 3 = N, 4-6 = D) or, failing that,
 * BYDAutoGearboxDevice.getGearboxAutoModeType() (1 = P, 2 = R, 3 = N, 4-6 = D).
 *
 * On a machine without the SDK (the Mac emulator) `setprop debug.beetle.gear P|D`
 * stands in for the car.
 */
object Gear {
    private const val TAG = "BeetleGameRoom"
    private const val OEM_PKG = "com.byd.data.collect"
    private const val BODY = "android.hardware.bydauto.bodywork.tsimpl.BodyworkManagerImpl"
    private const val GEARBOX = "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice"
    private const val DEBUG_PROP = "debug.beetle.gear"

    /** Release build: games only in P. The test build (-PtestBuild) plays in any gear. */
    val LOCK: Boolean get() = com.seleuco.mame4droid.BuildConfig.GEAR_LOCK

    enum class State { PARK, DRIVE, UNKNOWN }

    @Volatile private var loader: ClassLoader? = null
    @Volatile private var nextTry = 0L
    private val devices = HashMap<String, Any?>()

    /** May block on a binder call; fine from a background thread, quick enough for a tap. */
    @Synchronized
    fun state(ctx: Context): State {
        prop(DEBUG_PROP).uppercase().takeIf { it.isNotEmpty() }?.let { return if (it == "P") State.PARK else State.DRIVE }
        val shift = call(ctx, BODY, "getShiftMode")?.takeIf { it in 0..6 }
        if (shift != null) return if (shift <= 1) State.PARK else State.DRIVE
        val auto = call(ctx, GEARBOX, "getGearboxAutoModeType")?.takeIf { it in 1..6 }
        if (auto != null) return if (auto == 1) State.PARK else State.DRIVE
        return State.UNKNOWN
    }

    /** Why a game may not run now, or null when it may. */
    fun blocked(ctx: Context): String? {
        if (!LOCK) return null
        return when (state(ctx)) {
            State.PARK -> null
            State.DRIVE -> "기어를 P 에 두어야 게임을 할 수 있습니다"
            State.UNKNOWN -> "기어를 읽지 못해 게임을 할 수 없습니다"
        }
    }

    private fun call(ctx: Context, cls: String, method: String): Int? {
        val dev = device(ctx, cls) ?: return null
        return try {
            (dev.javaClass.getMethod(method).invoke(dev) as? Number)?.toInt()
        } catch (t: Throwable) { Log.w(TAG, "gear: $cls.$method: $t"); null }
    }

    private fun device(ctx: Context, cls: String): Any? {
        devices[cls]?.let { return it }
        val l = loader(ctx) ?: return null
        val dev = try {
            val c = Class.forName(cls, true, l)
            // the factory is getInstance on some classes and getsInstance on others
            val m = try { c.getMethod("getInstance", Context::class.java) }
                    catch (e: NoSuchMethodException) { c.getMethod("getsInstance", Context::class.java) }
            m.invoke(null, ctx.applicationContext)
        } catch (t: Throwable) { Log.w(TAG, "gear: $cls: $t"); null }
        if (dev != null) devices[cls] = dev
        return dev
    }

    private fun loader(ctx: Context): ClassLoader? {
        loader?.let { return it }
        val now = System.currentTimeMillis()
        if (now < nextTry) return null
        nextTry = now + 10_000L // the car's services may still be starting
        runCatching { HiddenApiBypass.addHiddenApiExemptions("Lcom/ts/", "Lcom/byd/", "Landroid/hardware/bydauto/") }
            .onFailure { Log.w(TAG, "gear: hidden API exemption: $it") }
        val own = ctx.classLoader
        if (runCatching { Class.forName(GEARBOX, false, own) }.isSuccess) return own.also { loader = it }
        return try {
            val ai = ctx.packageManager.getApplicationInfo(OEM_PKG, 0)
            val paths = listOfNotNull(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
            val dex = paths.flatMap { p ->
                ZipFile(p).use { z ->
                    z.entries().asSequence().filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                        .map { e -> ByteBuffer.wrap(z.getInputStream(e).use { it.readBytes() }) }.toList()
                }
            }
            if (dex.isEmpty()) null else InMemoryDexClassLoader(dex.toTypedArray(), own).also {
                loader = it
                Log.w(TAG, "gear: vehicle SDK from $OEM_PKG (${dex.size} dex)")
            }
        } catch (t: Throwable) { Log.w(TAG, "gear: no vehicle SDK: $t"); null }
    }

    private fun prop(key: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
            .invoke(null, key, "") as String
    } catch (t: Throwable) { "" }
}
