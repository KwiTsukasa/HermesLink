package cn.xhs.hermespocket

import android.app.Application
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.facebook.react.uimanager.ViewManager

class HermesPocketApplication : Application(), ReactApplication {
    override val reactHost: ReactHost by lazy {
        getDefaultReactHost(
            context = applicationContext,
            packageList = PackageList(this).packages.apply { add(HermesWorkspacePackage()) },
            useDevSupport = false,
        )
    }

    override fun onCreate() {
        super.onCreate()
        HermesNativeStrings.initialize(this)
        loadReactNative(this)
    }
}

private class HermesWorkspacePackage : ReactPackage {
    @Suppress("OVERRIDE_DEPRECATION")
    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> =
        listOf(
            HermesWorkspaceModule(reactContext),
            HermesTerminalModule(reactContext),
            HermesMediaModule(reactContext),
            HermesResourcesModule(reactContext),
            HermesOfficialModule(reactContext),
            HermesLiveModule(reactContext),
            HermesLocaleModule(reactContext),
            HermesAppearanceModule(reactContext),
        )

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> =
        listOf(HermesTerminalSurfaceManager(), HermesDisplaySurfaceManager())
}
