package com.example

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.navigation.NavigationBarView
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ExamplePlugin : Plugin() {
    private var activity: AppCompatActivity? = null

    override fun load(context: Context) {
        val act = getAppCompatActivity(context)
        activity = act
        registerMainAPI(ExampleProvider())

        openSettings = { ctx ->
            val hostAct = getAppCompatActivity(ctx) ?: activity
            hostAct?.let { BlankFragment(this).show(it.supportFragmentManager, "TestControlCenter") }
        }

        act?.let { host ->
            host.runOnUiThread {
                setupHomeInjection(host)
                installHomeLabMenuItem(host)
            }
        }
    }

    private fun getAppCompatActivity(ctx: Context?): AppCompatActivity? {
        var current = ctx
        while (current is ContextWrapper) {
            if (current is AppCompatActivity) return current
            current = current.baseContext
        }
        return current as? AppCompatActivity
    }

    private fun setupHomeInjection(act: AppCompatActivity) {
        val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                if (isHomeFragment(f)) v.post { injectHomeTools(act, v) }
            }

            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                if (isHomeFragment(f)) f.view?.post { injectHomeTools(act, f.requireView()) }
            }
        }
        act.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
        checkAndInjectExisting(act, act.supportFragmentManager)
    }

    private fun isHomeFragment(fragment: Fragment) = fragment.javaClass.simpleName.contains("Home", ignoreCase = true)

    private fun installHomeLabMenuItem(act: AppCompatActivity, attempt: Int = 0) {
        val railId = act.resources.getIdentifier("nav_rail_view", "id", act.packageName)
        val bottomId = act.resources.getIdentifier("nav_view", "id", act.packageName)
        val navBars = listOfNotNull(
            if (railId != 0) act.findViewById<NavigationBarView>(railId) else null,
            if (bottomId != 0) act.findViewById<NavigationBarView>(bottomId) else null,
        )
        val downloadsId = act.resources.getIdentifier("navigation_downloads", "id", act.packageName)
        val items = navBars.mapNotNull { bar -> bar.menu.findItem(downloadsId) }
        if (items.isEmpty()) {
            if (attempt < MENU_INSTALL_MAX_ATTEMPTS) {
                act.window.decorView.postDelayed({ installHomeLabMenuItem(act, attempt + 1) }, MENU_INSTALL_RETRY_MS)
            }
            return
        }

        items.forEach { item ->
            item.title = "Home Lab"
            getExtensionIcon(act)?.let(item::setIcon)
            item.isCheckable = false
            item.setOnMenuItemClickListener {
                openHomeLab(act)
                true
            }
        }
    }

    private fun getExtensionIcon(context: Context) = runCatching {
        val extResources = resources
        val iconId = extResources?.getIdentifier("ic_extension_home", "drawable", "com.example") ?: 0
        if (iconId != 0) extResources?.getDrawable(iconId, context.theme)
        else context.getDrawable(android.R.drawable.btn_star_big_on)
    }.getOrNull()

    private fun openHomeLab(act: AppCompatActivity) {
        val homeRoot = findHomeRoot(act.supportFragmentManager)
        if (homeRoot != null) {
            homeRoot.post { launchTakeoverFromRoot(act, homeRoot) }
            return
        }

        val railId = act.resources.getIdentifier("nav_rail_view", "id", act.packageName)
        val bottomId = act.resources.getIdentifier("nav_view", "id", act.packageName)
        val rail = if (railId != 0) act.findViewById<NavigationBarView>(railId) else null
        val bottom = if (bottomId != 0) act.findViewById<NavigationBarView>(bottomId) else null
        val navBar = listOfNotNull(rail, bottom).firstOrNull { it.isShown }
        val homeItemId = act.resources.getIdentifier("navigation_home", "id", act.packageName)
        if (navBar == null || homeItemId == 0 || navBar.menu.findItem(homeItemId) == null) {
            Toast.makeText(act, "Home CloudStream tidak ditemukan", Toast.LENGTH_SHORT).show()
            return
        }

        navBar.menu.performIdentifierAction(homeItemId, 0)
        navBar.postDelayed({
            val root = findHomeRoot(act.supportFragmentManager)
            if (root != null) launchTakeoverFromRoot(act, root)
            else Toast.makeText(act, "Home sedang dimuat, tekan ikon lagi sebentar", Toast.LENGTH_SHORT).show()
        }, HOME_NAVIGATION_DELAY_MS)
    }

    private fun findHomeRoot(manager: FragmentManager): View? {
        manager.fragments.forEach { fragment ->
            if (isHomeFragment(fragment) && fragment.isVisible) {
                val view = fragment.view ?: return@forEach
                val rootId = view.resources.getIdentifier("home_root", "id", view.context.packageName)
                return if (rootId != 0) view.findViewById(rootId) else view
            }
            findHomeRoot(fragment.childFragmentManager)?.let { return it }
        }
        return null
    }

    private fun launchTakeoverFromRoot(act: AppCompatActivity, rootView: View) {
        val root = rootView as? ViewGroup ?: return
        val recyclerId = act.resources.getIdentifier("home_master_recycler", "id", act.packageName)
        val recycler = if (recyclerId != 0) root.findViewById<View>(recyclerId) else null
        if (recycler == null) {
            Toast.makeText(act, "Feed Home CloudStream tidak ditemukan", Toast.LENGTH_SHORT).show()
            return
        }
        showTakeover(act, root, recycler)
    }

    private fun checkAndInjectExisting(act: AppCompatActivity, manager: FragmentManager) {
        manager.fragments.forEach { fragment ->
            if (isHomeFragment(fragment)) fragment.view?.post { injectHomeTools(act, fragment.requireView()) }
            checkAndInjectExisting(act, fragment.childFragmentManager)
        }
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun color(hex: String) = Color.parseColor(hex)

    private fun rounded(context: Context, fill: String, stroke: String? = null, radius: Int = 14) =
        GradientDrawable().apply {
            setColor(color(fill))
            cornerRadius = dp(context, radius).toFloat()
            stroke?.let { setStroke(dp(context, 1), color(it)) }
        }

    private fun injectHomeTools(act: AppCompatActivity, rootView: View) {
        try {
            val rootId = act.resources.getIdentifier("home_root", "id", act.packageName)
            val homeRoot = (if (rootId != 0) rootView.findViewById<View>(rootId) else null) as? ViewGroup ?: rootView as? ViewGroup ?: return
            if (homeRoot.findViewWithTag<View>(TOOLS_TAG) != null || homeRoot.findViewWithTag<View>(TAKEOVER_TAG) != null) return

            val recyclerId = act.resources.getIdentifier("home_master_recycler", "id", act.packageName)
            val recycler = if (recyclerId != 0) rootView.findViewById<View>(recyclerId) else null
            val tools = LinearLayout(act).apply {
                tag = TOOLS_TAG
                orientation = LinearLayout.VERTICAL
                setPadding(dp(act, 14), dp(act, 12), dp(act, 14), dp(act, 12))
                background = rounded(act, "#161B22", "#388BFD")
            }

            tools.addView(TextView(act).apply {
                text = "⚡ TESTPLUGIN · HOME LAB"
                textSize = 14f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(color("#58A6FF"))
            })
            tools.addView(TextView(act).apply {
                val used = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)
                text = "Runtime UI injection  ·  PID ${Process.myPid()}  ·  Heap ${used} MB"
                textSize = 11f
                setTextColor(color("#8B949E"))
                setPadding(0, dp(act, 3), 0, dp(act, 8))
            })
            tools.addView(actionButton(act, "Take over Home", "#238636") {
                if (recycler == null) {
                    Toast.makeText(act, "Recycler Home CloudStream tidak ditemukan", Toast.LENGTH_SHORT).show()
                } else {
                    showTakeover(act, homeRoot, recycler)
                }
            })
            tools.addView(actionButton(act, "Open runtime console", "#21262D") {
                BlankFragment(this).show(act.supportFragmentManager, "TestControlCenter")
            }.apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(act, 6)
            })

            val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
                setMargins(dp(act, 12), dp(act, 10), dp(act, 12), 0)
            }
            homeRoot.addView(tools, params)
            tools.post {
                if (recycler != null && recycler.parent != null) {
                    recycler.setPadding(recycler.paddingLeft, tools.height + dp(act, 18), recycler.paddingRight, recycler.paddingBottom)
                }
            }
        } catch (_: Throwable) {
            // CloudStream's internal Home layout can change between app versions.
        }
    }

    private fun actionButton(context: Context, label: String, fill: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(Color.WHITE)
        background = rounded(context, fill, radius = 9)
        setPadding(dp(context, 12), dp(context, 7), dp(context, 12), dp(context, 7))
        setOnClickListener { onClick() }
    }

    private fun showTakeover(act: AppCompatActivity, root: ViewGroup, recycler: View) {
        if (root.findViewWithTag<View>(TAKEOVER_TAG) != null) return
        val state = listOf(recycler, findHostView(act, root, "home_api_fab"), findHostView(act, root, "home_random"))
            .filterNotNull().distinct().associateWith { it.visibility }
        recycler.visibility = View.GONE
        state.keys.filter { it !== recycler }.forEach { it.visibility = View.GONE }

        val overlay = FrameLayout(act).apply {
            tag = TAKEOVER_TAG
            setBackgroundColor(color("#0D1117"))
            isFocusableInTouchMode = true
        }
        val scroller = ScrollView(act).apply { isFillViewport = true; clipToPadding = false }
        val page = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(act, 20), dp(act, 20), dp(act, 20), dp(act, 28))
        }
        scroller.addView(page)
        overlay.addView(scroller, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        page.addView(TextView(act).apply {
            text = "TESTPLUGIN / HOME LAB"
            textSize = 11f
            letterSpacing = .12f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(color("#58A6FF"))
        })
        page.addView(TextView(act).apply {
            text = "Your Home.\nYour rules."
            textSize = 34f
            setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
            setTextColor(color("#F0F6FC"))
            setPadding(0, dp(act, 8), 0, dp(act, 5))
        })
        page.addView(TextView(act).apply {
            text = "A custom surface rendered inside the CloudStream Home fragment. Switch views, interact with cards, then restore the original feed."
            textSize = 13f
            setTextColor(color("#8B949E"))
            setPadding(0, 0, 0, dp(act, 18))
        })

        val status = TextView(act).apply {
            text = "●  CUSTOM HOME ACTIVE     ·     STOCK FEED HIDDEN"
            textSize = 10f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(color("#7EE787"))
            setPadding(dp(act, 12), dp(act, 10), dp(act, 12), dp(act, 10))
            background = rounded(act, "#12251A", "#238636", 9)
        }
        page.addView(status, marginParams(act, bottom = 16))

        val tabBar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        page.addView(tabBar, marginParams(act, bottom = 14))
        val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        page.addView(content)

        val tabs = listOf("Discover", "Continue", "Library")
        fun render(tab: String) {
            tabBar.removeAllViews()
            tabs.forEach { name ->
                val tabButton = actionButton(act, name, if (name == tab) "#1F6FEB" else "#21262D") { render(name) }
                tabBar.addView(tabButton, LinearLayout.LayoutParams(0, dp(act, 44), 1f).apply { marginEnd = dp(act, 6) })
            }
            content.removeAllViews()
            val sectionTitle = when (tab) {
                "Continue" -> "Pick up where you left off"
                "Library" -> "Your collection"
                else -> "A home screen built by an extension"
            }
            content.addView(TextView(act).apply {
                text = sectionTitle
                textSize = 18f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(color("#F0F6FC"))
                setPadding(0, 0, 0, dp(act, 10))
            })
            val cards = when (tab) {
                "Continue" -> listOf("Resume a title", "Recently opened", "Next episode")
                "Library" -> listOf("Favorites", "Watch history", "Downloads")
                else -> listOf("Featured collection", "Because you watched anime", "New this week")
            }
            cards.forEachIndexed { index, title ->
                val card = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(act, 16), dp(act, 15), dp(act, 16), dp(act, 15))
                    background = rounded(act, if (index == 0) "#17243A" else "#161B22", if (index == 0) "#1F6FEB" else "#30363D")
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        status.text = "✓  SELECTED: ${title.uppercase()}"
                        Toast.makeText(act, "$title dipilih", Toast.LENGTH_SHORT).show()
                    }
                }
                card.addView(TextView(act).apply {
                    text = when (index) { 0 -> "✦   EDITOR'S PICK"; 1 -> "↗   PERSONALIZED"; else -> "＋   EXPLORE" }
                    textSize = 9f
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(color(if (index == 0) "#79C0FF" else "#8B949E"))
                })
                card.addView(TextView(act).apply {
                    text = title
                    textSize = 17f
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(color("#F0F6FC"))
                    setPadding(0, dp(act, 5), 0, dp(act, 3))
                })
                card.addView(TextView(act).apply {
                    text = "Tap this card to trigger an action in the extension UI."
                    textSize = 11f
                    setTextColor(color("#8B949E"))
                })
                content.addView(card, marginParams(act, bottom = 10))
            }
        }
        render("Discover")

        page.addView(TextView(act).apply {
            text = "THIS IS A REAL VIEW TREE"
            textSize = 10f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(color("#D29922"))
            setPadding(0, dp(act, 16), 0, dp(act, 5))
        })
        page.addView(TextView(act).apply {
            text = "The extension replaced the visible Home feed at runtime. Host navigation and other tabs remain owned by CloudStream."
            textSize = 11f
            setTextColor(color("#8B949E"))
            setPadding(0, 0, 0, dp(act, 16))
        })
        page.addView(actionButton(act, "Restore CloudStream Home", "#A52A2A") {
            root.removeView(overlay)
            state.forEach { (view, visibility) ->
                if (view.parent != null) view.visibility = visibility
            }
            Toast.makeText(act, "Home CloudStream dipulihkan", Toast.LENGTH_SHORT).show()
        })
    }

    private fun marginParams(context: Context, bottom: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(context, bottom) }

    private fun findHostView(act: AppCompatActivity, root: View, resourceName: String): View? {
        val id = act.resources.getIdentifier(resourceName, "id", act.packageName)
        return if (id != 0) root.findViewById(id) else null
    }

    private companion object {
        const val TOOLS_TAG = "test_plugin_home_tools"
        const val TAKEOVER_TAG = "test_plugin_home_takeover"
        const val HOME_NAVIGATION_DELAY_MS = 500L
        const val MENU_INSTALL_RETRY_MS = 250L
        const val MENU_INSTALL_MAX_ATTEMPTS = 20
    }
}
