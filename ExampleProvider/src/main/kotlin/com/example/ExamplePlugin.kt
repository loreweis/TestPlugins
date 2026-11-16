package com.example

import android.content.Context
import android.content.ContextWrapper
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ExamplePlugin: Plugin() {
    private var activity: AppCompatActivity? = null

    override fun load(context: Context) {
        activity = context as? AppCompatActivity

        // All providers should be added in this manner
        registerMainAPI(ExampleProvider())

        openSettings = { ctx ->
            var act: AppCompatActivity? = ctx as? AppCompatActivity
            if (act == null && ctx is ContextWrapper) {
                var c: Context? = ctx
                while (c is ContextWrapper) {
                    if (c is AppCompatActivity) {
                        act = c
                        break
                    }
                    c = c.baseContext
                }
            }
            if (act == null) act = activity

            act?.let {
                val frag = BlankFragment(this)
                frag.show(it.supportFragmentManager, "TestControlCenter")
            }
        }
    }
}
