package com.keshav.capturesposed.hookers

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.util.Log
import com.keshav.capturesposed.BuildConfig
import com.keshav.capturesposed.TAG
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.util.concurrent.Executor

/**
 * Hooks Activity.registerScreenCaptureCallback(Executor, ScreenCaptureCallback) directly
 * inside each target app's process. When active, the original registration is skipped so the
 * app never receives the "screenshot was taken" callback.
 *
 * This complements WindowManagerServiceHooker (which neutralises detection at the framework
 * level in system_server). Hooking app-side as well makes the registration itself a no-op,
 * which also covers apps that branch on whether registration succeeded.
 *
 * Requires the module to be scoped to the target app (see META-INF/xposed/module.prop).
 */
object RegisterScreenCaptureCallbackHooker {

    @SuppressLint("PrivateApi")
    fun hook(param: PackageLoadedParam, module: XposedModule) {
        // registerScreenCaptureCallback exists from API 34 (UPSIDE_DOWN_CAKE / Android 14).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

        // android.app.Activity$ScreenCaptureCallback — load by name to avoid compileSdk coupling.
        val screenCaptureCallbackClass =
            Class.forName("android.app.Activity\$ScreenCaptureCallback")

        val registerMethod = Activity::class.java.getDeclaredMethod(
            "registerScreenCaptureCallback",
            Executor::class.java,
            screenCaptureCallbackClass
        )

        module.hook(registerMethod).intercept { chain ->
            val prefs = module.getRemotePreferences(BuildConfig.APPLICATION_ID)
            // Reuse the same toggle as the WMS screenshot hook so one switch governs both.
            val isHookActive = prefs.getBoolean("screenshotHookActive", true)

            if (isHookActive) {
                module.log(
                    Log.INFO, TAG,
                    "Blocked registerScreenCaptureCallback in ${param.packageName}"
                )
                // Do NOT call chain.proceed() -> original is skipped. Method returns void.
                null
            } else {
                chain.proceed()
            }
        }
    }
}
