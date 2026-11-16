package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

class BlankFragment(private val plugin: ExamplePlugin? = null) : BottomSheetDialogFragment() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var consoleView: TextView
    private lateinit var consoleScroll: ScrollView
    private lateinit var memoryText: TextView
    private lateinit var threadText: TextView
    private lateinit var latencyBadge: TextView
    private lateinit var providerText: TextView
    private lateinit var cryptoText: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun bgCard(
        bgColor: Int = Color.parseColor("#161B22"),
        radiusDp: Float = 12f,
        strokeColor: Int = Color.parseColor("#30363D")
    ) = GradientDrawable().apply {
        setColor(bgColor)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setStroke(dp(1), strokeColor)
    }

    private fun badge(text: String, bgColor: Int, textColor: Int = Color.WHITE): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 10f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(textColor)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(6).toFloat()
            }
        }
    }

    private fun button(text: String, bgColor: Int, textColor: Int = Color.WHITE, onClick: () -> Unit): Button {
        return Button(requireContext()).apply {
            this.text = text
            textSize = 12f
            isAllCaps = false
            setTextColor(textColor)
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        activity?.runOnUiThread {
            if (::consoleView.isInitialized) {
                val cur = consoleView.text.toString()
                consoleView.text = if (cur.isBlank()) "[$time] $msg" else "$cur\n[$time] $msg"
                if (::consoleScroll.isInitialized) {
                    consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }

    private fun updateTelemetry() {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val total = rt.totalMemory() / (1024 * 1024)
        val max = rt.maxMemory() / (1024 * 1024)
        val pct = if (max > 0) (used * 100 / max) else 0

        memoryText.text = "Heap Usage: $used MB used / $total MB allocated (Max $max MB, ${pct}% usage)"
        threadText.text = "Active JVM Threads: ${Thread.activeCount()} • Available Cores: ${rt.availableProcessors()}"
        log("Telemetry updated: $used/$max MB heap, ${Thread.activeCount()} threads active")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val rootScroll = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0D1117"))
        }

        val rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(24))
        }
        rootScroll.addView(rootLayout)

        // Drag Handle
        rootLayout.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, dp(2), 0, dp(14))
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#30363D"))
                cornerRadius = dp(2).toFloat()
            }
        })

        // Header Title
        rootLayout.addView(TextView(ctx).apply {
            text = "⚡ TestPlugin Control Center"
            textSize = 20f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.parseColor("#F0F6FC"))
        })

        rootLayout.addView(TextView(ctx).apply {
            text = "Advanced In-App Diagnostics, Telemetry & Runtime Inspection"
            textSize = 12f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, dp(2), 0, dp(10))
        })

        // Badges row
        val badgesRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(14))
            addView(badge("Dalvik Bytecode", Color.parseColor("#238636")))
            val spacer1 = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(6), dp(1)) }
            addView(spacer1)
            addView(badge("Host PID: ${Process.myPid()}", Color.parseColor("#1F6FEB")))
            val spacer2 = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(6), dp(1)) }
            addView(spacer2)
            addView(badge("Material 3 UI", Color.parseColor("#8957E5")))
        }
        rootLayout.addView(badgesRow)

        // Keep the host's original Downloads destination reachable after its nav slot is repurposed.
        val downloadsCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bgCard()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(12)) }

            addView(TextView(ctx).apply {
                text = "CloudStream shortcuts"
                textSize = 14f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor("#58A6FF"))
                setPadding(0, 0, 0, dp(8))
            })
            addView(button("⬇  Open Downloads", Color.parseColor("#21262D"), Color.parseColor("#F0F6FC")) {
                plugin?.openCloudStreamDownloads()
                dismiss()
            })
        }
        rootLayout.addView(downloadsCard)

        // CARD 1: Host & Memory Telemetry
        val card1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bgCard()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(12))
            }

            addView(TextView(ctx).apply {
                text = "📊 Host & Memory Telemetry"
                textSize = 14f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor("#58A6FF"))
                setPadding(0, 0, 0, dp(6))
            })

            addView(TextView(ctx).apply {
                text = "App Context: ${ctx.packageName} • PID: ${Process.myPid()}"
                textSize = 12f
                setTextColor(Color.parseColor("#C9D1D9"))
            })

            addView(TextView(ctx).apply {
                text = "Device: ${Build.MANUFACTURER.uppercase(Locale.getDefault())} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})"
                textSize = 12f
                setTextColor(Color.parseColor("#C9D1D9"))
                setPadding(0, dp(2), 0, 0)
            })

            memoryText = TextView(ctx).apply {
                text = "Heap Usage: Calculating..."
                textSize = 12f
                setTextColor(Color.parseColor("#7EE787"))
                setPadding(0, dp(4), 0, 0)
            }
            addView(memoryText)

            threadText = TextView(ctx).apply {
                text = "Active JVM Threads: Calculating..."
                textSize = 12f
                setTextColor(Color.parseColor("#C9D1D9"))
                setPadding(0, dp(2), 0, dp(10))
            }
            addView(threadText)

            addView(button("🔄 Refresh Telemetry", Color.parseColor("#21262D"), Color.parseColor("#C9D1D9")) {
                updateTelemetry()
            })
        }
        rootLayout.addView(card1)

        // CARD 2: Network Probe
        val card2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bgCard()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(12))
            }

            addView(TextView(ctx).apply {
                text = "🌐 Real-Time Network Probe"
                textSize = 14f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor("#58A6FF"))
                setPadding(0, 0, 0, dp(4))
            })

            addView(TextView(ctx).apply {
                text = "Measure live HTTP roundtrip latency inside the plugin classloader."
                textSize = 11f
                setTextColor(Color.parseColor("#8B949E"))
                setPadding(0, 0, 0, dp(8))
            })

            val urlInput = EditText(ctx).apply {
                setText("https://1.1.1.1")
                textSize = 13f
                setTextColor(Color.parseColor("#F0F6FC"))
                setHintTextColor(Color.parseColor("#484F58"))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = bgCard(bgColor = Color.parseColor("#0D1117"), strokeColor = Color.parseColor("#30363D"))
                isSingleLine = true
            }
            addView(urlInput)

            // Preset chips
            val chipsScroll = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, dp(6), 0, dp(8))
            }
            val chipsLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            chipsScroll.addView(chipsLayout)

            fun addChip(title: String, url: String) {
                val btn = button(title, Color.parseColor("#21262D"), Color.parseColor("#C9D1D9")) {
                    urlInput.setText(url)
                }.apply {
                    textSize = 10f
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                }
                chipsLayout.addView(btn)
                chipsLayout.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(6), dp(1)) })
            }

            addChip("☁️ Cloudflare", "https://1.1.1.1")
            addChip("🔍 Google", "https://8.8.8.8")
            addChip("🐙 GitHub API", "https://api.github.com")
            addView(chipsScroll)

            latencyBadge = TextView(ctx).apply {
                text = "⚡ Status: Ready to probe"
                textSize = 12f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor("#8B949E"))
                setPadding(0, dp(4), 0, dp(8))
            }
            addView(latencyBadge)

            addView(button("⚡ Ping Target URL", Color.parseColor("#238636"), Color.WHITE) {
                val target = urlInput.text.toString().trim()
                if (target.isBlank()) {
                    Toast.makeText(ctx, "Harap masukkan URL", Toast.LENGTH_SHORT).show()
                    return@button
                }
                latencyBadge.text = "⏳ Probing $target..."
                latencyBadge.setTextColor(Color.parseColor("#D29922"))
                log("Starting HTTP probe to $target...")

                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            val start = System.currentTimeMillis()
                            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 5000
                                readTimeout = 5000
                                requestMethod = "GET"
                                setRequestProperty("User-Agent", "CloudStream-TestPlugin/1.0")
                            }
                            val code = conn.responseCode
                            val time = System.currentTimeMillis() - start
                            conn.disconnect()
                            Pair(code, time)
                        } catch (e: Exception) {
                            Pair(-1, e.message ?: "Failed")
                        }
                    }

                    if (result.first > 0) {
                        val latency = result.second as Long
                        latencyBadge.text = "🟢 HTTP ${result.first} OK • ${latency}ms latency"
                        latencyBadge.setTextColor(Color.parseColor("#7EE787"))
                        log("Probe SUCCESS: HTTP ${result.first} in ${latency}ms")
                    } else {
                        latencyBadge.text = "🔴 Error: ${result.second}"
                        latencyBadge.setTextColor(Color.parseColor("#F85149"))
                        log("Probe FAILED: ${result.second}")
                    }
                }
            })
        }
        rootLayout.addView(card2)

        // CARD 3: Reflection & Provider Introspection
        val card3 = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bgCard()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(12))
            }

            addView(TextView(ctx).apply {
                text = "🧩 Provider Memory Introspection"
                textSize = 14f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor("#58A6FF"))
                setPadding(0, 0, 0, dp(4))
            })

            addView(TextView(ctx).apply {
                text = "Scans host singleton APIHolder in-memory state to inspect registered providers."
                textSize = 11f
                setTextColor(Color.parseColor("#8B949E"))
                setPadding(0, 0, 0, dp(8))
            })

            providerText = TextView(ctx).apply {
                text = "Total Registered Providers: (Tap Scan)"
                textSize = 12f
                setTextColor(Color.parseColor("#C9D1D9"))
                setPadding(0, 0, 0, dp(8))
            }
            addView(providerText)

            addView(button("🔍 Scan Loaded Providers", Color.parseColor("#1F6FEB"), Color.WHITE) {
                try {
                    val holderClass = Class.forName("com.lagradost.cloudstream3.APIHolder")
                    val field = holderClass.getField("INSTANCE")
                    val holderInstance = field.get(null)
                    val getApisMethod = holderClass.getMethod("getAllProviders")
                    val list = getApisMethod.invoke(holderInstance) as? List<*>
                    val names = list?.mapNotNull { item ->
                        val getNameMethod = item?.javaClass?.getMethod("getName")
                        getNameMethod?.invoke(item) as? String
                    } ?: emptyList()

                    providerText.text = "Active Providers in Memory: ${names.size}"
                    providerText.setTextColor(Color.parseColor("#7EE787"))
                    log("Scanned ${names.size} providers: ${names.joinToString(", ")}")
                    Toast.makeText(ctx, "Found ${names.size} providers in memory!", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    providerText.text = "Reflection scan failed: ${e.message}"
                    providerText.setTextColor(Color.parseColor("#F85149"))
                    log("Provider inspection error: ${e.message}")
                }
            })
        }
        rootLayout.addView(card3)

        // CARD 4: Crypto Engine Benchmark
        val card4 = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bgCard()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(12))
            }

            addView(TextView(ctx).apply {
                text = "⚡ In-App Crypto Benchmark"
                textSize = 14f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.parseColor("#58A6FF"))
                setPadding(0, 0, 0, dp(4))
            })

            addView(TextView(ctx).apply {
                text = "Executes 2,500 consecutive rounds of SHA-256 hash digests inside this plugin."
                textSize = 11f
                setTextColor(Color.parseColor("#8B949E"))
                setPadding(0, 0, 0, dp(8))
            })

            cryptoText = TextView(ctx).apply {
                text = "Status: Idle"
                textSize = 12f
                setTextColor(Color.parseColor("#C9D1D9"))
                setPadding(0, 0, 0, dp(8))
            }
            addView(cryptoText)

            addView(button("🚀 Run SHA-256 Benchmark", Color.parseColor("#8957E5"), Color.WHITE) {
                cryptoText.text = "Running 2,500 SHA-256 iterations..."
                cryptoText.setTextColor(Color.parseColor("#D29922"))
                log("Starting 2,500 rounds SHA-256 benchmark...")

                scope.launch {
                    val duration = withContext(Dispatchers.Default) {
                        val md = MessageDigest.getInstance("SHA-256")
                        var current = "CloudStream-Advanced-UI-TestPlugin".toByteArray()
                        val start = System.currentTimeMillis()
                        for (i in 0 until 2500) {
                            current = md.digest(current)
                        }
                        System.currentTimeMillis() - start
                    }
                    cryptoText.text = "⚡ 2,500 rounds completed in ${duration}ms"
                    cryptoText.setTextColor(Color.parseColor("#7EE787"))
                    log("Benchmark result: 2,500 hashes in ${duration}ms")
                }
            })
        }
        rootLayout.addView(card4)

        // CARD 5: Console Window
        val card5 = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bgCard()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(12))
            }

            val consoleHeader = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(8))

                addView(TextView(ctx).apply {
                    text = "🖥️ Plugin Console Log"
                    textSize = 14f
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(Color.parseColor("#58A6FF"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                addView(button("📋 Copy", Color.parseColor("#21262D"), Color.parseColor("#C9D1D9")) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("PluginLogs", consoleView.text.toString()))
                    Toast.makeText(ctx, "Log disalin ke clipboard", Toast.LENGTH_SHORT).show()
                }.apply {
                    textSize = 10f
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                })

                val space = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(6), dp(1)) }
                addView(space)

                addView(button("🗑️ Clear", Color.parseColor("#21262D"), Color.parseColor("#F85149")) {
                    consoleView.text = ""
                    log("Console cleared.")
                }.apply {
                    textSize = 10f
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                })
            }
            addView(consoleHeader)

            consoleScroll = ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(140)
                )
                background = bgCard(bgColor = Color.parseColor("#0D1117"), strokeColor = Color.parseColor("#21262D"))
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }

            consoleView = TextView(ctx).apply {
                text = ""
                textSize = 11f
                setTypeface(Typeface.MONOSPACE)
                setTextColor(Color.parseColor("#3FB950"))
            }
            consoleScroll.addView(consoleView)
            addView(consoleScroll)
        }
        rootLayout.addView(card5)

        // Close Button
        rootLayout.addView(button("✕ Tutup Dashboard", Color.parseColor("#21262D"), Color.parseColor("#C9D1D9")) {
            dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })

        updateTelemetry()
        log("Control Center initialized successfully.")
        return rootScroll
    }
}
