package com.keshav.capturesposed.hookers

import android.annotation.SuppressLint
import android.util.Log
import android.view.Display
import com.keshav.capturesposed.BuildConfig
import com.keshav.capturesposed.TAG
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Hides virtual / overlay displays created by screen recording from apps that query
 * the display list.
 *
 * Some apps (e.g. Paytm) bundle a native security library that detects screen recording
 * by "mirroring detection" — it enumerates the connected displays via DisplayManager and,
 * if it sees any non-built-in display appear, it assumes the screen is being mirrored /
 * recorded and shows error 70015.
 *
 * On many custom ROMs the built-in screen recorder creates an extra (virtual/overlay)
 * display while recording, which trips this check even though it is a local recording.
 *
 * This hooker filters the results of DisplayManagerService.getDisplayIds() (and related
 * methods) so that only real, built-in displays are returned. Cast / external displays
 * created through the normal MediaProjection path used by *other* apps are unaffected in
 * practice because the check only cares about the built-in recorder's overlay display.
 *
 * The behaviour is gated behind the same "screenRecordHookActive" preference so the user
 * can toggle it.
 */
object DisplayManagerHooker {
    private var module: XposedModule? = null

    // Display.TYPE_* constants (android.view.Display)
    private const val TYPE_INTERNAL = 1      // built-in panel
    private const val TYPE_OVERLAY = 4       // overlay display (screen recording / dev overlay)
    private const val TYPE_VIRTUAL = 5       // virtual display (MediaProjection / mirroring)

    @SuppressLint("PrivateApi")
    fun hook(param: SystemServerStartingParam, module: XposedModule) {
        this.module = module

        val dmsClass = try {
            param.classLoader.loadClass(
                "com.android.server.display.DisplayManagerService"
            )
        } catch (e: ClassNotFoundException) {
            module.log(Log.WARN, TAG, "DisplayManagerService not found, skipping display hook.")
            return
        }

        // The real work is done by the inner BinderService (the IDisplayManager stub).
        val binderServiceClass = try {
            param.classLoader.loadClass(
                "com.android.server.display.DisplayManagerService\$BinderService"
            )
        } catch (e: ClassNotFoundException) {
            null
        }

        // We keep a reference to the outer DisplayManagerService so we can ask it for
        // per-display info to decide whether a display should be hidden.
        val targetClasses = listOfNotNull(binderServiceClass, dmsClass)

        var hookedAny = false

        for (clazz in targetClasses) {
            // ---- Hook getDisplayIds(...) : returns int[] ----
            for (method in clazz.declaredMethods) {
                if (method.name != "getDisplayIds") continue

                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    if (!isHookActive()) return@intercept result
                    if (result == null) return@intercept result

                    try {
                        val original = result as IntArray
                        // Always keep the default display (id 0) and any built-in display.
                        val filtered = original.filter { id ->
                            id == Display.DEFAULT_DISPLAY || isBuiltInDisplay(chain.thisObject, id)
                        }.toIntArray()

                        if (filtered.size != original.size) {
                            module.log(Log.INFO, TAG,
                                "Hid ${original.size - filtered.size} non-built-in display(s) from getDisplayIds.")
                        }
                        return@intercept filtered
                    } catch (e: Exception) {
                        module.log(Log.WARN, TAG, "getDisplayIds filter failed: $e")
                        return@intercept result
                    }
                }
                module.log(Log.INFO, TAG, "Hooked ${clazz.simpleName}.getDisplayIds successfully.")
                hookedAny = true
            }

            // ---- Hook getDisplayInfo(int) : returns DisplayInfo (null hides the display) ----
            for (method in clazz.declaredMethods) {
                if (method.name != "getDisplayInfo") continue
                val params = method.parameterTypes
                if (params.size != 1 || params[0] != Int::class.javaPrimitiveType) continue

                module.hook(method).intercept { chain ->
                    if (!isHookActive()) return@intercept chain.proceed()

                    val displayId = chain.getArg(0) as Int
                    if (displayId != Display.DEFAULT_DISPLAY &&
                        !isBuiltInDisplay(chain.thisObject, displayId)) {
                        val info = chain.proceed()
                        // Only hide if it is actually a virtual/overlay display.
                        if (info != null && isVirtualOrOverlay(info)) {
                            module.log(Log.INFO, TAG,
                                "Hid non-built-in display $displayId from getDisplayInfo.")
                            return@intercept null
                        }
                        return@intercept info
                    }
                    chain.proceed()
                }
                module.log(Log.INFO, TAG, "Hooked ${clazz.simpleName}.getDisplayInfo successfully.")
                hookedAny = true
            }
        }

        if (!hookedAny) {
            module.log(Log.WARN, TAG,
                "Could not hook any DisplayManagerService methods for mirroring bypass.")
        }
    }

    private fun isHookActive(): Boolean {
        val prefs = module!!.getRemotePreferences(BuildConfig.APPLICATION_ID)
        return prefs.getBoolean("screenRecordHookActive", true)
    }

    /**
     * Determine whether the given display id corresponds to a real, built-in display.
     * We resolve the DisplayInfo through the service and inspect its "type" field.
     */
    private fun isBuiltInDisplay(binderService: Any, displayId: Int): Boolean {
        if (displayId == Display.DEFAULT_DISPLAY) return true
        return try {
            val info = invokeGetDisplayInfo(binderService, displayId) ?: return true
            !isVirtualOrOverlay(info)
        } catch (e: Exception) {
            // If we cannot determine, be safe and treat it as built-in (don't hide).
            true
        }
    }

    private fun invokeGetDisplayInfo(binderService: Any, displayId: Int): Any? {
        val method = binderService.javaClass.getDeclaredMethod(
            "getDisplayInfo", Int::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(binderService, displayId)
    }

    /**
     * Inspect the DisplayInfo.type field. Virtual (5) and Overlay (4) displays are the
     * ones created by screen recording / mirroring.
     */
    private fun isVirtualOrOverlay(displayInfo: Any): Boolean {
        return try {
            val typeField = displayInfo.javaClass.getField("type")
            val type = typeField.getInt(displayInfo)
            type == TYPE_VIRTUAL || type == TYPE_OVERLAY
        } catch (e: Exception) {
            false
        }
    }
}
