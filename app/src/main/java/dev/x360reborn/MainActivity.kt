package dev.x360reborn

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.PopupMenu
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import dev.x360reborn.home.HomeView
import dev.x360reborn.home.HubBuilder
import dev.x360reborn.home.Target
import dev.x360reborn.home.Tile
import dev.x360reborn.home.TileSize
import dev.x360reborn.home.Trans
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var view: HomeView
    private lateinit var apps: Apps
    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val launcherApps by lazy { getSystemService(LauncherApps::class.java) }

    private val appsChanged = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
    }

    private val clock = object : Runnable {
        override fun run() {
            view.refreshClock()
            main.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apps = Apps(this)
        view = HomeView(this, ::launch, ::options, apps::icon, { reload() }, ::tileSizeChosen)
        view.tileSize = apps.tileSize
        setContentView(view)
        window.insetsController?.apply {
            hide(WindowInsets.Type.statusBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        view.requestFocus()
        // targetSdk 36 uses predictive back, which never calls onBackPressed.
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { view.back() }
        launcherApps.registerCallback(appsChanged)
        reload()
        view.play(Trans.TO)
    }

    override fun onResume() {
        super.onResume()
        main.post(clock)
        // Coming back from something we launched: the hub flies back in (TransBackTo).
        if (leftForApp) {
            leftForApp = false
            view.play(Trans.BACK_TO)
        }
    }

    /** Set when we hand off to another activity, so onResume knows to play the return transition. */
    private var leftForApp = false

    override fun onPause() {
        main.removeCallbacks(clock)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home: jump back to the home channel.
        if (Intent.ACTION_MAIN == intent.action && intent.hasCategory(Intent.CATEGORY_HOME)) view.back()
    }

    override fun onDestroy() {
        launcherApps.unregisterCallback(appsChanged)
        loader.shutdownNow()
        super.onDestroy()
    }

    private fun reload() {
        val layout = view.wanted
        loader.execute {
            val list = apps.load()
            val hub = HubBuilder.build(list, apps.recent(), apps.pins(), apps.settingsEntries(), layout)
            // A rotation may have queued a newer build; only the latest layout wins.
            main.post { if (view.wanted == layout) view.setHub(hub) }
            // Home-page icons first, so the visible tiles fill in before the long lists.
            val visible = hub.pages[0].tiles.mapNotNull { it.iconKey }
            apps.loadIcons(visible) { main.post { view.invalidate() } }
        }
    }

    private fun tileSizeChosen(size: TileSize) {
        apps.tileSize = size
        // The settings tile shows the current size, so rebuild even if the layout already caught up.
        reload()
        Toast.makeText(this, "Tile size: ${size.label}", Toast.LENGTH_SHORT).show()
    }

    private fun launch(tile: Tile, bounds: android.graphics.Rect) {
        when (val t = tile.target) {
            is Target.App -> {
                val cn = apps.component(t.key) ?: return
                val user = apps.user(t.key) ?: return
                try {
                    // The hub is already zooming away (TransFrom), so the app just fades in over it.
                    val opts = ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, 0)
                    launcherApps.startMainActivity(cn, user, bounds, opts.toBundle())
                    leftForApp = true
                    apps.markLaunched(t.key)
                    // Rebuild after the app is up, so "recent" reorders while the hub is hidden.
                    main.postDelayed(::reload, 800)
                } catch (e: Exception) {
                    Log.w(TAG, "launch failed: ${t.key}", e)
                    view.play(Trans.TO)
                    Toast.makeText(this, "Can't open ${tile.label}", Toast.LENGTH_SHORT).show()
                }
            }
            is Target.Action -> if (t.name == Apps.ACTION_TILE_SIZE) {
                view.tileSize = view.tileSize.next()
                tileSizeChosen(view.tileSize)
            } else try {
                startActivity(Intent(t.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, 0).toBundle())
                leftForApp = true
            } catch (e: ActivityNotFoundException) {
                view.play(Trans.TO)
                Toast.makeText(this, "Not available on this device", Toast.LENGTH_SHORT).show()
            }
            is Target.Channel -> {}
        }
    }

    private fun options(tile: Tile) {
        val key = (tile.target as? Target.App)?.key ?: return
        val pinned = key in apps.pins()
        val menu = PopupMenu(this, view, Gravity.CENTER)
        menu.menu.add(0, 1, 0, if (pinned) "Unpin from home" else "Pin to home")
        if (key in apps.recent()) menu.menu.add(0, 2, 1, "Remove from recent")
        menu.menu.add(0, 3, 2, "App info")
        // ACTION_DELETE always targets the personal profile; work apps are removed from App info instead.
        val work = apps.isWork(key)
        if (!work) menu.menu.add(0, 4, 3, "Uninstall")
        menu.setOnMenuItemClickListener { item ->
            val pkg = key.substringBefore('/')
            when (item.itemId) {
                1 -> { apps.togglePin(key); reload() }
                2 -> { apps.forget(key); reload() }
                3 -> if (work) {
                    launcherApps.startAppDetailsActivity(apps.component(key), apps.user(key), null, null)
                } else {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
                }
                4 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)))
            }
            true
        }
        menu.show()
    }

    private companion object {
        const val TAG = "X360Reborn"
    }
}
