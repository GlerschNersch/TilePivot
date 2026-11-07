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
import dev.x360reborn.home.GAME_ART
import dev.x360reborn.home.HomeView
import dev.x360reborn.home.HubBuilder
import dev.x360reborn.home.Search
import dev.x360reborn.home.Target
import dev.x360reborn.home.Themes
import dev.x360reborn.home.Tile
import dev.x360reborn.home.TileSize
import dev.x360reborn.home.Trans
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var view: HomeView
    private lateinit var apps: Apps
    private lateinit var games: Games
    private lateinit var library: GameLibrary
    private lateinit var covers: Covers
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
        apps = Apps(this) { main.post { view.invalidate() } }
        library = GameLibrary(this)
        games = Games(library::roots)
        // The theme must be active before the view caches its first colours.
        Themes.active = Themes.byId(apps.themeId)
        covers = Covers({ uri -> runCatching { contentResolver.openInputStream(Uri.parse(uri)) }.getOrNull() }) {
            main.post { view.invalidate() }
        }
        // Game cover keys go to the cover cache, everything else is an app icon.
        view = HomeView(this, { k -> if (k.startsWith(GAME_ART)) covers.get(k) else apps.icon(k) },
            ::launch, ::options, apps::badge, { reload() }, ::tileSizeChosen, ::openSearch)
        view.heroIconFor = { k -> if (k.startsWith(GAME_ART)) covers.get(k) else apps.heroIcon(k) }
        view.tileSize = apps.tileSize
        view.bgStyle = apps.bgStyle
        view.clock24h = apps.clock24h
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
        } else {
            view.reveal() // never resume onto a hub that is still hidden
        }
    }

    /** Set when we hand off to another activity, so onResume knows to play the return transition. */
    private var leftForApp = false

    /**
     * The hub hides itself (TransFrom) before an activity starts. Normally we pause and
     * onResume brings it back; but in split-screen / freeform the launched app can open in
     * another window without pausing us, which left the hub blank and deaf to input. If we
     * are still resumed this long after a hand-off, the hub comes back on its own.
     */
    private val stillHere = Runnable {
        if (leftForApp) {
            leftForApp = false
            view.reveal()
        }
    }

    /** We just started another activity: arm the return transition and the stuck-hub guard. */
    private fun handedOff() {
        leftForApp = true
        main.removeCallbacks(stillHere)
        main.postDelayed(stillHere, HANDOFF_GUARD_MS)
    }

    override fun onPause() {
        main.removeCallbacks(clock)
        main.removeCallbacks(stillHere)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home: jump back to the home channel (and un-hide the hub).
        if (Intent.ACTION_MAIN == intent.action && intent.hasCategory(Intent.CATEGORY_HOME)) {
            view.reveal()
            view.back()
        }
    }

    override fun onDestroy() {
        launcherApps.unregisterCallback(appsChanged)
        main.removeCallbacksAndMessages(null)
        loader.shutdownNow()
        apps.close()
        covers.close()
        super.onDestroy()
    }

    /** The app list from the latest load; the search dialog filters this. */
    @Volatile private var appList: List<AppInfo> = emptyList()

    /** Rebuilds the hub off the main thread. Game folders are only re-walked when [rescanGames]. */
    private fun reload(rescanGames: Boolean = false) {
        val layout = view.wanted
        val (gridPx, heroPx) = view.iconPxFor(layout)
        loader.execute {
            val list = apps.load()
            appList = list
            // The game list is cached (see Games): most rebuilds don't touch the folders.
            val gameList = games.load(force = rescanGames)
            covers.update(gameList)
            val hub = HubBuilder.build(
                list, apps.recent(), apps.pins(),
                apps.settingsEntries(library.trees().size, games.found),
                layout, apps.folders(), gameList,
                hiddenChannels = hiddenChannels(),
            )
            // A rotation may have queued a newer build; only the latest layout wins.
            main.post { if (view.wanted == layout) view.setHub(hub) }
            // A frame shows at most three pages (two mid-swipe, plus letterbox edges in landscape).
            apps.fitIconCache(3 * maxOf(layout.grid.slots.size, layout.home.slots.size), gridPx, heroPx)
            covers.edgePx = maxOf(gridPx, heroPx)
            // Home-page icons first, so the visible tiles fill in, then the rest in page order.
            val visible = hub.pages[0].tiles.mapNotNull { it.iconKey }
            val later = hub.pages.drop(1).flatMap { p -> p.tiles.mapNotNull { it.iconKey } }
            apps.loadIcons(visible, later) { main.post { view.invalidate() } }
        }
    }

    /** Media channels the user hid in settings; everything else always stays in the pivot. */
    private fun hiddenChannels(): Set<String> = buildSet {
        if (apps.channelHidden("tv & movies")) add("tv & movies")
        if (apps.channelHidden("music")) add("music")
    }

    private fun toggleChannel(title: String) {
        val hidden = !apps.channelHidden(title)
        apps.setChannelHidden(title, hidden)
        Toast.makeText(this, "$title channel ${if (hidden) "hidden" else "shown"}", Toast.LENGTH_SHORT).show()
        reload()
    }

    private fun themePicker() {
        val themes = Themes.all
        val current = themes.indexOfFirst { it.id == apps.themeId }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Theme")
            .setSingleChoiceItems(themes.map { it.name }.toTypedArray(), current) { dialog, which ->
                val theme = themes[which]
                apps.themeId = theme.id
                Themes.active = theme
                view.onThemeChanged()
                Toast.makeText(this, "Theme: ${theme.name}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                // The hub is already hidden (TransFrom), so every early exit must bring it back.
                val cn = apps.component(t.key)
                val user = apps.user(t.key)
                if (cn == null || user == null) {
                    view.play(Trans.TO)
                    Toast.makeText(this, "${tile.label} is no longer installed", Toast.LENGTH_SHORT).show()
                    reload()
                    return
                }
                try {
                    // The hub is already zooming away (TransFrom), so the app just fades in over it.
                    val opts = ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, 0)
                    launcherApps.startMainActivity(cn, user, bounds, opts.toBundle())
                    handedOff()
                    apps.markLaunched(t.key)
                    // Rebuild after the app is up, so "recent" reorders while the hub is hidden.
                    main.postDelayed(::reload, 800)
                } catch (e: Exception) {
                    Log.w(TAG, "launch failed: ${t.key}", e)
                    view.play(Trans.TO)
                    Toast.makeText(this, "Can't open ${tile.label}", Toast.LENGTH_SHORT).show()
                }
            }
            is Target.Game -> launchGame(tile, t)
            is Target.Action -> if (t.name == Apps.ACTION_TILE_SIZE) {
                view.tileSize = view.tileSize.next()
                tileSizeChosen(view.tileSize)
            } else if (t.name == Apps.ACTION_SEARCH) {
                openSearch("")
            } else if (t.name == Apps.ACTION_XBOX) {
                gameLibrary()
            } else if (t.name == Apps.ACTION_THEME) {
                themePicker()
            } else if (t.name == Apps.ACTION_BG) {
                apps.bgStyle = apps.bgStyle.next()
                view.bgStyle = apps.bgStyle
                Toast.makeText(this, "Background: ${apps.bgStyle.label}", Toast.LENGTH_SHORT).show()
                reload()
            } else if (t.name == Apps.ACTION_CLOCK) {
                apps.clock24h = !apps.clock24h
                view.clock24h = apps.clock24h
                Toast.makeText(this, if (apps.clock24h) "Clock: 24-hour" else "Clock: 12-hour", Toast.LENGTH_SHORT).show()
                reload()
            } else if (t.name == Apps.ACTION_TV) {
                toggleChannel("tv & movies")
            } else if (t.name == Apps.ACTION_MUSIC) {
                toggleChannel("music")
            } else if (t.name == Apps.ACTION_BACKUP) {
                pickFile(Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, "tilepivot-settings.json"), REQ_BACKUP)
            } else if (t.name == Apps.ACTION_RESTORE) {
                pickFile(Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    // .json often has no registered MIME type, so accept the usual stand-ins too.
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream")), REQ_RESTORE)
            } else try {
                // The privacy tile opens the hosted policy in the browser; the rest are system actions.
                val intent = if (t.name == Apps.ACTION_PRIVACY) {
                    Intent(Intent.ACTION_VIEW, Uri.parse(Apps.PRIVACY_POLICY_URL))
                } else {
                    Intent(t.name)
                }
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, 0).toBundle())
                handedOff()
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
     * A game whose folder has no direct path (see [HostPaths]) never gets an intent at all.
     */
    private fun launchGame(tile: Tile, game: Target.Game) {
        val path = game.hostPath
        if (path == null) {
            // The hub already played its leave transition; bring it back before the dialog.
            view.play(Trans.TO)
            AlertDialog.Builder(this)
                .setTitle("Can't open this game")
                .setMessage("Xendroid+ needs a direct file path to the game, and the folder " +
                    "“${tile.label}” was picked from can't provide one (Downloads, cloud and other " +
                    "app-provided folders don't). Pick the folder from your device storage or SD " +
                    "card instead, in settings › xbox 360 games.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val pkg = XENDROID_PACKAGES.firstOrNull { p ->
            runCatching { packageManager.resolveActivity(Intent(ACTION_XENDROID).setPackage(p), 0) != null }
                .getOrDefault(false)
        }
        if (pkg == null) {
            view.play(Trans.TO)
            AlertDialog.Builder(this)
                .setTitle("Xendroid+ is not installed")
                .setMessage("Xbox 360 games open in Xendroid+, a separate emulator app that is not " +
                    "part of TilePivot. Get and install Xendroid+ yourself; once it is installed, " +
                    "this game will open in it.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        try {
            val intent = Intent(ACTION_XENDROID).setPackage(pkg)
                .putExtra("game_uri", path)
                .putExtra("game_name", tile.label)
            startActivity(intent, ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, 0).toBundle())
            handedOff()
        } catch (e: Exception) {
            Log.w(TAG, "xendroid launch failed: $path", e)
            view.play(Trans.TO)
            Toast.makeText(this, "Can't open ${tile.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickFile(intent: Intent, request: Int) {
        try {
            @Suppress("DEPRECATION") // plain Activity: no AndroidX result API here
            startActivityForResult(intent, request)
            handedOff()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No file picker on this device", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_BACKUP -> saveBackup(uri)
            REQ_RESTORE -> confirmRestore(uri)
            REQ_GAME_FOLDER -> addGameFolder(uri)
        }
    }

    private fun saveBackup(uri: Uri) {
        try {
            val text = apps.exportSettings()
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "Settings backed up", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "backup failed", e)
            Toast.makeText(this, "Couldn't save the backup", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmRestore(uri: Uri) {
        val values = try {
            val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            Backup.decode(text)
        } catch (e: Backup.BadBackup) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            return
        } catch (e: Exception) {
            Log.w(TAG, "restore read failed", e)
            Toast.makeText(this, "Couldn't read that file", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Restore settings")
            .setMessage("Your pins, folders, recents, theme and layout will be replaced with the ones in this backup.")
            .setPositiveButton("Restore") { _, _ ->
                if (apps.importSettings(values)) {
                    Toast.makeText(this, "Settings restored", Toast.LENGTH_SHORT).show()
                    // Theme, tile size, background and clock are read in onCreate: start over.
                    recreate()
                } else {
                    Toast.makeText(this, "Couldn't restore settings", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Settings-channel Xbox entry: the game-library manager. Lists the picked folders (each
     * marked when it can't be read now, or can't hand Xendroid+ a path), with Add and Re-scan;
     * tapping a folder offers to remove it. Folder state is read off the main thread.
     */
    private fun gameLibrary() {
        loader.execute {
            val folders = library.folders()
            main.post { if (!isFinishing && !isDestroyed) showGameLibrary(folders) }
        }
    }

    private fun showGameLibrary(folders: List<GameLibrary.Folder>) {
        val b = AlertDialog.Builder(this).setTitle("Xbox 360 game folders")
        if (folders.isEmpty()) {
            b.setMessage("Pick the folder that holds your Xbox 360 games (ISO, ZAR, Games on Demand " +
                "or XBLA packages, or folders with a default.xex). You only need to do this once; " +
                "TilePivot gets read-only access to that folder and nothing else.")
        } else {
            val rows = folders.map { f ->
                val note = when {
                    !f.available -> "\nunavailable: removed, unmounted or access revoked"
                    f.path == null -> "\nno direct file path: games here can't open in Xendroid+"
                    else -> ""
                }
                f.label + note
            }
            b.setItems(rows.toTypedArray()) { _, which ->
                folders.getOrNull(which)?.let(::confirmRemoveFolder)
            }
        }
        b.setPositiveButton("Add folder") { _, _ -> pickGameFolder() }
        if (folders.isNotEmpty()) b.setNeutralButton("Re-scan") { _, _ -> rescanGames() }
        b.setNegativeButton("Close", null)
        b.show()
    }

    private fun confirmRemoveFolder(folder: GameLibrary.Folder) {
        AlertDialog.Builder(this)
            .setTitle("Remove folder")
            .setMessage("“${folder.label}” will no longer be scanned for games. The files stay where they are.")
            .setPositiveButton("Remove") { _, _ ->
                // Same single-threaded loader as the rescan, so the removal lands first.
                loader.execute { library.remove(folder.tree) }
                rescanGames()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickGameFolder() {
        pickFile(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
            REQ_GAME_FOLDER)
    }

    private fun addGameFolder(tree: Uri) {
        if (!library.add(tree)) {
            Toast.makeText(this, "Couldn't keep access to that folder", Toast.LENGTH_LONG).show()
            return
        }
        rescanGames()
    }

    /** Re-walks the picked folders and reports the count once the scan is done. */
    private fun rescanGames() {
        reload(rescanGames = true)
        // The loader is single-threaded, so this runs after the rescan: report its count.
        loader.execute {
            val n = games.found
            main.post { Toast.makeText(this, "$n Xbox 360 games found", Toast.LENGTH_SHORT).show() }
        }
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
        /** How long a hand-off may leave us resumed (no onPause) before the hub reappears. */
        const val HANDOFF_GUARD_MS = 2_000L
        const val REQ_BACKUP = 1
        const val REQ_RESTORE = 2
        const val REQ_GAME_FOLDER = 3
        /** Xendroid+'s emulator launch action (EmulatorHostActivity). */
        const val ACTION_XENDROID = "xendroid.intent.action.xendroid"
        /** Release builds install as .fork; the plain package is the suffix-overridden build. */
        val XENDROID_PACKAGES = listOf("xendroid.compose.fork", "xendroid.compose")
    }
}
