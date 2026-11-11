package com.lagradost.cloudstream3.providers

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import android.app.Activity
import android.app.Application
import android.os.Bundle

import okhttp3.Dns
import java.net.InetAddress
import org.json.JSONObject
import okhttp3.OkHttpClient

@CloudstreamPlugin
class TopluDizilerPlugin: Plugin() {
    companion object {
        var pluginContext: Context? = null
        var currentActivity: Activity? = null

        fun getCurrentActivityRef(): Activity? {
            try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
                val activitiesField = activityThreadClass.getDeclaredField("mActivities")
                activitiesField.isAccessible = true
                val activities = activitiesField.get(activityThread) as Map<*, *>
                for (activityRecord in activities.values) {
                    val activityRecordClass = activityRecord!!.javaClass
                    val activityField = activityRecordClass.getDeclaredField("activity")
                    activityField.isAccessible = true
                    val activity = activityField.get(activityRecord) as? Activity
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        return activity
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("TopluDiziler", "Error getting current activity via reflection: ${e.message}")
            }
            return null
        }

        fun showSettingsDialog(context: Context) {
            try {
                val act = currentActivity ?: getCurrentActivityRef() ?: (context as? Activity)
                val runnable = Runnable {
                    try {
                        val modes = arrayOf("🎬 Sadece Filmler", "📺 Sadece Diziler", "⛩️ Sadece Animeler", "🌍 Sadece Belgeseller", "📡 Sadece Canlı TV", "✨ Tüm İçerikler")
                        val modeKeys = arrayOf("movie", "tv", "anime", "doc", "live", "all")

                        val prefs = context.getSharedPreferences("vizyonhub_prefs", Context.MODE_PRIVATE)
                        val current = prefs.getString("watch_mode", "all") ?: "all"
                        val checkedItem = modeKeys.indexOf(current).let { if (it == -1) 5 else it }

                        val targetContext = currentActivity ?: getCurrentActivityRef() ?: context
                        android.app.AlertDialog.Builder(targetContext)
                            .setTitle("🍿 VizyonHub İçerik Türü Seçimi")
                            .setSingleChoiceItems(modes, checkedItem) { dialog, which ->
                                val selectedMode = modeKeys[which]
                                prefs.edit().putString("watch_mode", selectedMode).apply()
                                TopluDizilerProvider.clearCache()
                                android.widget.Toast.makeText(targetContext, "Seçiminiz kaydedildi: ${modes[which]}! Ana sayfayı yenileyiniz.", android.widget.Toast.LENGTH_LONG).show()
                                dialog.dismiss()
                            }
                            .setNegativeButton("Kapat", null)
                            .show()
                    } catch (e: Throwable) {
                        android.util.Log.e("TopluDiziler", "Dialog inner error: ${e.message}")
                    }
                }
                if (act != null) {
                    act.runOnUiThread(runnable)
                } else {
                    runnable.run()
                }
            } catch (e: Throwable) {
                android.util.Log.e("TopluDiziler", "Settings dialog error: ${e.message}")
            }
        }
    }

    override fun load(context: Context) {
        pluginContext = context
        val app = context.applicationContext as? Application
        app?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) { currentActivity = activity }
            override fun onActivityStarted(activity: Activity) { currentActivity = activity }
            override fun onActivityResumed(activity: Activity) { currentActivity = activity }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) { if (currentActivity == activity) currentActivity = null }
        })
        
        // Configure global OkHttpClient to use DNS over HTTPS (DoH) to bypass ISP throttling and DNS poisoning
        try {
            val customDns = SafeDns()
            val dnsBuilder = com.lagradost.cloudstream3.app.baseClient.newBuilder()
            com.lagradost.cloudstream3.app.baseClient = dnsBuilder.dns(customDns).build()
            android.util.Log.d("TopluDiziler", "Global DoH DNS resolver configured successfully.")
        } catch (e: Throwable) {
            android.util.Log.e("TopluDiziler", "Failed to configure global DoH: ${e.message}", e)
        }

        val resolvedAct = getCurrentActivityRef()
        if (resolvedAct != null) {
            currentActivity = resolvedAct
        }

        val provider = TopluDizilerProvider()
        registerMainAPI(provider)
        
        // Register all other sub-providers to ensure they are available in APIHolder within VizyonHub
        try {
            registerMainAPI(FilmMakinesi())
            registerMainAPI(FullHDFilmizlesene())
            registerMainAPI(KultFilmler())
            registerMainAPI(SezonlukDizi())
            registerMainAPI(Sinewix())
            registerMainAPI(DizipalProvider())
            registerMainAPI(FilmizleChProvider())
            registerMainAPI(DiziSolProvider())
            registerMainAPI(DiziFilmLifeProvider())
            registerMainAPI(FilmizleNowProvider())
            registerMainAPI(SetFilmizleProvider())
            registerMainAPI(DizirollProvider())
            
            registerMainAPI(CanlitvProvider())
            registerMainAPI(DDizi())
            
            registerMainAPI(AnimeciX())
            registerMainAPI(OpenAnime())
            
            registerMainAPI(BelgeselProvider())
            
            // Extractors
            registerExtractorAPI(CloseLoad())
            registerExtractorAPI(RapidVid())
            registerExtractorAPI(TRsTX())
            registerExtractorAPI(VidMoxy())
            registerExtractorAPI(Sobreatsesuyp())
            registerExtractorAPI(TurboImgz())
            registerExtractorAPI(TurkeyPlayer())
            registerExtractorAPI(TauVideo())
        } catch (e: Throwable) {
            android.util.Log.e("TopluDiziler", "Error registering dependent APIs: ${e.message}", e)
        }
        
        openSettings = { ctx ->
            showSettingsDialog(ctx)
        }
    }
}

class SafeDns : Dns {
    private val bootstrapClient = OkHttpClient.Builder().build()
    
    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname == "cloudflare-dns.com" || hostname == "chrome.cloudflare-dns.com") {
            return listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
        }
        try {
            val url = "https://cloudflare-dns.com/dns-query?name=${hostname}&type=A"
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("accept", "application/dns-json")
                .build()
            bootstrapClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return Dns.SYSTEM.lookup(hostname)
                    val json = JSONObject(body)
                    val answer = json.optJSONArray("Answer")
                    if (answer != null && answer.length() > 0) {
                        val addresses = mutableListOf<InetAddress>()
                        for (i in 0 until answer.length()) {
                            val obj = answer.getJSONObject(i)
                            val type = obj.optInt("type", 1)
                            if (type == 1) { // Type A (IPv4)
                                val data = obj.optString("data", "")
                                if (data.isNotEmpty()) {
                                    addresses.add(InetAddress.getByName(data))
                                }
                            }
                        }
                        if (addresses.isNotEmpty()) return addresses
                    }
                }
            }
        } catch (e: Throwable) {
            // Fallback
        }
        return Dns.SYSTEM.lookup(hostname)
    }
}
