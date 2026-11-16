package com.example

import android.app.Activity
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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ExamplePlugin: Plugin() {
    private var activity: AppCompatActivity? = null
    private var fabHidden = false

    override fun load(context: Context) {
        val act = getAppCompatActivity(context)
        activity = act

        registerMainAPI(ExampleProvider())

        openSettings = { ctx ->
            val hostAct = getAppCompatActivity(ctx) ?: activity
            hostAct?.let {
                val frag = BlankFragment(this)
                frag.show(it.supportFragmentManager, "TestControlCenter")
            }
        }

        act?.let { hostActivity ->
            hostActivity.runOnUiThread {
                setupHomeInjection(hostActivity)
            }
        }
    }

    private fun getAppCompatActivity(ctx: Context?): AppCompatActivity? {
        if (ctx == null) return null
        if (ctx is AppCompatActivity) return ctx
        if (ctx is ContextWrapper) {
            var c: Context? = ctx
            while (c is ContextWrapper) {
                if (c is AppCompatActivity) return c
                c = c.baseContext
            }
        }
        return null
    }

    private fun setupHomeInjection(act: AppCompatActivity) {
        val lifecycleCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(
                fm: FragmentManager,
                f: Fragment,
                v: View,
                savedInstanceState: Bundle?
            ) {
                if (isHomeFragment(f)) {
                    v.post { injectBanner(act, v) }
                }
            }

            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                if (isHomeFragment(f)) {
                    f.view?.let { v -> v.post { injectBanner(act, v) } }
                }
            }
        }

        act.supportFragmentManager.registerFragmentLifecycleCallbacks(lifecycleCallbacks, true)

        // Inject immediately if Home is already loaded
        checkAndInjectExisting(act, act.supportFragmentManager)
    }

    private fun isHomeFragment(f: Fragment): Boolean {
        val name = f.javaClass.simpleName
        return name.contains("Home", ignoreCase = true)
    }

    private fun checkAndInjectExisting(act: AppCompatActivity, fm: FragmentManager) {
        for (frag in fm.fragments) {
            if (isHomeFragment(frag)) {
                frag.view?.let { v -> v.post { injectBanner(act, v) } }
            }
            checkAndInjectExisting(act, frag.childFragmentManager)
        }
    }

    private fun injectBanner(act: AppCompatActivity, rootView: View) {
        try {
            val bannerTag = "test_plugin_home_banner"
            if (rootView.findViewWithTag<View>(bannerTag) != null) return

            val density = act.resources.displayMetrics.density
            fun dp(v: Int): Int = (v * density).toInt()

            val pkg = act.packageName
            val homeRootId = act.resources.getIdentifier("home_root", "id", pkg)
            val homeRoot = (if (homeRootId != 0) rootView.findViewById(homeRootId) else null)
                ?: (rootView as? ViewGroup)
                ?: return

            val recyclerId = act.resources.getIdentifier("home_master_recycler", "id", pkg)
            val recycler = if (recyclerId != 0) rootView.findViewById<View>(recyclerId) else null

            val fabId = act.resources.getIdentifier("home_api_fab", "id", pkg)
            val randomFabId = act.resources.getIdentifier("home_random", "id", pkg)

            val rt = Runtime.getRuntime()
            val usedHeap = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)

            val banner = LinearLayout(act).apply {
                tag = bannerTag
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))

                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#161B22"))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), Color.parseColor("#388BFD"))
                }

                val headerLayout = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL

                    val titleText = TextView(act).apply {
                        text = "⚡ Injected by TestPlugin"
                        textSize = 15f
                        setTypeface(Typeface.DEFAULT_BOLD)
                        setTextColor(Color.parseColor("#58A6FF"))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    addView(titleText)

                    val btnClose = TextView(act).apply {
                        text = "✕"
                        textSize = 16f
                        setTypeface(Typeface.DEFAULT_BOLD)
                        setTextColor(Color.parseColor("#8B949E"))
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        setOnClickListener {
                            homeRoot.removeView(this@apply)
                            recycler?.setPadding(recycler.paddingLeft, 0, recycler.paddingRight, recycler.paddingBottom)
                            Toast.makeText(act, "Banner injeksi ditutup", Toast.LENGTH_SHORT).show()
                        }
                    }
                    addView(btnClose)
                }
                addView(headerLayout)

                val subText = TextView(act).apply {
                    text = "Layout Home CloudStream Berhasil Dimodifikasi Secara Runtime!"
                    textSize = 11f
                    setTextColor(Color.parseColor("#C9D1D9"))
                    setPadding(0, dp(2), 0, dp(6))
                }
                addView(subText)

                val badgeRow = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, dp(10))

                    fun makeBadge(text: String, bg: Int): TextView {
                        return TextView(act).apply {
                            this.text = text
                            textSize = 10f
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setTextColor(Color.WHITE)
                            setPadding(dp(6), dp(2), dp(6), dp(2))
                            background = GradientDrawable().apply {
                                setColor(bg)
                                cornerRadius = dp(4).toFloat()
                            }
                        }
                    }

                    addView(makeBadge("🟢 Hooked", Color.parseColor("#238636")))
                    addView(View(act).apply { layoutParams = LinearLayout.LayoutParams(dp(6), dp(1)) })
                    addView(makeBadge("PID: ${Process.myPid()}", Color.parseColor("#1F6FEB")))
                    addView(View(act).apply { layoutParams = LinearLayout.LayoutParams(dp(6), dp(1)) })
                    addView(makeBadge("Heap: ${usedHeap}MB", Color.parseColor("#8957E5")))
                }
                addView(badgeRow)

                val btnRow = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL

                    fun makeBtn(label: String, bg: Int, onClick: () -> Unit): Button {
                        return Button(act).apply {
                            text = label
                            textSize = 11f
                            isAllCaps = false
                            setTypeface(Typeface.DEFAULT_BOLD)
                            setTextColor(Color.WHITE)
                            setPadding(dp(10), dp(4), dp(10), dp(4))
                            background = GradientDrawable().apply {
                                setColor(bg)
                                cornerRadius = dp(6).toFloat()
                            }
                            setOnClickListener { onClick() }
                        }
                    }

                    val btnToggleFab = makeBtn(if (fabHidden) "👁️ Tampilkan FAB" else "🚫 Sembunyikan FAB", Color.parseColor("#21262D")) {
                        fabHidden = !fabHidden
                        val targetVis = if (fabHidden) View.GONE else View.VISIBLE
                        if (fabId != 0) rootView.findViewById<View>(fabId)?.visibility = targetVis
                        if (randomFabId != 0) rootView.findViewById<View>(randomFabId)?.visibility = targetVis
                        (this as? Button)?.text = if (fabHidden) "👁️ Tampilkan FAB" else "🚫 Sembunyikan FAB"
                        Toast.makeText(act, if (fabHidden) "FAB Hardcoded CloudStream Disembunyikan!" else "FAB Hardcoded Dimunculkan Kembali!", Toast.LENGTH_SHORT).show()
                    }
                    addView(btnToggleFab)

                    addView(View(act).apply { layoutParams = LinearLayout.LayoutParams(dp(8), dp(1)) })

                    val btnDashboard = makeBtn("⚙️ Open Console", Color.parseColor("#238636")) {
                        val frag = BlankFragment(this@ExamplePlugin)
                        frag.show(act.supportFragmentManager, "TestControlCenter")
                    }
                    addView(btnDashboard)
                }
                addView(btnRow)
            }

            val bannerParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
                val marginH = dp(14)
                val marginT = dp(10)
                setMargins(marginH, marginT, marginH, 0)
            }

            homeRoot.addView(banner, bannerParams)

            banner.post {
                val padTop = banner.height + dp(16)
                recycler?.setPadding(recycler.paddingLeft, padTop, recycler.paddingRight, recycler.paddingBottom)
            }

            Toast.makeText(act, "⚡ TestPlugin: Home Layout Dimodifikasi!", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            // Defensive programming against layout changes
        }
    }
}
