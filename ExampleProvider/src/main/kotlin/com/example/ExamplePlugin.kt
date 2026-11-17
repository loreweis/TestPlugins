package com.example

import android.content.Context
import android.content.ContextWrapper
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
import com.google.android.material.navigation.NavigationBarView
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.util.WeakHashMap

@CloudstreamPlugin
class ExamplePlugin : Plugin() {
    private var activity: AppCompatActivity? = null
    private val layoutWatchers = WeakHashMap<View, ViewTreeObserver.OnGlobalLayoutListener>()

    override fun load(context: Context) {
        activity = getAppCompatActivity(context)
        registerMainAPI(ExampleProvider())

        openSettings = { ctx ->
            val hostActivity = getAppCompatActivity(ctx) ?: activity
            hostActivity?.let { BlankFragment(this).show(it.supportFragmentManager, "TestControlCenter") }
        }

        activity?.let { host ->
            host.runOnUiThread { observeHomeFragments(host) }
        }
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
        if (swapHeaderControls(host, homeRoot) || layoutWatchers.containsKey(homeRoot)) return

        val watcher = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (swapHeaderControls(host, homeRoot)) stopWatchingHomeLayout(homeRoot)
            }
        }
        layoutWatchers[homeRoot] = watcher
        homeRoot.viewTreeObserver.addOnGlobalLayoutListener(watcher)

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

    private fun stopWatchingHomeLayout(homeRoot: View) {
        val watcher = layoutWatchers.remove(homeRoot) ?: return
        val observer = homeRoot.viewTreeObserver
        if (observer.isAlive) observer.removeOnGlobalLayoutListener(watcher)
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
            val searchButton = searchRow.findViewWithTag<ImageButton>(SEARCH_BUTTON_TAG)
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
            if (searchButton.parent == null) {
                val params = LinearLayout.LayoutParams(dp(host, SEARCH_BUTTON_SIZE_DP), ViewGroup.LayoutParams.MATCH_PARENT)
                searchRow.addView(searchButton, params)
            }

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
}
