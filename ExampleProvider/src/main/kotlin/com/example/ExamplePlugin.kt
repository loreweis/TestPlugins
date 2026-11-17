package com.example

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.plugins.RepositoryManager.PREBUILT_REPOSITORIES
import com.lagradost.cloudstream3.plugins.PluginManager.getPluginsLocal
import com.lagradost.cloudstream3.plugins.PluginManager.getPluginsOnline
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val homeHeaderStates = WeakHashMap<ViewGroup, HomeHeaderState>()
    private val providerIconUrls = mutableMapOf<String, String?>()
    private val providerIconRequests = mutableSetOf<String>()
    private val tuiTypefaceMedium: Typeface by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create("monospace", 500, false)
        else Typeface.create("monospace", Typeface.BOLD)
    }

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
                    val host = mainActivityOrNull(activity) ?: return
                    host.runOnUiThread { observeHomeFragments(host) }
                }

                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) {
                    val host = mainActivityOrNull(activity) ?: return
                    host.window.decorView.post {
                        attachToActivity(host)
                        scanCurrentHome(host)
                    }
                }
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) {
                    (activity as? AppCompatActivity)?.let {
                        fragmentCallbacks.remove(it)?.let { callbacks ->
                            runCatching { it.supportFragmentManager.unregisterFragmentLifecycleCallbacks(callbacks) }
                        }
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
                    activity?.let { host ->
                        host.window.decorView.post {
                            if (host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                attachToActivity(host)
                                scanCurrentHome(host)
                            }
                        }
                    }
                }

                override fun onLowMemory() = Unit
            }
            componentCallbacks = callbacks
            app.registerComponentCallbacks(callbacks)
        }

        activity?.let { host ->
            host.runOnUiThread {
                observeHomeFragments(host)
                host.window.decorView.post {
                    if (host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        attachToActivity(host)
                        scanCurrentHome(host)
                    }
                }
            }
        }
    }

    override fun beforeUnload() {
        application?.let { app ->
            activityLifecycleCallbacks?.let(app::unregisterActivityLifecycleCallbacks)
            componentCallbacks?.let(app::unregisterComponentCallbacks)
        }
        activityLifecycleCallbacks = null
        componentCallbacks = null

        fragmentCallbacks.forEach { (host, callbacks) ->
            runCatching { host.supportFragmentManager.unregisterFragmentLifecycleCallbacks(callbacks) }
        }
        fragmentCallbacks.clear()
        attachedActivities.clear()

        layoutWatchers.keys.toList().forEach(::stopWatchingHomeLayout)
        recyclerWatchers.forEach { (recycler, listener) ->
            recycler.removeOnChildAttachStateChangeListener(listener)
        }
        recyclerWatchers.clear()
        homeHeaderStates.toMap().forEach { (account, state) ->
            runCatching { restoreHomeHeader(account, state) }
        }
        homeHeaderStates.clear()
        providerIconUrls.clear()
        providerIconRequests.clear()
        activity = null
        application = null
    }

    private fun mainActivityOrNull(activity: Activity): AppCompatActivity? {
        if (activity.javaClass.simpleName != "MainActivity") return null
        return activity as? AppCompatActivity
    }

    private fun attachToActivity(host: AppCompatActivity) {
        activity = host
        if (attachedActivities.put(host, true) == null) observeHomeFragments(host)
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
    }

    private fun scanCurrentHome(host: AppCompatActivity) {
        val navHostId = host.resources.getIdentifier("nav_host_fragment", "id", host.packageName)
        if (navHostId == 0) return
        val navHost = runCatching { host.supportFragmentManager.findFragmentById(navHostId) }.getOrNull()
            ?: return
        if (!navHost.isAdded) return
        val home = runCatching { navHost.childFragmentManager.fragments }
            .getOrNull()
            ?.firstOrNull { it.isAdded && isHome(it) }
            ?: return
        home.view?.let { watchHomeLayout(host, home, it) }
    }

    private fun isHome(fragment: Fragment) = fragment.javaClass.simpleName.contains("Home", ignoreCase = true)

    private fun watchHomeLayout(host: AppCompatActivity, fragment: Fragment, homeRoot: View) {
        watchHomeRecycler(host, fragment, homeRoot)
        if (installHomeBrand(host, fragment, homeRoot)) return

        layoutWatchers[homeRoot]?.let { existing ->
            if (existing.observer.isAlive) return
            layoutWatchers.remove(homeRoot)
        }

        val observer = homeRoot.viewTreeObserver
        if (!observer.isAlive) return

        val watcher = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (installHomeBrand(host, fragment, homeRoot)) stopWatchingHomeLayout(homeRoot)
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
                    installHomeBrand(host, fragment, child)
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

    private fun installHomeBrand(
        host: AppCompatActivity,
        homeFragment: Fragment,
        headerRoot: View
    ): Boolean {
        val homeHeaderId = host.resources.getIdentifier("home_header", "id", host.packageName)
        val homeHeader = (if (homeHeaderId != 0) headerRoot.findViewById<ViewGroup>(homeHeaderId) else null)
            ?: (headerRoot as? ViewGroup)
            ?: return false
        val heroFrameId = host.resources.getIdentifier("home_preview_viewpager_text", "id", host.packageName)
        val heroFrame = (if (heroFrameId != 0) homeHeader.findViewById<FrameLayout>(heroFrameId) else null)
            ?: return false
        val titleHolderId = host.resources.getIdentifier("home_preview_title_holder", "id", host.packageName)
        val titleHolder = if (titleHolderId != 0) homeHeader.findViewById<View>(titleHolderId) else null
        val selectorFabId = host.resources.getIdentifier("home_api_fab", "id", host.packageName)
        val selectorFab = if (selectorFabId != 0) homeFragment.view?.findViewById<View>(selectorFabId) else null
        val accountId = host.resources.getIdentifier("home_head_profile_padding", "id", host.packageName)
        val searchId = host.resources.getIdentifier("home_search", "id", host.packageName)
        val account = (if (accountId != 0) headerRoot.findViewById<ViewGroup>(accountId) else null)
            ?: return false
        val search = (if (searchId != 0) headerRoot.findViewById<androidx.appcompat.widget.SearchView>(searchId) else null)
            ?: return false
        val parent = account.parent as? ViewGroup ?: return false
        if (search.parent !== parent || parent !is LinearLayout) return false
        val searchIconId = host.resources.getIdentifier("search_mag_icon", "id", "androidx.appcompat")
            .takeIf { it != 0 }
            ?: host.resources.getIdentifier("search_mag_icon", "id", host.packageName)
        val searchButtonId = host.resources.getIdentifier("search_button", "id", "androidx.appcompat")
            .takeIf { it != 0 }
            ?: host.resources.getIdentifier("search_button", "id", host.packageName)
        val searchIcon = if (searchIconId != 0) search.findViewById<ImageView>(searchIconId) else null
        val searchButton = if (searchButtonId != 0) search.findViewById<View>(searchButtonId) else null
        val originalTopBarParent = parent.parent as? ViewGroup ?: return false
        val originalTopBarIndex = originalTopBarParent.indexOfChild(parent)
        if (originalTopBarIndex < 0) return false
        (headerRoot as? ViewGroup)?.findViewWithTag<View>(LEGACY_SEARCH_BUTTON_TAG)?.let { button ->
            (button.parent as? ViewGroup)?.removeView(button)
        }

        val headerState = homeHeaderStates[account] ?: run {
            val originalChildren = (0 until parent.childCount).map(parent::getChildAt)
            val searchIndex = originalChildren.indexOf(search)
            val accountIndex = originalChildren.indexOf(account)
            if (searchIndex < 0 || accountIndex < 0) return false
            val accountChildren = (0 until account.childCount).map(account::getChildAt)
            val state = HomeHeaderState(
                parent = parent,
                search = search,
                originalChildren = originalChildren,
                originalChildLayoutParams = originalChildren.map { copyLayoutParams(it.layoutParams) },
                searchIndex = searchIndex,
                accountIndex = accountIndex,
                accountChildren = accountChildren,
                accountChildLayoutParams = accountChildren.map { copyLayoutParams(it.layoutParams) },
                accountLayoutParams = copyLayoutParams(account.layoutParams),
                accountVisibility = account.visibility,
                accountPadding = intArrayOf(account.paddingLeft, account.paddingTop, account.paddingRight, account.paddingBottom),
                accountGravity = (account as? LinearLayout)?.gravity ?: Gravity.CENTER,
                searchVisibility = search.visibility,
                headerView = homeHeader,
                originalHeaderBackground = homeHeader.background,
                topBar = parent,
                originalTopBarParent = originalTopBarParent,
                originalTopBarIndex = originalTopBarIndex,
                originalTopBarLayoutParams = copyLayoutParams(parent.layoutParams),
                originalTopBarPadding = intArrayOf(parent.paddingLeft, parent.paddingTop, parent.paddingRight, parent.paddingBottom),
                originalTopBarBackground = parent.background,
                heroFrame = heroFrame,
                originalHeroFrameLayoutParams = copyLayoutParams(heroFrame.layoutParams),
                originalHeroFrameBackground = heroFrame.background,
                originalHeroFrameForeground = heroFrame.foreground,
                titleHolder = titleHolder,
                originalTitleHolderVisibility = titleHolder?.visibility,
                selectorFab = selectorFab,
                originalSelectorFabVisibility = selectorFab?.visibility,
                searchIcon = searchIcon,
                originalSearchIconLayoutParams = copyLayoutParams(searchIcon?.layoutParams),
                originalSearchIconPadding = searchIcon?.let {
                    intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom)
                },
                originalSearchIconTint = searchIcon?.imageTintList,
                originalSearchIconScaleType = searchIcon?.scaleType,
                searchButton = searchButton,
                originalSearchButtonLayoutParams = copyLayoutParams(searchButton?.layoutParams),
                originalSearchButtonBackground = searchButton?.background,
                originalSearchButtonPadding = (searchButton as? ImageView)?.let {
                    intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom)
                },
                originalSearchButtonTint = (searchButton as? ImageView)?.imageTintList,
                originalSearchButtonScaleType = (searchButton as? ImageView)?.scaleType,
                brandIcon = ImageView(host),
                brandFallback = TextView(host),
                brandName = TextView(host)
            )
            homeHeaderStates[account] = state
            watchHomeHeaderLifecycle(account, state, homeFragment)
            state
        }

        if (headerState.brandContainer?.parent === account) {
            applyTuiHeader(host, headerState)
            attachHomepageNameWatcher(host, homeFragment, headerState)
            updateSelectedProvider(host, homeFragment, headerState, getHomeApiName(homeFragment))
            return true
        }

        account.removeAllViews()
        (account as? LinearLayout)?.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        account.setPadding(0, 0, 0, 0)
        account.visibility = View.VISIBLE
        val orderedChildren = headerState.originalChildren.toMutableList().apply {
            this[headerState.searchIndex] = account
            this[headerState.accountIndex] = search
        }
        val brand = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "Pilih provider"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val iconBox = FrameLayout(host).apply {
            layoutParams = LinearLayout.LayoutParams(dp(host, 18), dp(host, 18)).apply {
                marginEnd = dp(host, 6)
            }
        }
        val initialName = getHomeApiName(homeFragment)
            ?.let { APIHolder.getApiFromNameNull(it) }
            ?.let(::getExtensionName)
            ?: "Pilih provider"
        headerState.brandFallback.apply {
            text = initialName.trim().firstOrNull()?.uppercase() ?: "?"
            textSize = 14f
            setTypeface(Typeface.create("monospace", Typeface.BOLD))
            gravity = Gravity.CENTER
            setTextColor(TUI_BRIGHT)
            background = GradientDrawable().apply {
                setColor(TUI_LINE)
            }
        }
        headerState.brandIcon.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply {
                cornerRadius = 0f
                setColor(Color.TRANSPARENT)
            }
        }
        detachFromParent(headerState.brandFallback)
        detachFromParent(headerState.brandIcon)
        detachFromParent(headerState.brandName)
        iconBox.addView(headerState.brandFallback, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        iconBox.addView(headerState.brandIcon, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        headerState.brandName.apply {
            text = initialName
            textSize = 16f
            maxLines = 1
            maxWidth = dp(host, 240)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(TUI_BRIGHT)
            setTypeface(tuiTypefaceMedium)
        }
        brand.addView(iconBox)
        brand.addView(headerState.brandName)
        brand.setOnClickListener {
            val selectorId = host.resources.getIdentifier("home_api_fab", "id", host.packageName)
            val selector = homeFragment.view?.findViewById<View>(selectorId)
            if (selector?.performClick() != true) {
                val fallbackId = host.resources.getIdentifier("home_change_api", "id", host.packageName)
                homeFragment.view?.findViewById<View>(fallbackId)?.performClick()
            }
        }
        brand.background = tuiRipple()
        account.addView(brand)
        headerState.brandContainer = brand

        parent.removeAllViews()
        orderedChildren.forEachIndexed { index, child ->
            val originalIndex = headerState.originalChildren.indexOf(child)
            val params = when (child) {
            account -> LinearLayout.LayoutParams(0, dp(host, PROVIDER_ICON_SIZE_DP), HEADER_SELECTOR_WEIGHT)
                search -> LinearLayout.LayoutParams(
                    dp(host, SEARCH_BUTTON_WIDTH_DP), dp(host, SEARCH_BUTTON_HEIGHT_DP)
                )
                else -> copyLayoutParams(headerState.originalChildLayoutParams[originalIndex])
            }
            if (params == null) parent.addView(child, index)
            else parent.addView(child, index, params)
        }
        applyTuiHeader(host, headerState)
        search.setOnSearchClickListener {
            search.post {
                account.visibility = View.GONE
                search.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(host, SEARCH_BUTTON_HEIGHT_DP)
                )
            }
        }
        search.setOnCloseListener {
            account.visibility = View.VISIBLE
            account.layoutParams = LinearLayout.LayoutParams(
                0,
                dp(host, PROVIDER_ICON_SIZE_DP),
                HEADER_SELECTOR_WEIGHT
            )
            search.layoutParams = LinearLayout.LayoutParams(
                dp(host, SEARCH_BUTTON_WIDTH_DP),
                dp(host, SEARCH_BUTTON_HEIGHT_DP),
                0f
            )
            false
        }
        attachHomepageNameWatcher(host, homeFragment, headerState)
        updateSelectedProvider(host, homeFragment, headerState, getHomeApiName(homeFragment))
        return true
    }

    private fun moveTopBarAboveHero(state: HomeHeaderState) {
        if (state.topBar.parent === state.headerView) return
        val header = state.headerView as? LinearLayout ?: return
        val frameIndex = state.headerView.indexOfChild(state.heroFrame)
        if (frameIndex < 0) return
        (state.topBar.parent as? ViewGroup)?.removeView(state.topBar)
        header.addView(
            state.topBar,
            frameIndex,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
    }

    private fun applyTuiHeader(host: AppCompatActivity, state: HomeHeaderState) {
        moveTopBarAboveHero(state)
        state.headerView.setBackgroundColor(TUI_BG)
        state.topBar.setBackgroundColor(TUI_BG)
        state.topBar.setPadding(dp(host, HEADER_HORIZONTAL_INSET_DP), dp(host, HEADER_VERTICAL_PADDING_DP),
            dp(host, HEADER_HORIZONTAL_INSET_DP), dp(host, HEADER_VERTICAL_PADDING_DP))
        (state.topBar.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            state.topBar.layoutParams = params
        }
        state.titleHolder?.visibility = View.GONE
        state.selectorFab?.visibility = View.GONE
        watchHeroPages(host, state)
        state.heroFrame?.let { frame ->
            frame.setBackgroundColor(TUI_BG)
            frame.foreground = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(host, 1), TUI_LINE)
                cornerRadius = 0f
            }
            applyHeroFrameGeometry(host, state)
            if (state.heroSizeListener == null) {
                val parent = frame.parent as? View ?: state.headerView
                val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    applyHeroFrameGeometry(host, state)
                }
                state.heroMeasureHost = parent
                state.heroSizeListener = listener
                parent.addOnLayoutChangeListener(listener)
            }
        }
        state.searchButton?.let { button ->
            button.background = tuiRipple()
            (button as? ImageView)?.let { imageButton ->
                imageButton.setPadding(
                    dp(host, SEARCH_BUTTON_HORIZONTAL_PADDING_DP), dp(host, SEARCH_BUTTON_VERTICAL_PADDING_DP),
                    dp(host, SEARCH_BUTTON_HORIZONTAL_PADDING_DP), dp(host, SEARCH_BUTTON_VERTICAL_PADDING_DP)
                )
                imageButton.imageTintList = ColorStateList.valueOf(TUI_BRIGHT)
                imageButton.scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val params = copyLayoutParams(button.layoutParams)
            if (params != null) {
                params.width = dp(host, SEARCH_CONTROL_WIDTH_DP)
                params.height = dp(host, SEARCH_BUTTON_HEIGHT_DP)
                if (params is LinearLayout.LayoutParams) params.gravity = Gravity.END
                button.layoutParams = params
            }
        }
        state.searchIcon?.let { icon ->
            icon.setPadding(0, 0, 0, 0)
            icon.imageTintList = ColorStateList.valueOf(TUI_BRIGHT)
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            val params = copyLayoutParams(icon.layoutParams)
            if (params != null) {
                params.width = dp(host, SEARCH_ICON_SIZE_DP)
                params.height = dp(host, SEARCH_ICON_SIZE_DP)
                icon.layoutParams = params
            }
        }
    }

    private fun applyHeroFrameGeometry(host: AppCompatActivity, state: HomeHeaderState) {
        val frame = state.heroFrame ?: return
        val availableWidth = (frame.parent as? View)?.width?.takeIf { it > 0 }
            ?: state.headerView.width.takeIf { it > 0 }
            ?: return
        val width = availableWidth - dp(host, HERO_HORIZONTAL_INSET_DP * 2)
        if (width <= 0) return
        val height = (width * HERO_ASPECT_HEIGHT / HERO_ASPECT_WIDTH).toInt()
        val params = frame.layoutParams as? LinearLayout.LayoutParams ?: return
        val changed = params.width != width || params.height != height ||
            params.topMargin != dp(host, HERO_TOP_PADDING_DP) ||
            params.bottomMargin != dp(host, HERO_BOTTOM_PADDING_DP) ||
            params.gravity != Gravity.CENTER_HORIZONTAL
        if (!changed) return
        params.width = width
        params.height = height
        params.topMargin = dp(host, HERO_TOP_PADDING_DP)
        params.bottomMargin = dp(host, HERO_BOTTOM_PADDING_DP)
        params.gravity = Gravity.CENTER_HORIZONTAL
        frame.layoutParams = params
    }

    private fun watchHeroPages(host: AppCompatActivity, headerState: HomeHeaderState) {
        val pagerId = host.resources.getIdentifier("home_preview_viewpager", "id", host.packageName)
        val pager = (if (pagerId != 0) headerState.headerView.findViewById<ViewGroup>(pagerId) else null)
            ?: return
        val recycler = pager.getChildAt(0) as? RecyclerView ?: return
        headerState.heroPages?.let { current ->
            if (current.recycler === recycler) {
                restyleAttachedHeroPages(host, current)
                return
            }
            cleanupHeroPages(current)
        }

        val pages = HeroPagesState(
            recycler = recycler,
            pageStyles = WeakHashMap()
        )
        val listener = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                styleHeroPage(host, pages, view)
            }

            override fun onChildViewDetachedFromWindow(view: View) {
                pages.pageStyles.remove(view)?.let(::restoreHeroPage)
            }
        }
        val observer = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = restyleAttachedHeroPages(host, pages)
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = restyleAttachedHeroPages(host, pages)
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) =
                restyleAttachedHeroPages(host, pages)
        }
        pages.attachListener = listener
        pages.adapterObserver = observer
        pages.adapter = recycler.adapter
        recycler.addOnChildAttachStateChangeListener(listener)
        pages.adapter?.registerAdapterDataObserver(observer)
        headerState.heroPages = pages
        restyleAttachedHeroPages(host, pages)
    }

    private fun restyleAttachedHeroPages(host: AppCompatActivity, pages: HeroPagesState) {
        pages.recycler.post {
            for (index in 0 until pages.recycler.childCount) {
                styleHeroPage(host, pages, pages.recycler.getChildAt(index))
            }
        }
    }

    private fun styleHeroPage(host: AppCompatActivity, pages: HeroPagesState, page: View) {
        if (page.parent == null || !page.isAttachedToWindow) return
        val imageId = host.resources.getIdentifier("home_scroll_preview", "id", host.packageName)
        val image = (if (imageId != 0) page.findViewById<ImageView>(imageId) else null) ?: return
        val titleId = host.resources.getIdentifier("home_scroll_preview_title", "id", host.packageName)
        val title = (if (titleId != 0) page.findViewById<TextView>(titleId) else null) ?: return
        val tagsId = host.resources.getIdentifier("home_scroll_preview_tags", "id", host.packageName)
        val tags = (if (tagsId != 0) page.findViewById<TextView>(tagsId) else null) ?: return
        val topShadowId = host.resources.getIdentifier("title_shadow_top", "id", host.packageName)
        val topShadow = if (topShadowId != 0) page.findViewById<View>(topShadowId) else null
        val bottomShadowId = host.resources.getIdentifier("title_shadow", "id", host.packageName)
        val bottomShadow = if (bottomShadowId != 0) page.findViewById<View>(bottomShadowId) else null
        val sourceMetadata = title.parent as? LinearLayout ?: return
        val pageContainer = page as? FrameLayout ?: return
        val existing = pages.pageStyles[page]
        if (existing != null) {
            syncHeroMetadata(existing)
            return
        }

        val overlay = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(
                dp(host, HERO_METADATA_HORIZONTAL_DP), dp(host, HERO_METADATA_VERTICAL_DP),
                dp(host, HERO_METADATA_HORIZONTAL_DP), dp(host, HERO_METADATA_VERTICAL_DP)
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            )
        }
        val overlayTitle = TextView(host).apply {
            setTextColor(TUI_BRIGHT)
            textSize = HERO_TITLE_SIZE_SP
            typeface = tuiTypefaceMedium
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val overlayTags = TextView(host).apply {
            setTextColor(TUI_DIM)
            textSize = HERO_LABEL_SIZE_SP
            typeface = tuiTypefaceMedium
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(host, HERO_TITLE_TAG_GAP_DP) }
        }
        overlay.addView(overlayTitle)
        overlay.addView(overlayTags)
        val state = HeroPageStyleState(
            page = page,
            originalPageBackground = page.background,
            image = image,
            originalImageScaleType = image.scaleType,
            originalImageBackground = image.background,
            originalImageClipToOutline = image.clipToOutline,
            topShadow = topShadow,
            originalTopShadowVisibility = topShadow?.visibility,
            originalTopShadowBackground = topShadow?.background,
            originalTopShadowLayoutParams = copyLayoutParams(topShadow?.layoutParams),
            bottomShadow = bottomShadow,
            originalBottomShadowBackground = bottomShadow?.background,
            originalBottomShadowVisibility = bottomShadow?.visibility,
            originalBottomShadowLayoutParams = copyLayoutParams(bottomShadow?.layoutParams),
            sourceMetadata = sourceMetadata,
            originalSourceMetadataVisibility = sourceMetadata.visibility,
            overlay = overlay,
            overlayTitle = overlayTitle,
            overlayTags = overlayTags,
            titleSource = title,
            tagsSource = tags
        )
        pages.pageStyles[page] = state
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                syncHeroMetadata(state)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        state.sourceTextWatcher = textWatcher
        title.addTextChangedListener(textWatcher)
        tags.addTextChangedListener(textWatcher)

        page.setBackgroundColor(TUI_BG)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.background = GradientDrawable().apply { cornerRadius = 0f; setColor(Color.TRANSPARENT) }
        image.clipToOutline = false
        sourceMetadata.visibility = View.GONE
        topShadow?.visibility = View.GONE
        bottomShadow?.let { shadow ->
            shadow.visibility = View.VISIBLE
            shadow.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.FILL
            )
            shadow.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    Color.argb(140, 0, 0, 0),
                    Color.argb(235, 0, 0, 0)
                )
            ).apply { cornerRadius = 0f }
        }
        pageContainer.addView(overlay)
        syncHeroMetadata(state)
    }

    private fun syncHeroMetadata(state: HeroPageStyleState) {
        state.overlayTitle.text = state.titleSource.text
        state.overlayTags.text = state.tagsSource.text
        state.overlayTitle.visibility = if (state.overlayTitle.text.isNullOrBlank()) View.GONE else View.VISIBLE
        state.overlayTags.visibility = if (state.overlayTags.text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun cleanupHeroPages(pages: HeroPagesState) {
        pages.attachListener?.let(pages.recycler::removeOnChildAttachStateChangeListener)
        pages.adapter?.let { adapter -> pages.adapterObserver?.let(adapter::unregisterAdapterDataObserver) }
        pages.pageStyles.values.toList().forEach(::restoreHeroPage)
        pages.pageStyles.clear()
    }

    private fun restoreHeroPage(state: HeroPageStyleState) {
        state.sourceTextWatcher?.let { watcher ->
            state.titleSource.removeTextChangedListener(watcher)
            state.tagsSource.removeTextChangedListener(watcher)
        }
        (state.overlay.parent as? ViewGroup)?.removeView(state.overlay)
        state.page.background = state.originalPageBackground
        state.image.scaleType = state.originalImageScaleType
        state.image.background = state.originalImageBackground
        state.image.clipToOutline = state.originalImageClipToOutline
        state.topShadow?.let { shadow ->
            state.originalTopShadowVisibility?.let { shadow.visibility = it }
            shadow.background = state.originalTopShadowBackground
            state.originalTopShadowLayoutParams?.let { shadow.layoutParams = it }
        }
        state.bottomShadow?.let { shadow ->
            state.originalBottomShadowVisibility?.let { shadow.visibility = it }
            shadow.background = state.originalBottomShadowBackground
            state.originalBottomShadowLayoutParams?.let { shadow.layoutParams = it }
        }
        state.sourceMetadata.visibility = state.originalSourceMetadataVisibility
    }

    private fun tuiRipple(): Drawable = RippleDrawable(
        ColorStateList.valueOf((TUI_ACCENT and 0x00FFFFFF) or 0x55000000),
        ColorDrawable(Color.TRANSPARENT),
        GradientDrawable().apply { setColor(Color.WHITE) }
    )

    private fun attachHomepageNameWatcher(
        host: AppCompatActivity,
        homeFragment: Fragment,
        state: HomeHeaderState
    ) {
        if (state.homeApiFab != null) return
        val fabId = host.resources.getIdentifier("home_api_fab", "id", host.packageName)
        val fab = homeFragment.view?.findViewById<TextView>(fabId) ?: return
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateSelectedProvider(host, homeFragment, state, getHomeApiName(homeFragment))
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        state.homeApiFab = fab
        state.homeApiFabWatcher = watcher
        fab.addTextChangedListener(watcher)
    }

    private fun updateSelectedProvider(
        host: AppCompatActivity,
        homeFragment: Fragment,
        state: HomeHeaderState,
        apiName: String?
    ) {
        val api = APIHolder.getApiFromNameNull(apiName)
        val isNoProvider = apiName.equals("None", ignoreCase = true) || api?.name.equals("None", ignoreCase = true)
        val displayName = api?.let(::getExtensionName) ?: apiName?.removeSuffix("Provider") ?: "Pilih provider"
        state.brandName.text = if (isNoProvider) "" else displayName
        state.brandName.visibility = if (isNoProvider) View.GONE else View.VISIBLE
        (state.brandIcon.parent as? View)?.visibility = if (isNoProvider) View.GONE else View.VISIBLE
        if (isNoProvider) {
            state.iconJob?.cancel()
            state.brandIcon.setImageDrawable(null)
            state.brandIcon.tag = null
            return
        }
        state.brandFallback.text = displayName.trim().firstOrNull()?.uppercase() ?: "?"
        state.brandIcon.setImageDrawable(api?.let { getProviderIcon(host, it) })
        state.brandIcon.tag = api?.sourcePlugin
        val sourcePlugin = api?.sourcePlugin ?: return
        val sourceTag = sourcePlugin
        synchronized(providerIconUrls) {
            if (providerIconUrls.containsKey(sourceTag)) {
                providerIconUrls[sourceTag]?.let { url -> state.brandIcon.loadImage(url) }
                return
            }
            if (!providerIconRequests.add(sourceTag)) return
        }

        state.iconJob?.cancel()
        state.iconJob = homeFragment.viewLifecycleOwner.lifecycleScope.launch {
            val iconUrl = try {
                withContext(Dispatchers.IO) { findProviderIconUrl(sourceTag) }
            } finally {
                synchronized(providerIconUrls) { providerIconRequests.remove(sourceTag) }
            }
            synchronized(providerIconUrls) { providerIconUrls[sourceTag] = iconUrl }
            if (state.brandIcon.tag == sourceTag && !iconUrl.isNullOrBlank()) {
                state.brandIcon.loadImage(iconUrl)
            }
        }
    }

    private suspend fun findProviderIconUrl(sourcePlugin: String): String? {
        val installed = (getPluginsOnline().toList() + getPluginsLocal().toList())
            .firstOrNull { it.filePath == sourcePlugin }
            ?: return null
        val repositories: List<RepositoryData> = (
            RepositoryManager.getRepositories().toList() + PREBUILT_REPOSITORIES.toList()
        ).distinctBy { it.url }
        for (repository in repositories) {
            val matchingPlugin = try {
                val candidates = RepositoryManager.getRepoPlugins(repository)
                    ?.filter { it.plugin.internalName == installed.internalName }
                    .orEmpty()
                candidates.firstOrNull { it.plugin.url == installed.url }?.plugin
                    ?: candidates.firstOrNull()?.plugin
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            } ?: continue
            return matchingPlugin.iconUrl
                ?.replace("%size%", "64")
                ?.replace("%exact_size%", "64")
                ?.takeIf { it.startsWith("https://", ignoreCase = true) }
        }
        return null
    }

    private fun watchHomeHeaderLifecycle(account: ViewGroup, state: HomeHeaderState, fragment: Fragment) {
        val lifecycle = fragment.viewLifecycleOwner.lifecycle
        val cleanup = object : LifecycleEventObserver {
            override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_DESTROY) {
                    if (homeHeaderStates[account] === state) {
                        runCatching { cleanupHomeHeaderResources(state) }
                        homeHeaderStates.remove(account)
                    }
                    lifecycle.removeObserver(this)
                }
            }
        }
        lifecycle.addObserver(cleanup)
    }

    private fun restoreHomeHeader(account: ViewGroup, state: HomeHeaderState) {
        cleanupHomeHeaderResources(state)
        state.heroFrame.layoutParams = state.originalHeroFrameLayoutParams
        state.heroFrame.background = state.originalHeroFrameBackground
        state.heroFrame.foreground = state.originalHeroFrameForeground
        state.titleHolder?.let { title -> state.originalTitleHolderVisibility?.let { title.visibility = it } }
        state.selectorFab?.let { fab -> state.originalSelectorFabVisibility?.let { fab.visibility = it } }
        state.headerView.background = state.originalHeaderBackground
        state.searchButton?.background = state.originalSearchButtonBackground
        state.originalSearchButtonLayoutParams?.let { params ->
            state.searchButton?.layoutParams = params
        }
        (state.searchButton as? ImageView)?.let { button ->
            state.originalSearchButtonPadding?.let { button.setPadding(it[0], it[1], it[2], it[3]) }
            button.imageTintList = state.originalSearchButtonTint
            state.originalSearchButtonScaleType?.let { button.scaleType = it }
        }
        state.searchIcon?.let { icon ->
            state.originalSearchIconLayoutParams?.let { icon.layoutParams = it }
            state.originalSearchIconPadding?.let { icon.setPadding(it[0], it[1], it[2], it[3]) }
            icon.imageTintList = state.originalSearchIconTint
            state.originalSearchIconScaleType?.let { icon.scaleType = it }
        }
        account.removeAllViews()
        state.accountChildren.forEachIndexed { index, child ->
            val params = state.accountChildLayoutParams[index]
            if (params == null) account.addView(child, index.coerceAtMost(account.childCount))
            else account.addView(child, index.coerceAtMost(account.childCount), params)
        }
        state.accountLayoutParams?.let { account.layoutParams = it }
        account.setPadding(
            state.accountPadding[0], state.accountPadding[1],
            state.accountPadding[2], state.accountPadding[3]
        )
        (account as? LinearLayout)?.gravity = state.accountGravity
        account.visibility = state.accountVisibility
        state.search.visibility = state.searchVisibility

        state.parent.removeAllViews()
        state.originalChildren.forEachIndexed { index, child ->
            val params = state.originalChildLayoutParams[index]
            if (params == null) state.parent.addView(child, index.coerceAtMost(state.parent.childCount))
            else state.parent.addView(child, index.coerceAtMost(state.parent.childCount), params)
        }
        (state.topBar.parent as? ViewGroup)?.removeView(state.topBar)
        state.topBar.setPadding(
            state.originalTopBarPadding[0], state.originalTopBarPadding[1],
            state.originalTopBarPadding[2], state.originalTopBarPadding[3]
        )
        state.topBar.background = state.originalTopBarBackground
        val topBarIndex = state.originalTopBarIndex.coerceIn(0, state.originalTopBarParent.childCount)
        val topBarParams = state.originalTopBarLayoutParams
        if (topBarParams == null) state.originalTopBarParent.addView(state.topBar, topBarIndex)
        else state.originalTopBarParent.addView(state.topBar, topBarIndex, topBarParams)
    }

    private fun cleanupHomeHeaderResources(state: HomeHeaderState) {
        state.heroPages?.let(::cleanupHeroPages)
        state.heroPages = null
        state.iconJob?.cancel()
        state.homeApiFab?.removeTextChangedListener(state.homeApiFabWatcher)
        state.search.setOnSearchClickListener(null)
        state.search.setOnCloseListener(null)
        state.heroSizeListener?.let { listener ->
            state.heroMeasureHost?.removeOnLayoutChangeListener(listener)
        }
    }

    private fun detachFromParent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun getExtensionName(api: MainAPI) = api.name.removeSuffix("Provider")

    private fun copyLayoutParams(params: ViewGroup.LayoutParams?): ViewGroup.LayoutParams? = when (params) {
        is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(params)
        is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(params)
        is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(params)
        null -> null
        else -> ViewGroup.LayoutParams(params)
    }

    private fun getProviderIcon(context: Context, api: MainAPI) = runCatching {
        val plugin = api.sourcePlugin?.let { PluginManager.plugins[it] as? Plugin }
        val pluginResources = plugin?.resources
        if (pluginResources != null) {
            val packageNames = listOf(api.javaClass.packageName, plugin.javaClass.packageName, "com.example").distinct()
            val iconNames = listOf("ic_launcher", "extension_icon", "plugin_icon", "icon", "logo")

            // Extension resource package names can differ from the provider's Kotlin package.
            // Resolve drawable IDs from the extension's generated R class first, then try names.
            val drawableClass = runCatching {
                plugin.javaClass.classLoader?.loadClass("${plugin.javaClass.packageName}.R\$drawable")
            }.getOrNull()
            drawableClass?.fields
                ?.firstOrNull { field -> field.name in iconNames }
                ?.let { field ->
                    val id = field.getInt(null)
                    if (id != 0) return@runCatching pluginResources.getDrawable(id, context.theme)
                }

            for (packageName in packageNames) {
                for (type in listOf("mipmap", "drawable")) {
                    for (name in iconNames) {
                        val iconId = pluginResources.getIdentifier(name, type, packageName)
                        if (iconId != 0) return@runCatching pluginResources.getDrawable(iconId, context.theme)
                    }
                }
            }
        }
        null
    }.getOrNull()

    private fun getHomeApiName(homeFragment: Fragment): String? = runCatching {
        homeFragment.javaClass.getDeclaredField("currentApiName").apply { isAccessible = true }
            .get(homeFragment) as? String
    }.getOrNull()

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val LEGACY_SEARCH_BUTTON_TAG = "test_plugin_advanced_search_button"
        const val HEADER_SELECTOR_WEIGHT = 1f
        const val SEARCH_BUTTON_WIDTH_DP = 50
        const val SEARCH_BUTTON_HEIGHT_DP = 26
        const val SEARCH_CONTROL_WIDTH_DP = 30
        const val SEARCH_BUTTON_HORIZONTAL_PADDING_DP = 6
        const val SEARCH_BUTTON_VERTICAL_PADDING_DP = 4
        const val SEARCH_ICON_SIZE_DP = 18
        const val PROVIDER_ICON_SIZE_DP = 18
        const val HEADER_HORIZONTAL_INSET_DP = 14
        const val HEADER_VERTICAL_PADDING_DP = 10
        const val HERO_HORIZONTAL_INSET_DP = 14
        const val HERO_TOP_PADDING_DP = 4
        const val HERO_BOTTOM_PADDING_DP = 12
        const val HERO_ASPECT_WIDTH = 16f
        const val HERO_ASPECT_HEIGHT = 8.5f
        const val HERO_METADATA_HORIZONTAL_DP = 12
        const val HERO_METADATA_VERTICAL_DP = 10
        const val HERO_TITLE_TAG_GAP_DP = 6
        const val HERO_TITLE_SIZE_SP = 16f
        const val HERO_LABEL_SIZE_SP = 11f
        const val TUI_BG = 0xFF0A0A0C.toInt()
        const val TUI_LINE = 0xFF26262E.toInt()
        const val TUI_BRIGHT = 0xFFF4F4F6.toInt()
        const val TUI_DIM = 0xFF8A8A98.toInt()
        const val TUI_ACCENT = 0xFFEAB308.toInt()
    }

    private data class LayoutWatcher(
        val observer: ViewTreeObserver,
        val listener: ViewTreeObserver.OnGlobalLayoutListener
    )

    private class HeroPagesState(
        val recycler: RecyclerView,
        val pageStyles: MutableMap<View, HeroPageStyleState>
    ) {
        var attachListener: RecyclerView.OnChildAttachStateChangeListener? = null
        var adapterObserver: RecyclerView.AdapterDataObserver? = null
        var adapter: RecyclerView.Adapter<*>? = null
    }

    private data class HeroPageStyleState(
        val page: View,
        val originalPageBackground: Drawable?,
        val image: ImageView,
        val originalImageScaleType: ImageView.ScaleType,
        val originalImageBackground: Drawable?,
        val originalImageClipToOutline: Boolean,
        val topShadow: View?,
        val originalTopShadowVisibility: Int?,
        val originalTopShadowBackground: Drawable?,
        val originalTopShadowLayoutParams: ViewGroup.LayoutParams?,
        val bottomShadow: View?,
        val originalBottomShadowBackground: Drawable?,
        val originalBottomShadowVisibility: Int?,
        val originalBottomShadowLayoutParams: ViewGroup.LayoutParams?,
        val sourceMetadata: LinearLayout,
        val originalSourceMetadataVisibility: Int,
        val overlay: LinearLayout,
        val overlayTitle: TextView,
        val overlayTags: TextView,
        val titleSource: TextView,
        val tagsSource: TextView,
        var sourceTextWatcher: TextWatcher? = null
    )

    private data class HomeHeaderState(
        val parent: ViewGroup,
        val search: androidx.appcompat.widget.SearchView,
        val originalChildren: List<View>,
        val originalChildLayoutParams: List<ViewGroup.LayoutParams?>,
        val searchIndex: Int,
        val accountIndex: Int,
        val accountChildren: List<View>,
        val accountChildLayoutParams: List<ViewGroup.LayoutParams?>,
        val accountLayoutParams: ViewGroup.LayoutParams?,
        val accountVisibility: Int,
        val accountPadding: IntArray,
        val accountGravity: Int,
        val searchVisibility: Int,
        val headerView: ViewGroup,
        val originalHeaderBackground: Drawable?,
        val topBar: LinearLayout,
        val originalTopBarParent: ViewGroup,
        val originalTopBarIndex: Int,
        val originalTopBarLayoutParams: ViewGroup.LayoutParams?,
        val originalTopBarPadding: IntArray,
        val originalTopBarBackground: Drawable?,
        val heroFrame: FrameLayout,
        val originalHeroFrameLayoutParams: ViewGroup.LayoutParams?,
        val originalHeroFrameBackground: Drawable?,
        val originalHeroFrameForeground: Drawable?,
        val titleHolder: View?,
        val originalTitleHolderVisibility: Int?,
        val selectorFab: View?,
        val originalSelectorFabVisibility: Int?,
        val searchIcon: ImageView?,
        val originalSearchIconLayoutParams: ViewGroup.LayoutParams?,
        val originalSearchIconPadding: IntArray?,
        val originalSearchIconTint: ColorStateList?,
        val searchButton: View?,
        val originalSearchButtonLayoutParams: ViewGroup.LayoutParams?,
        val originalSearchButtonBackground: Drawable?,
        val originalSearchButtonPadding: IntArray?,
        val originalSearchButtonTint: ColorStateList?,
        val originalSearchButtonScaleType: ImageView.ScaleType?,
        val originalSearchIconScaleType: ImageView.ScaleType?,
        val brandIcon: ImageView,
        val brandFallback: TextView,
        val brandName: TextView,
        var brandContainer: ViewGroup? = null,
        var homeApiFab: TextView? = null,
        var homeApiFabWatcher: TextWatcher? = null,
        var heroMeasureHost: View? = null,
        var heroSizeListener: View.OnLayoutChangeListener? = null,
        var heroPages: HeroPagesState? = null,
        var iconJob: Job? = null
    )
}
