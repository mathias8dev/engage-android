package io.engage.sdk.push.fcm

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.engage.sdk.EngageLogger

/** Internal notification trampoline that makes tap handling independent of the host Activity lifecycle. */
public class EngagePushOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val handled = EngagePushModule.onOpen(intent)
        val webUrlHandled = handled && intent.getStringExtra(ACTION_TYPE) == WEB_URL
        EngageLogger.debug(
            "Push",
            "notification trampoline handled=$handled webUrlHandled=$webUrlHandled",
        )
        if (!webUrlHandled) launchHostApplication()
        finish()
    }

    private fun launchHostApplication() {
        val launcher = packageManager.getLaunchIntentForPackage(packageName) ?: run {
            EngageLogger.warn("Push", "host application launch failed reason=no_launcher")
            return
        }
        intent.extras?.let(launcher::putExtras)
        launcher.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(launcher)
        EngageLogger.debug("Push", "host application launched from notification trampoline")
    }

    private companion object {
        const val ACTION_TYPE = "engage_action_type"
        const val WEB_URL = "WEB_URL"
    }
}
