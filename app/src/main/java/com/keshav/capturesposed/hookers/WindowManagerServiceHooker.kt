package com.keshav.capturesposed.hookers

import android.annotation.SuppressLint
import android.content.ComponentName
import android.os.Build
import android.os.ResultReceiver
import android.util.ArraySet
import android.util.Log
import com.keshav.capturesposed.BuildConfig
import com.keshav.capturesposed.TAG
import com.keshav.capturesposed.utils.XposedHelpers
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.io.FileDescriptor

object WindowManagerServiceHooker {
    var module: XposedModule? = null
    private var classLoader: ClassLoader? = null

    @SuppressLint("PrivateApi")
    fun hook(param: SystemServerStartingParam, module: XposedModule) {
        this.module = module
        classLoader = param.classLoader
        val windowManagerServiceClass = param.classLoader.loadClass("com.android.server.wm.WindowManagerService")

        module.hook(
            windowManagerServiceClass.getDeclaredMethod("notifyScreenshotListeners", Int::class.java)
        ).intercept { chain ->
            val prefs = module.getRemotePreferences(BuildConfig.APPLICATION_ID)
            val isHookActive = prefs.getBoolean("screenshotHookActive", true)

            if (isHookActive) {
                module.log(Log.INFO, TAG, "Blocked screenshot detection.")
                return@intercept emptyList<ComponentName>()
            }

            module.log(Log.INFO, TAG, "Allowed screenshot detection.")
            chain.proceed()
        }

        // Hook ScreenCaptureCallback dispatching (Android 14+)
        // This blocks Activity.ScreenCaptureCallback.onScreenCaptured() from being triggered,
        // which prevents apps like Paytm from showing error 70015 ("Screen sharing is not allowed")
        // when using built-in screen recording on custom ROMs.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                // Hook notifyScreenCaptureListeners - called when screen capture state changes
                val notifyScreenCaptureMethod = try {
                    windowManagerServiceClass.getDeclaredMethod("notifyScreenCaptureListeners", Int::class.java)
                } catch (e: NoSuchMethodException) {
                    null
                }

                if (notifyScreenCaptureMethod != null) {
                    module.hook(notifyScreenCaptureMethod).intercept { chain ->
                        val prefs = module.getRemotePreferences(BuildConfig.APPLICATION_ID)
                        val isHookActive = prefs.getBoolean("screenRecordHookActive", true)

                        if (isHookActive) {
                            module.log(Log.INFO, TAG, "Blocked screen capture callback notification.")
                            return@intercept null
                        }
                        chain.proceed()
                    }
                    module.log(Log.INFO, TAG, "Hooked notifyScreenCaptureListeners successfully.")
                }

                // Hook Session.registerScreenCaptureCallback to intercept registration
                // and prevent callbacks from ever firing
                try {
                    val sessionClass = param.classLoader.loadClass("com.android.server.wm.Session")
                    val registerMethods = sessionClass.declaredMethods.filter {
                        it.name.contains("registerScreenCaptureCallback") ||
                        it.name.contains("ScreenCapture")
                    }
                    for (method in registerMethods) {
                        if (method.name.contains("register")) {
                            module.hook(method).intercept { chain ->
                                val prefs = module.getRemotePreferences(BuildConfig.APPLICATION_ID)
                                val isHookActive = prefs.getBoolean("screenRecordHookActive", true)

                                if (isHookActive) {
                                    module.log(Log.INFO, TAG, "Blocked screen capture callback registration via ${method.name}.")
                                    return@intercept null
                                }
                                chain.proceed()
                            }
                            module.log(Log.INFO, TAG, "Hooked ${method.name} in Session successfully.")
                        }
                    }
                } catch (e: Exception) {
                    module.log(Log.WARN, TAG, "Could not hook Session screen capture methods: $e")
                }

                // Hook WindowState or DisplayContent methods that dispatch screen capture state
                try {
                    val displayContentClass = param.classLoader.loadClass("com.android.server.wm.DisplayContent")
                    val dispatchMethods = displayContentClass.declaredMethods.filter {
                        it.name.contains("screenCapture", ignoreCase = true) ||
                        it.name.contains("ScreenCapture")
                    }
                    for (method in dispatchMethods) {
                        module.hook(method).intercept { chain ->
                            val prefs = module.getRemotePreferences(BuildConfig.APPLICATION_ID)
                            val isHookActive = prefs.getBoolean("screenRecordHookActive", true)

                            if (isHookActive) {
                                module.log(Log.INFO, TAG, "Blocked DisplayContent.${method.name}.")
                                return@intercept if (method.returnType == Boolean::class.javaPrimitiveType) false
                                    else if (method.returnType == Void.TYPE) Unit
                                    else null
                            }
                            chain.proceed()
                        }
                        module.log(Log.INFO, TAG, "Hooked DisplayContent.${method.name} successfully.")
                    }
                } catch (e: Exception) {
                    module.log(Log.WARN, TAG, "Could not hook DisplayContent screen capture methods: $e")
                }

            } catch (e: Exception) {
                module.log(Log.WARN, TAG, "Could not hook screen capture callbacks (Android 14+): $e")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            module.hook(
                windowManagerServiceClass.getDeclaredMethod(
                    "onShellCommand", FileDescriptor::class.java,
                    FileDescriptor::class.java, FileDescriptor::class.java, Array<String>::class.java,
                    classLoader!!.loadClass("android.os.ShellCallback"), ResultReceiver::class.java
                )
            ).intercept { chain ->
                // This will intercept command: wm refresh-recording-callbacks
                val wmCommandArgs = chain.getArg(3) as Array<*>
                if (wmCommandArgs.size == 1 && wmCommandArgs[0] == "refresh-recording-callbacks") {
                    val mScreenRecordingCallbackController = XposedHelpers.getObjectField(
                        chain.thisObject, "mScreenRecordingCallbackController"
                    ) as Any
                    val screenRecordingCallbackControllerClass = mScreenRecordingCallbackController::class.java

                    val getRecordedUidsMethod =
                        screenRecordingCallbackControllerClass.getDeclaredMethod("getRecordedUids")
                    val dispatchCallbacksMethod = screenRecordingCallbackControllerClass.getDeclaredMethod(
                        "dispatchCallbacks", ArraySet::class.java, Boolean::class.javaPrimitiveType
                    )

                    getRecordedUidsMethod.isAccessible = true
                    dispatchCallbacksMethod.isAccessible = true

                    val recordedUids = getRecordedUidsMethod.invoke(mScreenRecordingCallbackController)
                    val mRecordedWC =
                        XposedHelpers.getObjectField(mScreenRecordingCallbackController, "mRecordedWC")

                    dispatchCallbacksMethod.invoke(
                        mScreenRecordingCallbackController, recordedUids, mRecordedWC != null
                    )
                }
                chain.proceed()
            }
        }
    }
}
