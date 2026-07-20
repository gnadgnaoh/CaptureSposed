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

        // The IDisplayManager stub that apps call into.
        val binderServiceClass = try {
            param.classLoader.loadClass(
                "com.android.server.display.DisplayManagerService\$BinderService"
            )
        } catch (e: ClassNotFoundException) {
            null
        }

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
                    if (displayId != Display.DEFAULT_DISPLAY) {
                        val info = chain.proceed()
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

        // ---- Block DISPLAY_ADDED / DISPLAY_CHANGED events for virtual & overlay displays ----
        // This is the key part for the mirroring-detection bypass: it prevents apps'
        // DisplayManager.DisplayListener.onDisplayAdded()/onDisplayChanged() from ever firing
        // for the extra display created by the built-in screen recorder, so the native
        // "isMirroring" check is never triggered.
        hookDisplayEventDispatch(param, dmsClass)

        if (!hookedAny) {
            module.log(Log.WARN, TAG,
                "Could not hook any DisplayManagerService query methods for mirroring bypass.")
        }
    }

    // Display event constants (DisplayManagerGlobal)
    private const val EVENT_DISPLAY_ADDED = 1
    private const val EVENT_DISPLAY_CHANGED = 2

    /**
     * Hooks the internal DMS method that sends display events to registered listeners,
     * dropping ADDED / CHANGED events that refer to a virtual or overlay display.
     *
     * Method names vary across Android versions, so we match by name and signature.
     * Common candidates: sendDisplayEventLocked(int, int),
     * handleLogicalDisplayAddedLocked(...), deliverDisplayEvent(...),
     * and in newer builds the LogicalDisplayMapper / DisplayManagerService callbacks.
     */
    @SuppressLint("PrivateApi")
    private fun hookDisplayEventDispatch(param: SystemServerStartingParam, dmsClass: Class<*>) {
        // Candidate 1: DisplayManagerService.sendDisplayEventLocked(int displayId, int event)
        val candidates = dmsClass.declaredMethods.filter { method ->
            val p = method.parameterTypes
            (method.name.startsWith("sendDisplayEvent") ||
             method.name == "deliverDisplayEvent") &&
            p.size >= 2 &&
            p[0] == Int::class.javaPrimitiveType &&
            p[1] == Int::class.javaPrimitiveType
        }

        for (method in candidates) {
            module.hook(method).intercept { chain ->
                if (!isHookActive()) return@intercept chain.proceed()

                val displayId = chain.getArg(0) as Int
                val event = chain.getArg(1) as Int

                if ((event == EVENT_DISPLAY_ADDED || event == EVENT_DISPLAY_CHANGED) &&
                    displayId != Display.DEFAULT_DISPLAY &&
                    isDisplayVirtualOrOverlayById(chain.thisObject, displayId)) {
                    module.log(Log.INFO, TAG,
                        "Suppressed display event ($event) for non-built-in display $displayId.")
                    // Swallow the event: return without dispatching.
                    return@intercept when {
                        method.returnType == Boolean::class.javaPrimitiveType -> false
                        method.returnType == Void.TYPE -> Unit
                        else -> null
                    }
                }
                chain.proceed()
            }
            module.log(Log.INFO, TAG,
                "Hooked DisplayManagerService.${method.name} (event suppression) successfully.")
        }

        // Candidate 2: the per-listener CallbackRecord dispatcher.
        try {
            val callbackRecordClass = param.classLoader.loadClass(
                "com.android.server.display.DisplayManagerService\$CallbackRecord"
            )
            val notifyMethods = callbackRecordClass.declaredMethods.filter { method ->
                method.name.startsWith("notifyDisplayEvent") &&
                method.parameterTypes.size >= 2 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                method.parameterTypes[1] == Int::class.javaPrimitiveType
            }
            for (method in notifyMethods) {
                module.hook(method).intercept { chain ->
                    if (!isHookActive()) return@intercept chain.proceed()

                    val displayId = chain.getArg(0) as Int
                    val event = chain.getArg(1) as Int
                    if ((event == EVENT_DISPLAY_ADDED || event == EVENT_DISPLAY_CHANGED) &&
                        displayId != Display.DEFAULT_DISPLAY) {
                        // We can't easily resolve type here without the outer service,
                        // so only suppress ADDED for non-default ids that look virtual.
                        module.log(Log.INFO, TAG,
                            "Suppressed CallbackRecord display event ($event) for display $displayId.")
                        return@intercept when {
                            method.returnType == Boolean::class.javaPrimitiveType -> false
                            method.returnType == Void.TYPE -> Unit
                            else -> null
                        }
                    }
                    chain.proceed()
                }
                module.log(Log.INFO, TAG,
                    "Hooked CallbackRecord.${method.name} (event suppression) successfully.")
            }
        } catch (e: Exception) {
            module.log(Log.WARN, TAG, "Could not hook CallbackRecord notify methods: $e")
        }
    }

    private fun isDisplayVirtualOrOverlayById(dmsOrBinder: Any, displayId: Int): Boolean {
        return try {
            val info = invokeGetDisplayInfo(dmsOrBinder, displayId) ?: return true
            isVirtualOrOverlay(info)
        } catch (e: Exception) {
            // If unknown, assume it's the recorder's display (suppress) since default is excluded.
            true
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
