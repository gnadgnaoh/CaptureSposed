package com.keshav.capturesposed.hookers

import android.annotation.SuppressLint
import android.os.IBinder
import android.util.Log
import com.keshav.capturesposed.BuildConfig
import com.keshav.capturesposed.TAG
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Hooks the Android 14+ ScreenCaptureCallback mechanism at the system_server level.
 *
 * When an app calls Activity.registerScreenCaptureCallback(), the callback is registered
 * in ActivityRecord (system_server). When screen capture/recording occurs, the system
 * dispatches onScreenCaptured() to all registered callbacks.
 *
 * Paytm uses this API to detect screen recording and show error 70015
 * ("Screen sharing is not allowed while using this app").
 *
 * This hooker blocks the dispatching so apps never receive the callback.
 */
object ScreenCaptureCallbackHooker {
    private var module: XposedModule? = null

    @SuppressLint("PrivateApi")
    fun hook(param: SystemServerStartingParam, module: XposedModule) {
        this.module = module

        // Strategy 1: Hook ActivityRecord.setScreenCaptureEnabled / reportScreenCaptureInProgress
        // These are the methods called when screen capture state changes for an activity
        try {
            val activityRecordClass = param.classLoader.loadClass(
                "com.android.server.wm.ActivityRecord"
            )

            // Hook all methods related to screen capture dispatching in ActivityRecord
            val screenCaptureMethods = activityRecordClass.declaredMethods.filter { method ->
                method.name.let { name ->
                    name.contains("ScreenCapture", ignoreCase = false) ||
                    name.contains("screenCapture", ignoreCase = false) ||
                    name == "reportScreenCaptured" ||
                    name == "onScreenCaptured" ||
                    name == "dispatchScreenCaptureCallbacks" ||
                    name == "notifyScreenCaptureCallbacks"
                }
            }

            for (method in screenCaptureMethods) {
                module.hook(method).intercept { chain ->
                    val prefs = module.getRemotePreferences(BuildConfig.APPLICATION_ID)
                    val isHookActive = prefs.getBoolean("screenRecordHookActive", true)

                    if (isHookActive) {
                        module.log(Log.INFO, TAG,
                            "Blocked ActivityRecord.${method.name} (screen capture callback dispatch).")
                        return@intercept when {
                            method.returnType == Boolean::class.javaPrimitiveType -> false
                            method.returnType == Void.TYPE -> Unit
                            else -> null
                        }
                    }
                    chain.proceed()
                }
                module.log(Log.INFO, TAG,
                    "Hooked ActivityRecord.${method.name} successfully.")
            }

            if (screenCaptureMethods.isEmpty()) {
                module.log(Log.INFO, TAG,
                    "No ScreenCapture methods found in ActivityRecord, trying alternative approaches.")
            }
        } catch (e: Exception) {
            module.log(Log.WARN, TAG, "Could not hook ActivityRecord screen capture methods: $e")
        }

        // Strategy 2: Hook ActivityTaskManagerService methods for screen capture
        try {
            val atmsClass = param.classLoader.loadClass(
                "com.android.server.wm.ActivityTaskManagerService"
            )

            val screenCaptureMethods = atmsClass.declaredMethods.filter { method ->
                method.name.let { name ->
                    name.contains("ScreenCapture", ignoreCase = false) ||
                    name.contains("screenCapture", ignoreCase = false) ||
                    name == "registerScreenCaptureCallback" ||
                    name == "unregisterScreenCaptureCallback" ||
                    name == "registerScreenCaptureObserver" ||
                    name == "unregisterScreenCaptureObserver"
                }
            }

            for (method in screenCaptureMethods) {
                if (method.name.contains("register", ignoreCase = true) &&
                    !method.name.contains("unregister", ignoreCase = true)) {
                    // Block registration - app never registers so never gets callbacks
                    module.hook(method).intercept { chain ->
                        val prefs = module.getRemotePreferences(BuildConfig.APPLICATION_ID)
                        val isHookActive = prefs.getBoolean("screenRecordHookActive", true)

                        if (isHookActive) {
                            module.log(Log.INFO, TAG,
                                "Blocked ATMS.${method.name} (screen capture callback registration).")
                            return@intercept null
                        }
                        chain.proceed()
                    }
                    module.log(Log.INFO, TAG,
                        "Hooked ATMS.${method.name} successfully.")
                }
            }
        } catch (e: Exception) {
            module.log(Log.WARN, TAG,
                "Could not hook ActivityTaskManagerService screen capture methods: $e")
        }

        // Strategy 3: Hook IScreenCaptureObserver.Stub.Proxy to block the IPC callback
        // This is the most direct approach - intercept the actual binder call
        try {
            val observerStubClass = try {
                param.classLoader.loadClass(
                    "android.app.IScreenCaptureObserver\$Stub\$Proxy"
                )
            } catch (e: ClassNotFoundException) {
                try {
                    param.classLoader.loadClass(
                        "android.app.IScreenCaptureObserver\$Stub"
                    )
                } catch (e2: ClassNotFoundException) {
                    null
                }
            }

            if (observerStubClass != null) {
                val onScreenCapturedMethod = try {
                    observerStubClass.getDeclaredMethod("onScreenCaptured")
                } catch (e: NoSuchMethodException) {
                    null
                }

                if (onScreenCapturedMethod != null) {
                    module.hook(onScreenCapturedMethod).intercept { chain ->
                        val prefs = module.getRemotePreferences(BuildConfig.APPLICATION_ID)
                        val isHookActive = prefs.getBoolean("screenRecordHookActive", true)

                        if (isHookActive) {
                            module.log(Log.INFO, TAG,
                                "Blocked IScreenCaptureObserver.onScreenCaptured() IPC call.")
                            return@intercept null
                        }
                        chain.proceed()
                    }
                    module.log(Log.INFO, TAG,
                        "Hooked IScreenCaptureObserver.onScreenCaptured successfully.")
                }
            }
        } catch (e: Exception) {
            module.log(Log.WARN, TAG,
                "Could not hook IScreenCaptureObserver: $e")
        }

        // Strategy 4: Hook WindowState methods that track capture state
        try {
            val windowStateClass = param.classLoader.loadClass(
                "com.android.server.wm.WindowState"
            )

            val captureMethods = windowStateClass.declaredMethods.filter { method ->
                method.name.let { name ->
                    name == "setScreenCaptureDisabled" ||
                    name == "isScreenCaptureDisabled" ||
                    name.contains("ScreenCapture", ignoreCase = false) ||
                    name == "reportScreenCaptured"
                }
            }

            for (method in captureMethods) {
                module.hook(method).intercept { chain ->
                    val prefs = module.getRemotePreferences(BuildConfig.APPLICATION_ID)
                    val isHookActive = prefs.getBoolean("screenRecordHookActive", true)

                    if (isHookActive) {
                        module.log(Log.INFO, TAG,
                            "Blocked WindowState.${method.name}.")
                        return@intercept when {
                            method.returnType == Boolean::class.javaPrimitiveType -> false
                            method.returnType == Void.TYPE -> Unit
                            else -> null
                        }
                    }
                    chain.proceed()
                }
                module.log(Log.INFO, TAG,
                    "Hooked WindowState.${method.name} successfully.")
            }
        } catch (e: Exception) {
            module.log(Log.WARN, TAG, "Could not hook WindowState screen capture methods: $e")
        }

        // Strategy 5: Hook Task/ActivityClientController for registerScreenCaptureCallback
        try {
            val controllerClass = try {
                param.classLoader.loadClass(
                    "com.android.server.wm.ActivityClientController"
                )
            } catch (e: ClassNotFoundException) {
                null
            }

            if (controllerClass != null) {
                val screenCaptureMethods = controllerClass.declaredMethods.filter { method ->
                    method.name.contains("ScreenCapture", ignoreCase = false) ||
                    method.name.contains("screenCapture", ignoreCase = false)
                }

                for (method in screenCaptureMethods) {
                    module.hook(method).intercept { chain ->
                        val prefs = module.getRemotePreferences(BuildConfig.APPLICATION_ID)
                        val isHookActive = prefs.getBoolean("screenRecordHookActive", true)

                        if (isHookActive) {
                            module.log(Log.INFO, TAG,
                                "Blocked ActivityClientController.${method.name}.")
                            return@intercept when {
                                method.returnType == Boolean::class.javaPrimitiveType -> false
                                method.returnType == Void.TYPE -> Unit
                                else -> null
                            }
                        }
                        chain.proceed()
                    }
                    module.log(Log.INFO, TAG,
                        "Hooked ActivityClientController.${method.name} successfully.")
                }
            }
        } catch (e: Exception) {
            module.log(Log.WARN, TAG,
                "Could not hook ActivityClientController screen capture methods: $e")
        }
    }
}
