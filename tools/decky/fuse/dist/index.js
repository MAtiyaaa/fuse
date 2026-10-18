const manifest = {"name":"Fuse"};
const API_VERSION = 2;
const internalAPIConnection = window.__DECKY_SECRET_INTERNALS_DO_NOT_USE_OR_YOU_WILL_BE_FIRED_deckyLoaderAPIInit;
if (!internalAPIConnection) {
    throw new Error('[@decky/api]: Failed to connect to the loader as as the loader API was not initialized. This is likely a bug in Decky Loader.');
}
let api;
try {
    api = internalAPIConnection.connect(API_VERSION, manifest.name);
}
catch {
    api = internalAPIConnection.connect(1, manifest.name);
    console.warn(`[@decky/api] Requested API version ${API_VERSION} but the running loader only supports version 1. Some features may not work.`);
}
if (api._version != API_VERSION) {
    console.warn(`[@decky/api] Requested API version ${API_VERSION} but the running loader only supports version ${api._version}. Some features may not work.`);
}
const callable = api.callable;
const toaster = api.toaster;
const definePlugin = (fn) => {
    return (...args) => {
        return fn(...args);
    };
};

/** Whether the person turned off Fuse's entries in Steam's menu (the kill switch). Fails closed: off. */
const menuOff = callable("menu_off");
/** Whether Fuse lets the plugin do [scope]: an older Fuse that doesn't say lets it do everything it offers. */
function may(hi, scope) {
    return !hi?.allowed || hi.allowed.includes(scope);
}
const hello = callable("hello");
const status = callable("status");
const steamGame = callable("steam_game");
const addGame = callable("add_game");
const removeGame = callable("remove_game");
const openGame = callable("open_game");
const importGames = callable("import_games");
const library = callable("library");
const openFuseGame = callable("open_fuse_game");
const emulators = callable("emulators");
const updateAllEmulators = callable("emulators_update_all");
const installAllEmulators = callable("emulators_install_all");
const uninstallAllEmulators = callable("emulators_uninstall_all");
const steamChanges = callable("steam_changes");
const changeArt = callable("change_art");
const watched = callable("watched");
const played = callable("played");
const changeDone = callable("change_done");
/** The words to show for an answer that failed; null when it didn't. */
function failure(answer) {
    if (!answer)
        return "Fuse isn't answering.";
    return answer.error ? answer.message ?? "Fuse couldn't do that." : null;
}

/**
 * "Add to Fuse" in Steam's own game menu (the ... on a game's page and in the library), placed just
 * above Properties. The entry asks Fuse whether the game is already in its library when the menu
 * opens, then offers Add to Fuse, or Open in Fuse and Remove from Fuse.
 *
 * Fail safe by design, because Steam's UI changes without notice and other plugins patch the same
 * menu:
 * - Only the menu's own render is patched, never a prototype other plugins share.
 * - Every step is guarded: on any error Steam's menu is returned exactly as Steam made it.
 * - After a few errors the patch removes itself for the rest of the session.
 * - The patch is undone when the plugin unloads, even if parts of it failed.
 * The Quick Access panel still offers the same actions.
 */
const MARK = "fuse-menu";
const MAX_ERRORS = 3;
function entries(appid) {
    const act = async (run, done) => {
        try {
            const answer = await run();
            toaster.toast({ title: "Fuse", body: failure(answer) ?? answer.message ?? done });
        }
        catch {
            toaster.toast({ title: "Fuse", body: "Fuse didn't answer. Open Fuse, then try again." });
        }
    };
    return [
        window.SP_REACT.createElement(DFL.MenuItem, { key: MARK, onSelected: async () => {
                try {
                    const known = await steamGame(appid);
                    if (failure(known)) {
                        toaster.toast({ title: "Fuse", body: failure(known) });
                        return;
                    }
                    if (known.inFuse)
                        await act(() => openGame(appid), "Opened in Fuse");
                    else
                        await act(() => addGame(appid), "Added to Fuse");
                }
                catch {
                    toaster.toast({ title: "Fuse", body: "Fuse didn't answer. Open Fuse, then try again." });
                }
            } }, "Fuse: Add or Open"),
        window.SP_REACT.createElement(DFL.MenuItem, { key: MARK + "-remove", onSelected: () => act(() => removeGame(appid), "Removed from Fuse") }, "Fuse: Remove from Fuse"),
    ];
}
/** Adds the entries once (by key), above Properties, without touching anything else in the list. */
function splice(children, appid) {
    if (!Array.isArray(children) || children.some((c) => c?.key === MARK))
        return;
    const properties = children.findIndex((item) => DFL.findInReactTree(item, (x) => typeof x?.onSelected === "function" && x.onSelected.toString().includes("AppProperties")));
    children.splice(properties >= 0 ? properties : children.length, 0, ...entries(appid));
}
function appidOf(component) {
    const id = component?._owner?.pendingProps?.overview?.appid ?? component?.props?.overview?.appid;
    return typeof id === "number" && id > 0 ? id : null;
}
/** Steam's library context menu component, or null where this Steam build hides it. */
function findLibraryContextMenu() {
    try {
        const module = DFL.findModuleByExport((e) => {
            try {
                return typeof e?.toString === "function" && e.toString().includes("().LibraryContextMenu");
            }
            catch {
                return false;
            }
        });
        if (!module)
            return null;
        const render = Object.values(module).find((sibling) => {
            try {
                const text = sibling?.toString?.() ?? "";
                return text.includes("createElement") && text.includes("navigator:");
            }
            catch {
                return false;
            }
        });
        return render ? DFL.fakeRenderComponent(render).type : null;
    }
    catch {
        return null;
    }
}
/** Patches [menu] to carry the Fuse entries; returns how to undo it, which never throws. */
function patchContextMenu(menu) {
    if (!menu?.prototype || typeof menu.prototype.render !== "function")
        return () => { };
    let errors = 0;
    let patch;
    const undo = () => {
        try {
            patch?.unpatch();
        }
        catch {
            // Already gone with Steam's UI: nothing of Fuse's is left to undo.
        }
        patch = undefined;
    };
    try {
        patch = DFL.afterPatch(menu.prototype, "render", (_, component) => {
            if (errors >= MAX_ERRORS)
                return component;
            try {
                const appid = appidOf(component);
                if (appid != null)
                    splice(component?.props?.children, appid);
            }
            catch {
                // Steam's menu stays as Steam made it; repeated trouble takes Fuse out of it altogether.
                errors++;
                if (errors >= MAX_ERRORS)
                    setTimeout(undo, 0);
            }
            return component;
        });
    }
    catch {
        patch = undefined;
    }
    return undo;
}

/**
 * Fuse's mark, drawn from the same paths as the app's: the rounded frame, the fuse line and its
 * spark. It takes the colour of the text around it, so it fits Steam's light and dark menus.
 */
const FuseMark = ({ size = 20 }) => (window.SP_REACT.createElement("svg", { width: size, height: size, viewBox: "0 0 100 100", fill: "none", "aria-hidden": "true" },
    window.SP_REACT.createElement("path", { d: "M49.8 5h.4c15.68 0 23.52 0 29.51 3.05a28 28 0 0 1 12.24 12.24C95 26.28 95 34.12 95 49.8v.4c0 15.68 0 23.52-3.05 29.51a28 28 0 0 1-12.24 12.24C73.72 95 65.88 95 50.2 95h-.4c-15.68 0-23.52 0-29.51-3.05A28 28 0 0 1 8.05 79.71C5 73.72 5 65.88 5 50.2v-.4c0-15.68 0-23.52 3.05-29.51A28 28 0 0 1 20.29 8.05C26.28 5 34.12 5 49.8 5Z", stroke: "currentColor", strokeWidth: 10 }),
    window.SP_REACT.createElement("path", { d: "M28 70c14 0 16-36 36-36 3.2 0 5.2-1.5 6-4", stroke: "currentColor", strokeWidth: 10, strokeLinecap: "round" }),
    window.SP_REACT.createElement("circle", { cx: 70, cy: 30, r: 9, fill: "#FF6A3D" })));

const ASSET = { capsule: 0, hero: 1, logo: 2, header: 3, icon: 4 };
const EVERY_MS = 2000;
/** Makes [c] in Steam; the entry's id in Steam, or throws with why it couldn't. */
async function make(c) {
    const apps = SteamClient?.Apps;
    if (!apps)
        throw new Error("Steam's client isn't reachable from this plugin");
    switch (c.op) {
        case "ADD": {
            const appId = await apps.AddShortcut(c.name, c.exe, c.startDir, c.launchOptions);
            if (typeof appId !== "number" || appId <= 0)
                throw new Error("Steam didn't make the entry");
            // Some Steam builds name a new shortcut after its program: the name Fuse gave it, then.
            apps.SetShortcutName(appId, c.name);
            apps.SetShortcutLaunchOptions(appId, c.launchOptions);
            await pictures(c, appId);
            return appId;
        }
        case "UPDATE": {
            if (!c.appId)
                throw new Error("No entry to change");
            // Its name is the person's: only how it starts changes.
            apps.SetShortcutExe(c.appId, c.exe);
            apps.SetShortcutStartDir(c.appId, c.startDir);
            apps.SetShortcutLaunchOptions(c.appId, c.launchOptions);
            await pictures(c, c.appId);
            return c.appId;
        }
        case "REMOVE": {
            if (!c.appId)
                throw new Error("No entry to take out");
            apps.RemoveShortcut(c.appId);
            return c.appId;
        }
    }
    throw new Error("Unknown change");
}
/** Gives the entry the pictures Fuse sends with the change; a picture Steam refuses doesn't undo the entry. */
async function pictures(c, appId) {
    for (const kind of Object.keys(c.art ?? {})) {
        const type = ASSET[kind];
        if (type === undefined)
            continue;
        try {
            const art = await changeArt(c.id, kind);
            if (failure(art) || !art.data || (art.format !== "png" && art.format !== "jpg"))
                continue;
            await SteamClient.Apps.SetCustomArtworkForApp(appId, art.data, art.format, type);
        }
        catch {
            // The entry stays; its picture can be given again from Fuse.
        }
    }
}
let busy = false;
let watching = new Set();
/** One round: the waiting changes, each made and reported in turn. Never throws. */
async function round() {
    if (busy)
        return;
    busy = true;
    try {
        const answer = await steamChanges();
        if (!failure(answer) && Array.isArray(answer.watch))
            watching = new Set(answer.watch);
        // What Fuse would say on its own screen while a game is in front of it in Game Mode.
        if (!failure(answer))
            for (const notice of answer.notices ?? [])
                toaster.toast({ title: "Fuse", body: notice });
        if (failure(answer) || !answer.changes?.length)
            return;
        for (const c of answer.changes) {
            try {
                const appId = await make(c);
                await changeDone(c.id, true, appId ?? null, null);
            }
            catch (e) {
                await changeDone(c.id, false, null, e instanceof Error ? e.message : String(e)).catch(() => undefined);
            }
        }
    }
    catch {
        // Fuse isn't running or answering: the next round asks again.
    }
    finally {
        busy = false;
    }
}
/** Notes the starts and stops of Fuse's Direct entries; nothing else Steam runs is noted. */
function watchSessions() {
    watched()
        .then((w) => {
        if (Array.isArray(w?.watch))
            watching = new Set(w.watch);
    })
        .catch(() => undefined);
    try {
        const sub = SteamClient?.GameSessions?.RegisterForAppLifetimeNotifications?.((n) => {
            if (n && watching.has(n.unAppID))
                void played(n.unAppID, !!n.bRunning).catch(() => undefined);
        });
        return () => {
            try {
                sub?.unregister?.();
            }
            catch {
                // Steam already let go of it.
            }
        };
    }
    catch {
        return () => { };
    }
}
/** Starts asking; the function it returns stops it. */
function startLiveChanges() {
    const timer = setInterval(() => void round(), EVERY_MS);
    const stopWatching = watchSessions();
    void round();
    return () => {
        clearInterval(timer);
        stopWatching();
    };
}

/**
 * What the plugin reads from Steam's own client, defensively: each lookup returns nothing rather
 * than throwing when Steam changes its internals, so the plugin degrades instead of breaking Steam.
 */
/** Steam's own app ids of the games installed on this device (not non-Steam shortcuts). */
function installedSteamGames() {
    try {
        const apps = collectionStore?.localGamesCollection?.allApps ?? [];
        return apps
            .filter((a) => a && typeof a.appid === "number" && a.app_type === 1)
            .map((a) => a.appid);
    }
    catch {
        return [];
    }
}
/** The non-Steam shortcut that starts Fuse, when one is in this Steam library. */
function fuseShortcut() {
    try {
        const apps = collectionStore?.deckDesktopApps?.allApps ?? collectionStore?.allAppsCollection?.allApps ?? [];
        const fuse = apps.find((a) => a && a.app_type === 1073741824 && typeof a.display_name === "string" && a.display_name.trim().toLowerCase() === "fuse");
        return fuse ? { appid: fuse.appid, gameid: String(fuse.m_gameid ?? fuse.gameid ?? "") } : null;
    }
    catch {
        return null;
    }
}
/** Starts Fuse through its Steam shortcut. False when there is none, or Steam refused. */
function startFuse() {
    const fuse = fuseShortcut();
    if (!fuse || !fuse.gameid)
        return false;
    try {
        SteamClient.Apps.RunGame(fuse.gameid, "", -1, 100);
        return true;
    }
    catch {
        return false;
    }
}

/** How often the panel asks again while it is open. Closed, it asks nothing at all. */
const REFRESH_MS = 5000;
function usePoll(load, every = REFRESH_MS) {
    const [value, setValue] = SP_REACT.useState(null);
    const [round, setRound] = SP_REACT.useState(0);
    SP_REACT.useEffect(() => {
        let alive = true;
        const tick = async () => {
            try {
                const v = await load();
                if (alive)
                    setValue(v);
            }
            catch {
                if (alive)
                    setValue(null);
            }
        };
        tick();
        const timer = setInterval(tick, every);
        return () => {
            alive = false;
            clearInterval(timer);
        };
    }, [round]);
    return [value, () => setRound((r) => r + 1)];
}
function say(answer, done) {
    toaster.toast({ title: "Fuse", body: failure(answer) ?? answer?.message ?? done });
}
/** Fuse itself: running or not, which version, who is playing. */
const Connection = ({ hi }) => {
    const problem = failure(hi);
    return (window.SP_REACT.createElement(DFL.PanelSection, { title: "Fuse" },
        window.SP_REACT.createElement(DFL.PanelSectionRow, null,
            window.SP_REACT.createElement(DFL.Field, { focusable: true, label: problem ? "Not connected" : `Fuse ${hi?.version ?? ""}`, description: problem ?? (hi?.profile ? `Playing: ${hi.profile}` : "Connected") })),
        problem && (window.SP_REACT.createElement(DFL.PanelSectionRow, null,
            window.SP_REACT.createElement(DFL.ButtonItem, { layout: "below", onClick: () => { if (!startFuse())
                    toaster.toast({ title: "Fuse", body: "Add Fuse to Steam from Fuse's settings to start it from here." }); } }, "Open Fuse")))));
};
/** The game Steam is running (or was last showing): in Fuse or not, and the one action that fits. */
const ThisGame = () => {
    const running = DFL.Router?.MainRunningApp;
    const appid = typeof running?.appid === "number" && running.appid > 0 ? running.appid : null;
    const [known, refresh] = usePoll(async () => (appid ? steamGame(appid) : null));
    if (!appid || !known || failure(known))
        return null;
    return (window.SP_REACT.createElement(DFL.PanelSection, { title: running?.display_name ?? "This game" },
        window.SP_REACT.createElement(DFL.PanelSectionRow, null, known.inFuse ? (window.SP_REACT.createElement(DFL.ButtonItem, { layout: "below", onClick: async () => say(await openGame(appid), "Opened in Fuse") }, "Open in Fuse")) : (window.SP_REACT.createElement(DFL.ButtonItem, { layout: "below", onClick: async () => { say(await addGame(appid), "Added to Fuse"); refresh(); } }, "Add to Fuse")))));
};
/** "3 h 20 min", "45 min", or "" for nothing. */
function duration(seconds) {
    const minutes = Math.round(seconds / 60);
    if (minutes < 1)
        return "";
    const h = Math.floor(minutes / 60);
    const m = minutes % 60;
    return h > 0 ? (m > 0 ? `${h} h ${m} min` : `${h} h`) : `${m} min`;
}
/** "Today", "Yesterday", "3 days ago" for a time in milliseconds. */
function ago(ms) {
    if (!ms)
        return "";
    const days = Math.floor((Date.now() - ms) / 86400000);
    if (days <= 0)
        return "Today";
    if (days === 1)
        return "Yesterday";
    if (days < 30)
        return `${days} days ago`;
    return new Date(ms).toLocaleDateString();
}
/** Fuse's own library: what is being played, how much there is, and the last games played, each one press from opening in Fuse. */
const FuseLibrary = ({ canOpen }) => {
    const [lib] = usePoll(() => library(), 10000);
    if (!lib || failure(lib))
        return null;
    const recent = lib.recent ?? [];
    const week = duration(lib.weekSeconds ?? 0);
    return (window.SP_REACT.createElement(DFL.PanelSection, { title: "Library" },
        window.SP_REACT.createElement(DFL.PanelSectionRow, null,
            window.SP_REACT.createElement(DFL.Field, { focusable: true, label: lib.playing ? `Playing ${lib.playing}` : `${lib.games ?? 0} games`, description: [lib.playing ? `${lib.games ?? 0} games` : null, `${lib.systems ?? 0} systems`, week ? `${week} this week` : null].filter(Boolean).join("  ·  ") })),
        recent.length === 0 && (window.SP_REACT.createElement(DFL.PanelSectionRow, null,
            window.SP_REACT.createElement(DFL.Field, { focusable: true, label: "Nothing played yet", description: "Games you play in Fuse show here." }))),
        recent.map((g) => (window.SP_REACT.createElement(DFL.PanelSectionRow, { key: g.id }, canOpen ? (window.SP_REACT.createElement(DFL.ButtonItem, { layout: "below", label: g.name, description: [g.system, ago(g.lastPlayed), duration(g.seconds)].filter(Boolean).join("  ·  "), onClick: async () => say(await openFuseGame(g.id), "Opened in Fuse") }, "Open in Fuse")) : (window.SP_REACT.createElement(DFL.Field, { focusable: true, label: g.name, description: [g.system, ago(g.lastPlayed)].filter(Boolean).join("  ·  ") })))))));
};
/** Fuse One: who is playing, how saves stand, the household's devices, and what is moving. */
const FuseOne = ({ s }) => {
    const one = s?.fuseOne;
    if (!s || failure(s))
        return null;
    if (!one?.available) {
        return (window.SP_REACT.createElement(DFL.PanelSection, { title: "Fuse One" },
            window.SP_REACT.createElement(DFL.PanelSectionRow, null,
                window.SP_REACT.createElement(DFL.Field, { focusable: true, label: "Not set up", description: "Saves, profiles and downloads across your devices. Set it up in Fuse: Settings, Fuse One." }))));
    }
    const t = s.transfers;
    const moving = t ? t.downloading + t.uploading : 0;
    return (window.SP_REACT.createElement(DFL.PanelSection, { title: "Fuse One" },
        window.SP_REACT.createElement(DFL.PanelSectionRow, null,
            window.SP_REACT.createElement(DFL.Field, { focusable: true, label: one.state ?? "Fuse One", description: one.detail })),
        (one.devices ?? []).slice(0, 6).map((d) => (window.SP_REACT.createElement(DFL.PanelSectionRow, { key: d.name },
            window.SP_REACT.createElement(DFL.Field, { focusable: true, label: d.name, description: d.platform })))),
        t && (moving > 0 || t.queued > 0 || t.failed > 0) && (window.SP_REACT.createElement(DFL.PanelSectionRow, null,
            window.SP_REACT.createElement(DFL.Field, { focusable: true, label: moving > 0 ? `${moving} moving` : t.queued > 0 ? `${t.queued} waiting` : "Transfers", description: [t.progress != null ? `${Math.round(t.progress * 100)}%` : null, t.failed > 0 ? `${t.failed} failed` : null].filter(Boolean).join("  ·  ") || undefined })))));
};
function confirm(title, body, run) {
    DFL.showModal(window.SP_REACT.createElement(DFL.ConfirmModal, { strTitle: title, strDescription: body, strOKButtonText: "Continue", onOK: () => { run(); } }));
}
/** Bringing Steam's games into Fuse, all at once. One game at a time is in each game's own menu. */
const SteamGames = () => (window.SP_REACT.createElement(DFL.PanelSection, { title: "Steam games in Fuse" },
    window.SP_REACT.createElement(DFL.PanelSectionRow, null,
        window.SP_REACT.createElement(DFL.ButtonItem, { layout: "below", onClick: () => {
                const ids = installedSteamGames();
                if (ids.length === 0) {
                    toaster.toast({ title: "Fuse", body: "No installed Steam games found." });
                    return;
                }
                confirm(`Add ${ids.length} Steam games to Fuse?`, "They start through Steam as always. Remove any of them later from its menu, or from Fuse.", async () => {
                    say(await importGames(ids), "Added to Fuse");
                });
            } }, "Add all installed games"))));
/** Fuse's emulators: what has an update, and the three whole-library actions. */
const Emulators = () => {
    const [e, refresh] = usePoll(() => emulators(), 15000);
    if (!e || failure(e) || !e.supported)
        return null;
    const updates = e.updates ?? [];
    return (window.SP_REACT.createElement(DFL.PanelSection, { title: "Emulators" },
        window.SP_REACT.createElement(DFL.PanelSectionRow, null,
            window.SP_REACT.createElement(DFL.Field, { focusable: true, label: updates.length > 0 ? `${updates.length} ${updates.length === 1 ? "update" : "updates"}` : "Up to date", description: updates.slice(0, 3).map((u) => u.name).join(", ") || `${e.installed ?? 0} installed` })),
        window.SP_REACT.createElement(DFL.PanelSectionRow, null,
            window.SP_REACT.createElement(DFL.ButtonItem, { layout: "below", disabled: updates.length === 0, onClick: async () => { say(await updateAllEmulators(), "Updating in Fuse"); refresh(); } }, "Update all")),
        window.SP_REACT.createElement(DFL.PanelSectionRow, null,
            window.SP_REACT.createElement(DFL.ButtonItem, { layout: "below", onClick: () => confirm("Install every emulator?", "Fuse installs each emulator its Store offers for this device. Progress shows in Fuse.", async () => say(await installAllEmulators(), "Installing in Fuse")) }, "Install all")),
        window.SP_REACT.createElement(DFL.PanelSectionRow, null,
            window.SP_REACT.createElement(DFL.ButtonItem, { layout: "below", onClick: () => confirm("Uninstall every emulator Fuse installed?", "Your games and saves stay. Emulators you installed yourself stay too.", async () => say(await uninstallAllEmulators(), "Uninstalling in Fuse")) }, "Uninstall all"))));
};
/** A part of the plugin Fuse doesn't let it use, and where the person can allow it. */
const NotAllowed = ({ title, what }) => (window.SP_REACT.createElement(DFL.PanelSection, { title: title },
    window.SP_REACT.createElement(DFL.PanelSectionRow, null,
        window.SP_REACT.createElement(DFL.Field, { focusable: true, label: "Not allowed", description: `Fuse doesn't let this plugin ${what}. Allow it in Fuse: Settings, Library, Decky.` }))));
const Content = () => {
    const [hi] = usePoll(() => hello());
    const [s] = usePoll(() => status());
    const connected = !failure(hi);
    return (window.SP_REACT.createElement(window.SP_REACT.Fragment, null,
        window.SP_REACT.createElement(Connection, { hi: hi }),
        connected && may(hi, "library") && window.SP_REACT.createElement(ThisGame, null),
        connected && may(hi, "library") && window.SP_REACT.createElement(FuseLibrary, { canOpen: may(hi, "play") }),
        connected && may(hi, "status") && window.SP_REACT.createElement(FuseOne, { s: s }),
        connected && may(hi, "change_library") && window.SP_REACT.createElement(SteamGames, null),
        connected && (may(hi, "emulators") ? window.SP_REACT.createElement(Emulators, null) : window.SP_REACT.createElement(NotAllowed, { title: "Emulators", what: "install and remove emulators" }))));
};
var index = definePlugin(() => {
    // Steam's game menu gets the Fuse entries where this Steam build allows it, unless the person
    // turned them off. Nothing here may throw: a failing plugin must never take Decky down with it.
    let unpatch = () => { };
    let unloaded = false;
    // Fuse's changes to Steam's library, made through Steam's running client while it runs.
    let stopLive = () => { };
    try {
        stopLive = startLiveChanges();
    }
    catch {
        stopLive = () => { };
    }
    menuOff()
        .catch(() => true)
        .then((off) => {
        if (off || unloaded)
            return;
        try {
            const menu = findLibraryContextMenu();
            if (menu)
                unpatch = patchContextMenu(menu);
        }
        catch {
            unpatch = () => { };
        }
    });
    return {
        name: "Fuse",
        titleView: (window.SP_REACT.createElement("div", { className: DFL.staticClasses.Title, style: { display: "flex", alignItems: "center", gap: 8 } },
            window.SP_REACT.createElement(FuseMark, { size: 18 }),
            " Fuse")),
        content: window.SP_REACT.createElement(Content, null),
        icon: window.SP_REACT.createElement(FuseMark, null),
        onDismount() {
            unloaded = true;
            try {
                stopLive();
            }
            catch {
                // Nothing is asked any more either way.
            }
            try {
                unpatch();
            }
            catch {
                // Nothing of Fuse's is left in Steam's UI.
            }
        },
    };
});

export { index as default };
//# sourceMappingURL=index.js.map
