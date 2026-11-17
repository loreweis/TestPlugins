package com.example

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.PluginManager
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
    private val homeHeaderStates = WeakHashMap<ViewGroup, HomeHeaderState>()

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
        if (installProviderStrip(host, fragment, homeRoot)) return

        layoutWatchers[homeRoot]?.let { existing ->
            if (existing.observer.isAlive) return
            layoutWatchers.remove(homeRoot)
        }

        val observer = homeRoot.viewTreeObserver
        if (!observer.isAlive) return

        val watcher = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (installProviderStrip(host, fragment, homeRoot)) stopWatchingHomeLayout(homeRoot)
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
                    installProviderStrip(host, fragment, child)
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

    private fun installProviderStrip(
        host: AppCompatActivity,
        homeFragment: Fragment,
        headerRoot: View
    ): Boolean {
        val accountId = host.resources.getIdentifier("home_head_profile_padding", "id", host.packageName)
        val searchId = host.resources.getIdentifier("home_search", "id", host.packageName)
        val account = (if (accountId != 0) headerRoot.findViewById<ViewGroup>(accountId) else null)
            ?: return false
        val search = (if (searchId != 0) headerRoot.findViewById<androidx.appcompat.widget.SearchView>(searchId) else null)
            ?: return false
        val parent = account.parent as? ViewGroup ?: return false
        if (search.parent !== parent || parent !is LinearLayout) return false
        (headerRoot as? ViewGroup)?.findViewWithTag<View>(LEGACY_SEARCH_BUTTON_TAG)?.let { button ->
            (button.parent as? ViewGroup)?.removeView(button)
        }

        val providers = APIHolder.apis
            .filter { it.hasMainPage && !it.sourcePlugin.isNullOrBlank() }
            .distinctBy { it.sourcePlugin }
            .sortedBy { it.name.lowercase() }
        if (providers.isEmpty()) return false

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
                searchVisibility = search.visibility
            )
            homeHeaderStates[account] = state
            watchHomeHeaderLifecycle(account, state, homeFragment)
            state
        }

        account.removeAllViews()
        (account as? LinearLayout)?.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        account.setPadding(dp(host, 10), 0, dp(host, 6), 0)
        account.visibility = View.VISIBLE
        val orderedChildren = headerState.originalChildren.toMutableList().apply {
            this[headerState.searchIndex] = account
            this[headerState.accountIndex] = search
        }
        val scroll = HorizontalScrollView(host).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val selectedApiName = getHomeApiName(homeFragment)
        providers.forEach { api ->
            val item = createProviderItem(host, homeFragment, api, api.name == selectedApiName)
            row.addView(item)
            if (api.name == selectedApiName) scroll.post { scroll.smoothScrollTo(item.left, 0) }
        }
        scroll.addView(row, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        account.addView(scroll)

        parent.removeAllViews()
        orderedChildren.forEachIndexed { index, child ->
            val originalIndex = headerState.originalChildren.indexOf(child)
            val params = when (child) {
                account -> LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, HEADER_SELECTOR_WEIGHT)
                search -> LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, HEADER_SEARCH_WEIGHT)
                else -> copyLayoutParams(headerState.originalChildLayoutParams[originalIndex])
            }
            if (params == null) parent.addView(child, index)
            else parent.addView(child, index, params)
        }
        search.setOnSearchClickListener {
            search.post {
                account.visibility = View.GONE
                search.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
        search.setOnCloseListener {
            account.visibility = View.VISIBLE
            account.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                HEADER_SELECTOR_WEIGHT
            )
            search.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                HEADER_SEARCH_WEIGHT
            )
            false
        }
        return true
    }

    private fun watchHomeHeaderLifecycle(account: ViewGroup, state: HomeHeaderState, fragment: Fragment) {
        val lifecycle = fragment.viewLifecycleOwner.lifecycle
        val cleanup = object : LifecycleEventObserver {
            override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_DESTROY) {
                    if (homeHeaderStates[account] === state) homeHeaderStates.remove(account)
                    lifecycle.removeObserver(this)
                }
            }
        }
        lifecycle.addObserver(cleanup)
    }

    private fun restoreHomeHeader(account: ViewGroup, state: HomeHeaderState) {
        state.search.setOnSearchClickListener(null)
        state.search.setOnCloseListener(null)
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
    }

    private fun createProviderItem(
        host: AppCompatActivity,
        homeFragment: Fragment,
        api: MainAPI,
        selected: Boolean
    ): View {
        val item = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = api.name
            setPadding(dp(host, 10), dp(host, 5), dp(host, 12), dp(host, 5))
            background = GradientDrawable().apply {
                setColor(if (selected) 0x332196F3 else Color.TRANSPARENT)
                cornerRadius = dp(host, 10).toFloat()
                if (selected) setStroke(dp(host, 1), 0xFF64B5F6.toInt())
            }
            setOnClickListener {
                rowSelection(this, host)
                selectHomepageProvider(host, homeFragment, api)
            }
        }
        val providerDrawable = getProviderIcon(host, api)
        val icon: View = if (providerDrawable != null) {
            ImageView(host).apply {
                setImageDrawable(providerDrawable)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                layoutParams = LinearLayout.LayoutParams(dp(host, 28), dp(host, 28)).apply {
                    marginEnd = dp(host, 7)
                }
            }
        } else {
            TextView(host).apply {
                text = getExtensionName(api).trim().firstOrNull()?.uppercase() ?: "?"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(resolveTextColor(host))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x332196F3)
                }
                layoutParams = LinearLayout.LayoutParams(dp(host, 28), dp(host, 28)).apply {
                    marginEnd = dp(host, 7)
                }
            }
        }
        val title = TextView(host).apply {
            text = getExtensionName(api)
            textSize = 12f
            maxLines = 1
            maxWidth = dp(host, 150)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(resolveTextColor(host))
            setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
        item.addView(icon)
        item.addView(title)
        return item
    }

    private fun rowSelection(item: View, context: Context) {
        val row = item.parent as? ViewGroup ?: return
        for (index in 0 until row.childCount) {
            val child = row.getChildAt(index)
            val selected = child === item
            child.background = GradientDrawable().apply {
                setColor(if (selected) 0x332196F3 else Color.TRANSPARENT)
                cornerRadius = dp(context, 10).toFloat()
                if (selected) setStroke(dp(context, 1), 0xFF64B5F6.toInt())
            }
            (child as? LinearLayout)?.let { container ->
                for (i in 0 until container.childCount) {
                    (container.getChildAt(i) as? TextView)?.setTypeface(
                        null,
                        if (selected) Typeface.BOLD else Typeface.NORMAL
                    )
                }
            }
        }
    }

    private fun getExtensionName(api: MainAPI) = api.name.removeSuffix("Provider")

    private fun copyLayoutParams(params: ViewGroup.LayoutParams?): ViewGroup.LayoutParams? = when (params) {
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

    private fun resolveTextColor(context: Context): Int {
        val values = android.util.TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.textColorPrimary, values, true)) {
            if (values.resourceId != 0) {
                androidx.core.content.ContextCompat.getColorStateList(context, values.resourceId)?.defaultColor ?: Color.WHITE
            } else values.data
        } else Color.WHITE
    }

    private fun getHomeApiName(homeFragment: Fragment): String? = runCatching {
        homeFragment.javaClass.getDeclaredField("currentApiName").apply { isAccessible = true }
            .get(homeFragment) as? String
    }.getOrNull()

    private fun selectHomepageProvider(host: AppCompatActivity, homeFragment: Fragment, api: MainAPI) {
        runCatching {
            val viewModelClass = Class.forName("com.lagradost.cloudstream3.ui.home.HomeViewModel")
                .asSubclass(ViewModel::class.java)
            val viewModel = ViewModelProvider(host).get(viewModelClass)
            val method = viewModel.javaClass.methods.firstOrNull {
                it.name == "loadAndCancel" && it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == String::class.java
            } ?: error("Home provider selector method not found")
            method.invoke(viewModel, api.name, true, true)
        }.onFailure {
            android.widget.Toast.makeText(host, "Tidak bisa membuka provider ${api.name}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val LEGACY_SEARCH_BUTTON_TAG = "test_plugin_advanced_search_button"
        const val HEADER_SELECTOR_WEIGHT = 1f
        const val HEADER_SEARCH_WEIGHT = 0.42f
    }

    private data class LayoutWatcher(
        val observer: ViewTreeObserver,
        val listener: ViewTreeObserver.OnGlobalLayoutListener
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
        val searchVisibility: Int
    )
}
