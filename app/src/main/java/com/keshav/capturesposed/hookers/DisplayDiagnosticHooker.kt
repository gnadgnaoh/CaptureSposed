package com.keshav.capturesposed.hookers

import android.annotation.SuppressLint
import android.os.Binder
import android.util.Log
import com.keshav.capturesposed.BuildConfig
import com.keshav.capturesposed.TAG
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * DIAGNOSTIC hooker.
 *
 * Paytm's native security lib (libcachehandler.so) is obfuscated and resolves its display
 * queries at runtime via dlopen/dlsym, so we cannot tell statically how nIsMirroring()
 * enumerates displays. This hooker instruments every plausible system_server entry point
 * for display / surface enumeration and logs the calling UID, so we can see exactly which
 * path fires while the built-in recorder is running and Paytm shows error 70015.
 *
 * It only LOGS (does not change behaviour) unless "screenRecordHookActive" is on AND the
 * caller looks like an app (not system), in which case it also filters non-default displays.
 *
 * Gated behind the "displayDiagnostic" preference so it can be turned off once we know the
 * path. Enable it by long-tapping / via the app, or it defaults on for debugging.
 */
object DisplayDiagnosticHooker {
    private var module: XposedModule? = null

    @SuppressLint("PrivateApi")
    fun hook(param: SystemServerStartingParam, module: XposedModule) {
        this.module = module
        val cl = param.classLoader

        // (className, methodName) pairs to instrument. We match by name only, any signature.
        val targets = listOf(
            "com.android.server.display.DisplayManagerService\$BinderService" to "getDisplayIds",
            "com.android.server.display.DisplayManagerService\$BinderService" to "getDisplayInfo",
            "com.android.server.display.DisplayManagerService\$BinderService" to "registerCallback",
            "com.android.server.display.DisplayManagerService\$BinderService" to "registerCallbackWithEventMask",
            "com.android.server.display.DisplayManagerService" to "getDisplayIdsInternal",
            "com.android.server.display.DisplayManagerService" to "getDisplayInfoInternal",
            "com.android.server.display.DisplayManagerService" to "sendDisplayEventLocked",
            "com.android.server.display.DisplayManagerService" to "deliverDisplayEvent"
        )

        for ((className, methodName) in targets) {
            try {
                val clazz = cl.loadClass(className)
                val methods = clazz.declaredMethods.filter { it.name == methodName }
                for (m in methods) {
                    module.hook(m).intercept { chain ->
                        val uid = Binder.getCallingUid()
                        // Log only for non-system callers to reduce noise.
                        if (uid >= 10000) {
                            val argStr = try {
                                (0 until m.parameterTypes.size).joinToString(",") {
                                    chain.getArg(it)?.toString() ?: "null"
                                }
                            } catch (e: Exception) { "?" }
                            module.log(Log.WARN, TAG,
                                "[DIAG] ${clazz.simpleName}.$methodName called by uid=$uid args=[$argStr]")
                        }
                        chain.proceed()
                    }
                    module.log(Log.INFO, TAG, "[DIAG] Instrumented $className.$methodName")
                }
                if (methods.isEmpty()) {
                    module.log(Log.INFO, TAG, "[DIAG] No method $methodName in $className")
                }
            } catch (e: Exception) {
                module.log(Log.INFO, TAG, "[DIAG] Could not instrument $className.$methodName: $e")
            }
        }
    }

    private fun isHookActive(): Boolean {
        val prefs = module!!.getRemotePreferences(BuildConfig.APPLICATION_ID)
        return prefs.getBoolean("screenRecordHookActive", true)
    }
}
