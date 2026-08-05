package io.engage.sdk.inapp.render

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.engage.sdk.EngageLogger
import java.lang.ref.WeakReference

internal class ActivityMonitor(
    application: Application,
    private val onChanged: () -> Unit,
) : Application.ActivityLifecycleCallbacks {
    private var resumed = WeakReference<Activity>(null)

    init {
        application.registerActivityLifecycleCallbacks(this)
        EngageLogger.debug("InApp.Activity", "activity lifecycle monitor registered")
    }

    val current: Activity?
        get() = resumed.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        EngageLogger.debug("InApp.Activity", "activity resumed class=${activity.javaClass.simpleName}")
        onChanged()
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed.get() === activity) {
            resumed.clear()
            EngageLogger.debug("InApp.Activity", "activity paused class=${activity.javaClass.simpleName}")
            onChanged()
        }
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
