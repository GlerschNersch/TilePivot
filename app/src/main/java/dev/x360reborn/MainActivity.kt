package dev.x360reborn

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import dev.x360reborn.home.AppInfo
import dev.x360reborn.home.HomeView
import dev.x360reborn.home.HubBuilder
import dev.x360reborn.home.Search
import dev.x360reborn.home.Target
import dev.x360reborn.home.Tile
import dev.x360reborn.home.TileSize
import dev.x360reborn.home.Trans
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var view: HomeView
    private lateinit var apps: Apps
    private lateinit var games: Games
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
        games = Games(this)
        view = HomeView(this, ::launch, ::options, apps::icon, { reload() }, ::tileSizeChosen, ::openSearch)
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

    /** The app list from the latest load; the search dialog filters this. */
    @Volatile private var appList: List<AppInfo> = emptyList()

    private fun reload() {
        val layout = view.wanted
        loader.execute {
            val list = apps.load()
            appList = list
            val gameList = games.load()
            val hub = HubBuilder.build(
                list, apps.recent(), apps.pins(),
                apps.settingsEntries(games.hasAccess(), games.found),
                layout, apps.folders(), gameList,
            )
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
            is Target.Game -> launchGame(tile, t.path)
            is Target.Action -> if (t.name == Apps.ACTION_TILE_SIZE) {
                view.tileSize = view.tileSize.next()
                tileSizeChosen(view.tileSize)
            } else if (t.name == Apps.ACTION_SEARCH) {
                openSearch("")
            } else if (t.name == Apps.ACTION_XBOX) {
                xboxAction()
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

    /**
     * Hands an Xbox 360 game to Xendroid+ using its own launch contract: the custom action,
     * the emulator's package, and game_uri / game_name extras only (no launch_args/launch_token —
     * those gate Xendroid+'s own per-launch options and a frontend must not send them).
     * game_uri is the game's absolute path; for folder games it is the default.xex path.
     */
    private fun launchGame(tile: Tile, path: String) {
        val pkg = XENDROID_PACKAGES.firstOrNull { p ->
            runCatching { packageManager.resolveActivity(Intent(ACTION_XENDROID).setPackage(p), 0) != null }
                .getOrDefault(false)
        }
        if (pkg == null) {
            // The hub already played its leave transition; bring it back before the dialog.
            view.play(Trans.TO)
            AlertDialog.Builder(this)
                .setTitle("Xendroid+ is not installed")
                .setMessage("Xbox 360 games need the Xendroid+ emulator. Install it, then this game will open in it.")
                .setPositiveButton("Get Xendroid+") { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(XENDROID_RELEASES)))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        try {
            val intent = Intent(ACTION_XENDROID).setPackage(pkg)
                .putExtra("game_uri", path)
                .putExtra("game_name", tile.label)
            startActivity(intent, ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, 0).toBundle())
            leftForApp = true
        } catch (e: Exception) {
            Log.w(TAG, "xendroid launch failed: $path", e)
            view.play(Trans.TO)
            Toast.makeText(this, "Can't open ${tile.label}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Settings-channel Xbox entry: grant all-files access when missing, otherwise re-scan. */
    private fun xboxAction() {
        if (games.hasAccess()) {
            reload()
            Toast.makeText(this, "${games.found} Xbox 360 games found", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", packageName, null),
            ))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
        leftForApp = true
    }

    /** Search dialog: a text field over a live-filtered list of apps; tapping one launches it. */
    private fun openSearch(initial: String) {
        val input = EditText(this).apply {
            hint = "Search apps"
            setSingleLine(true)
            setText(initial)
            setSelection(length())
        }
        val results = ArrayList<AppInfo>()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList())
        val list = ListView(this).apply { this.adapter = adapter }
        fun refresh() {
            results.clear()
            results.addAll(Search.filter(appList, input.text.toString()))
            adapter.clear()
            adapter.addAll(results.map { it.label })
            adapter.notifyDataSetChanged()
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(input)
            addView(list)
        }
        val dialog = AlertDialog.Builder(this).setView(box).create()
        list.setOnItemClickListener { _, _, pos, _ ->
            val app = results.getOrNull(pos) ?: return@setOnItemClickListener
            dialog.dismiss()
            launch(Tile(app.label, Target.App(app.key)), android.graphics.Rect())
        }
        refresh()
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        input.requestFocus()
    }

    /** Single-line name prompt used for creating and renaming folders. Empty names are ignored. */
    private fun nameDialog(title: String, prefill: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = "Folder name"
            setSingleLine(true)
            setText(prefill)
            setSelection(length())
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onOk(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun options(tile: Tile) {
        // Game files aren't ours to manage, so game tiles have no long-press menu.
        if (tile.target is Target.Game) return
        when {
            tile.target is Target.App -> appOptions(tile)
            tile.target is Target.Channel && tile.folderId != null -> folderOptions(tile)
            else -> return
        }
    }

    private fun appOptions(tile: Tile) {
        val key = (tile.target as Target.App).key
        val pinned = key in apps.pins()
        val folders = apps.folders()
        val work = apps.isWork(key)
        val pkg = key.substringBefore('/')
        // The old popup menu rendered zero-height on Matt's phone and the emulator, which made
        // every long-press action unreachable; these options are an AlertDialog list instead.
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels += if (pinned) "Unpin from home" else "Pin to home"
        actions += { apps.togglePin(key); reload() }
        if (key in apps.recent()) {
            labels += "Remove from recent"
            actions += { apps.forget(key); reload() }
        }
        labels += "App info"
        actions += {
            // ACTION_DELETE always targets the personal profile; work apps are managed from App info instead.
            if (work) {
                launcherApps.startAppDetailsActivity(apps.component(key), apps.user(key), null, null)
            } else {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
            }
        }
        if (!work) {
            labels += "Uninstall"
            actions += { startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))) }
        }
        // Inside a folder channel, an app can also be taken back out of that folder.
        val homeFolder = tile.folderId?.let { id -> folders.firstOrNull { it.id == id } }
        if (homeFolder != null) {
            labels += "Remove from ${homeFolder.name}"
            actions += { apps.removeFromFolder(homeFolder.id, key); reload() }
        }
        folders.forEach { f ->
            labels += "Add to folder: ${f.name}"
            actions += {
                if (key in f.members) {
                    Toast.makeText(this, "Already in ${f.name}", Toast.LENGTH_SHORT).show()
                } else {
                    apps.addToFolder(f.id, key); reload()
                }
            }
        }
        labels += "New folder…"
        actions += {
            nameDialog("New folder", "") { name ->
                apps.createFolder(name, key); reload()
            }
        }
        AlertDialog.Builder(this)
            .setTitle(tile.label)
            .setItems(labels.toTypedArray()) { _, which -> actions.getOrNull(which)?.invoke() }
            .show()
    }

    private fun folderOptions(tile: Tile) {
        val id = tile.folderId ?: return
        AlertDialog.Builder(this)
            .setTitle(tile.label)
            .setItems(arrayOf("Rename folder…", "Delete folder")) { _, which ->
                when (which) {
                    0 -> nameDialog("Rename folder", tile.label) { name ->
                        apps.renameFolder(id, name); reload()
                    }
                    1 -> AlertDialog.Builder(this)
                        .setTitle("Delete folder")
                        .setMessage("“${tile.label}” will be removed. Its apps stay installed and return to the normal lists.")
                        .setPositiveButton("Delete") { _, _ -> apps.deleteFolder(id); reload() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .show()
    }

    private companion object {
        const val TAG = "X360Reborn"
        /** Xendroid+'s emulator launch action (EmulatorHostActivity). */
        const val ACTION_XENDROID = "xendroid.intent.action.xendroid"
        /** Release builds install as .fork; the plain package is the suffix-overridden build. */
        val XENDROID_PACKAGES = listOf("xendroid.compose.fork", "xendroid.compose")
        const val XENDROID_RELEASES = "https://github.com/phforner0/Xendroid-Plus/releases"
    }
}
