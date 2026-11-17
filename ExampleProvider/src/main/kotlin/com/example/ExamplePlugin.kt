package com.example

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationBarView
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.util.WeakHashMap

@CloudstreamPlugin
class ExamplePlugin : Plugin() {
    private var activity: AppCompatActivity? = null
    private var application: Application? = null
    private var activityLifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var componentCallbacks: ComponentCallbacks? = null
    private val attachedActivities = WeakHashMap<AppCompatActivity, Boolean>()
    private val fragmentCallbacks = WeakHashMap<AppCompatActivity, FragmentManager.FragmentLifecycleCallbacks>()
    private val layoutWatchers = WeakHashMap<View, LayoutWatcher>()
    private val recyclerWatchers = WeakHashMap<RecyclerView, RecyclerView.OnChildAttachStateChangeListener>()

    override fun load(context: Context) {
        activity = getAppCompatActivity(context)
        registerMainAPI(ExampleProvider())

        openSettings = { ctx ->
            val hostActivity = getAppCompatActivity(ctx) ?: activity
            hostActivity?.let { BlankFragment(this).show(it.supportFragmentManager, "TestControlCenter") }
        }

        val app = context.applicationContext as? Application
        if (app != null && activityLifecycleCallbacks == null) {
            application = app
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    attachIfMainActivity(activity)
                }

                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = attachIfMainActivity(activity)
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) {
                    (activity as? AppCompatActivity)?.let {
                        fragmentCallbacks.remove(it)?.let(it.supportFragmentManager::unregisterFragmentLifecycleCallbacks)
                        attachedActivities.remove(it)
                        if (this@ExamplePlugin.activity === it) this@ExamplePlugin.activity = null
                    }
                }
            }
            activityLifecycleCallbacks = callbacks
            app.registerActivityLifecycleCallbacks(callbacks)
        }
        if (app != null && componentCallbacks == null) {
            val callbacks = object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    activity?.let { host -> host.runOnUiThread { attachToActivity(host) } }
                }

                override fun onLowMemory() = Unit
            }
            componentCallbacks = callbacks
            app.registerComponentCallbacks(callbacks)
        }

        activity?.let { host -> host.runOnUiThread { attachToActivity(host) } }
    }

    override fun beforeUnload() {
        application?.let { app ->
            activityLifecycleCallbacks?.let(app::unregisterActivityLifecycleCallbacks)
            componentCallbacks?.let(app::unregisterComponentCallbacks)
        }
        activityLifecycleCallbacks = null
        componentCallbacks = null

        fragmentCallbacks.forEach { (host, callbacks) ->
            host.supportFragmentManager.unregisterFragmentLifecycleCallbacks(callbacks)
        }
        fragmentCallbacks.clear()
        attachedActivities.clear()

        layoutWatchers.keys.toList().forEach(::stopWatchingHomeLayout)
        recyclerWatchers.forEach { (recycler, listener) ->
            recycler.removeOnChildAttachStateChangeListener(listener)
        }
        recyclerWatchers.clear()
        activity = null
        application = null
    }

    private fun attachIfMainActivity(activity: Activity) {
        if (activity.javaClass.simpleName != "MainActivity") return
        val host = activity as? AppCompatActivity ?: return
        host.runOnUiThread { attachToActivity(host) }
    }

    private fun attachToActivity(host: AppCompatActivity) {
        activity = host
        if (attachedActivities.put(host, true) == null) observeHomeFragments(host)
        visitFragments(host, host.supportFragmentManager)
    }

    private fun getAppCompatActivity(context: Context?): AppCompatActivity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is AppCompatActivity) return current
            current = current.baseContext
        }
        return current as? AppCompatActivity
    }

    private fun observeHomeFragments(host: AppCompatActivity) {
        if (fragmentCallbacks.containsKey(host)) return
        val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(
                fm: FragmentManager,
                fragment: Fragment,
                view: View,
                savedInstanceState: android.os.Bundle?
            ) {
                if (isHome(fragment)) watchHomeLayout(host, fragment, view)
            }

            override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
                if (isHome(fragment)) fragment.view?.let { watchHomeLayout(host, fragment, it) }
            }

        }
        host.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
        fragmentCallbacks[host] = callbacks
        visitFragments(host, host.supportFragmentManager)
    }

    private fun visitFragments(host: AppCompatActivity, manager: FragmentManager) {
        manager.fragments.forEach { fragment ->
            if (isHome(fragment)) fragment.view?.let { watchHomeLayout(host, fragment, it) }
            visitFragments(host, fragment.childFragmentManager)
        }
    }

    private fun isHome(fragment: Fragment) = fragment.javaClass.simpleName.contains("Home", ignoreCase = true)

    private fun watchHomeLayout(host: AppCompatActivity, fragment: Fragment, homeRoot: View) {
        watchHomeRecycler(host, fragment, homeRoot)
        if (swapHeaderControls(host, homeRoot)) return

        layoutWatchers[homeRoot]?.let { existing ->
            if (existing.observer.isAlive) return
            layoutWatchers.remove(homeRoot)
        }

        val observer = homeRoot.viewTreeObserver
        if (!observer.isAlive) return

        val watcher = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (swapHeaderControls(host, homeRoot)) stopWatchingHomeLayout(homeRoot)
            }
        }
        layoutWatchers[homeRoot] = LayoutWatcher(observer, watcher)
        observer.addOnGlobalLayoutListener(watcher)

        val lifecycle = fragment.viewLifecycleOwner.lifecycle
        val cleanup = object : LifecycleEventObserver {
            override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_DESTROY) {
                    stopWatchingHomeLayout(homeRoot)
                    lifecycle.removeObserver(this)
                }
            }
        }
        lifecycle.addObserver(cleanup)
    }

    private fun watchHomeRecycler(host: AppCompatActivity, fragment: Fragment, homeRoot: View) {
        val recyclerId = host.resources.getIdentifier("home_master_recycler", "id", host.packageName)
        val recycler = (if (recyclerId != 0) homeRoot.findViewById<RecyclerView>(recyclerId) else null)
            ?: return
        if (recyclerWatchers.containsKey(recycler)) return

        val searchId = host.resources.getIdentifier("home_search", "id", host.packageName)
        val listener = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(child: View) {
                if (searchId != 0 && child.findViewById<View>(searchId) != null) {
                    swapHeaderControls(host, child)
                }
            }

            override fun onChildViewDetachedFromWindow(child: View) = Unit
        }
        recycler.addOnChildAttachStateChangeListener(listener)
        recyclerWatchers[recycler] = listener

        val lifecycle = fragment.viewLifecycleOwner.lifecycle
        val cleanup = object : LifecycleEventObserver {
            override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_DESTROY) {
                    recycler.removeOnChildAttachStateChangeListener(listener)
                    recyclerWatchers.remove(recycler)
                    lifecycle.removeObserver(this)
                }
            }
        }
        lifecycle.addObserver(cleanup)
    }

    private fun stopWatchingHomeLayout(homeRoot: View) {
        val watcher = layoutWatchers.remove(homeRoot) ?: return
        if (watcher.observer.isAlive) watcher.observer.removeOnGlobalLayoutListener(watcher.listener)
    }

    private fun swapHeaderControls(host: AppCompatActivity, homeRoot: View): Boolean {
        val searchId = host.resources.getIdentifier("home_search", "id", host.packageName)
        val alternateProfileId = host.resources.getIdentifier("alternate_home_head_profile_padding", "id", host.packageName)
        val regularProfileId = host.resources.getIdentifier("home_head_profile_padding", "id", host.packageName)
        if (searchId == 0 || alternateProfileId == 0 || regularProfileId == 0) return false

        val search = homeRoot.findViewById<View>(searchId) ?: return false
        val alternateProfile = homeRoot.findViewById<View>(alternateProfileId) ?: return false
        val regularProfile = homeRoot.findViewById<View>(regularProfileId) ?: return false
        val searchRow = search.parent as? ViewGroup ?: return false
        val alternateParent = alternateProfile.parent as? ViewGroup ?: return false
        val searchIconId = host.resources.getIdentifier("search_mag_icon", "id", "androidx.appcompat")
        val originalSearchIcon = if (searchIconId != 0) search.findViewById<View>(searchIconId) as? ImageView else null
        val drawable = originalSearchIcon?.drawable?.constantState
            ?.newDrawable(host.resources)?.mutate()
            ?: host.resources.getIdentifier("search_icon", "drawable", host.packageName)
                .takeIf { it != 0 }
                ?.let { host.resources.getDrawable(it, host.theme) }
            ?: return false

        return runCatching {
            if (searchRow !== alternateParent) {
                alternateParent.removeView(alternateProfile)
                searchRow.addView(alternateProfile, 0)
            }
            alternateProfile.visibility = View.VISIBLE
            regularProfile.visibility = View.GONE
            // Keep its weighted space so the replacement search button lands at the old account slot.
            search.visibility = View.INVISIBLE

            val searchButtonId = host.resources.getIdentifier("search_button", "id", host.packageName)
            val searchButton = (homeRoot as? ViewGroup)?.findViewWithTag<ImageButton>(SEARCH_BUTTON_TAG)
                ?: ImageButton(host).apply {
                    id = searchButtonId.takeIf { it != 0 } ?: View.generateViewId()
                    tag = SEARCH_BUTTON_TAG
                    contentDescription = "Pencarian lanjutan"
                    isFocusable = true
                    isClickable = true
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(host, 12), dp(host, 12), dp(host, 12), dp(host, 12))
                    setBackground(
                        originalSearchIcon?.background?.constantState?.newDrawable(host.resources)?.mutate()
                            ?: host.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).let { attrs ->
                                val background = attrs.getDrawable(0)
                                attrs.recycle()
                                background
                            }
                    )
                    setImageDrawable(drawable)
                    imageTintList = originalSearchIcon?.imageTintList
                    setOnClickListener { openAdvancedSearch(host) }
                }
            if (searchButton.parent !== searchRow) {
                (searchButton.parent as? ViewGroup)?.removeView(searchButton)
                val params = LinearLayout.LayoutParams(dp(host, SEARCH_BUTTON_SIZE_DP), ViewGroup.LayoutParams.MATCH_PARENT)
                searchRow.addView(searchButton, params)
            }
            searchButton.setImageDrawable(drawable)
            searchButton.imageTintList = originalSearchIcon?.imageTintList

            alternateProfile.nextFocusRightId = searchButton.id
            searchButton.nextFocusLeftId = alternateProfile.id
            true
        }.getOrDefault(false)
    }

    private fun openAdvancedSearch(host: AppCompatActivity) {
        val railId = host.resources.getIdentifier("nav_rail_view", "id", host.packageName)
        val bottomId = host.resources.getIdentifier("nav_view", "id", host.packageName)
        val searchDestinationId = host.resources.getIdentifier("navigation_search", "id", host.packageName)
        val navBar = listOfNotNull(
            if (railId != 0) host.findViewById<NavigationBarView>(railId) else null,
            if (bottomId != 0) host.findViewById<NavigationBarView>(bottomId) else null,
        ).firstOrNull { it.isShown } ?: return
        if (searchDestinationId == 0 || navBar.menu.findItem(searchDestinationId) == null) return
        runCatching { navBar.menu.performIdentifierAction(searchDestinationId, 0) }
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val SEARCH_BUTTON_TAG = "test_plugin_advanced_search_button"
        const val SEARCH_BUTTON_SIZE_DP = 50
    }

    private data class LayoutWatcher(
        val observer: ViewTreeObserver,
        val listener: ViewTreeObserver.OnGlobalLayoutListener
    )
}
