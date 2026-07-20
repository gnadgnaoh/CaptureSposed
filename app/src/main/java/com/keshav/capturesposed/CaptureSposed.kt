package com.keshav.capturesposed

import android.os.Build
import android.util.Log
import com.keshav.capturesposed.hookers.ScreenCaptureCallbackHooker
import com.keshav.capturesposed.hookers.ScreenRecordingCallbackControllerHooker
import com.keshav.capturesposed.hookers.WindowManagerServiceHooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

private lateinit var module: CaptureSposed

const val TAG = "CaptureSposed"

class CaptureSposed : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        module = this
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        super.onSystemServerStarting(param)

        try {
            WindowManagerServiceHooker.hook(param, module)

            // Android 14+ (API 34): Hook Activity.ScreenCaptureCallback mechanism
            // This blocks apps like Paytm from detecting screen recording via
            // registerScreenCaptureCallback() and showing error 70015.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ScreenCaptureCallbackHooker.hook(param, module)

            // Android 15+ (API 35): Hook ScreenRecordingCallbackController
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
                ScreenRecordingCallbackControllerHooker.hook(param, module)
        } catch (e: Exception) {
            log(Log.ERROR, TAG, "ERROR: $e")
        }
    }
}
