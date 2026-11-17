package com.example

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.util.WeakHashMap

@CloudstreamPlugin
class ExamplePlugin : Plugin() {
    private var activity: AppCompatActivity? = null
    private val swappedHomeRoots = WeakHashMap<View, Boolean>()

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
                if (isHome(fragment)) scheduleHeaderSwap(host, view)
            }

            override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
                if (isHome(fragment)) fragment.view?.let { scheduleHeaderSwap(host, it) }
            }
        }
        host.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
        visitFragments(host, host.supportFragmentManager)
    }

    private fun visitFragments(host: AppCompatActivity, manager: FragmentManager) {
        manager.fragments.forEach { fragment ->
            if (isHome(fragment)) fragment.view?.let { scheduleHeaderSwap(host, it) }
            visitFragments(host, fragment.childFragmentManager)
        }
    }

    private fun isHome(fragment: Fragment) = fragment.javaClass.simpleName.contains("Home", ignoreCase = true)

    private fun scheduleHeaderSwap(host: AppCompatActivity, homeRoot: View, attempt: Int = 0) {
        if (swappedHomeRoots[homeRoot] == true) return
        if (swapHeaderControls(host, homeRoot)) return
        if (attempt < MAX_HEADER_ATTEMPTS) {
            homeRoot.postDelayed({ scheduleHeaderSwap(host, homeRoot, attempt + 1) }, HEADER_RETRY_DELAY_MS)
        }
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
        if (searchRow === alternateParent) return false

        val previousIndex = alternateParent.indexOfChild(alternateProfile)
        val previousParams = alternateProfile.layoutParams
        return runCatching {
            alternateParent.removeView(alternateProfile)
            searchRow.addView(alternateProfile, 0)
            alternateProfile.visibility = View.VISIBLE
            regularProfile.visibility = View.GONE

            val searchFocusId = search.id
            val profileFocusId = alternateProfile.id
            search.nextFocusLeftId = profileFocusId
            alternateProfile.nextFocusRightId = searchFocusId
            swappedHomeRoots[homeRoot] = true
            true
        }.getOrElse {
            (alternateProfile.parent as? ViewGroup)?.removeView(alternateProfile)
            if (alternateProfile.parent == null) {
                alternateParent.addView(alternateProfile, previousIndex.coerceAtLeast(0), previousParams)
            }
            false
        }
    }

    private companion object {
        const val HEADER_RETRY_DELAY_MS = 350L
        const val MAX_HEADER_ATTEMPTS = 48
    }
}
