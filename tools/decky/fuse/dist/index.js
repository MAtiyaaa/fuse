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

const hello = callable("hello");
const status = callable("status");
const steamGame = callable("steam_game");
const addGame = callable("add_game");
const removeGame = callable("remove_game");
const openGame = callable("open_game");
const importGames = callable("import_games");
const emulators = callable("emulators");
const updateAllEmulators = callable("emulators_update_all");
const installAllEmulators = callable("emulators_install_all");
const uninstallAllEmulators = callable("emulators_uninstall_all");
/** The words to show for an answer that failed; null when it didn't. */
function failure(answer) {
    if (!answer)
        return "Fuse isn't answering.";
    return answer.error ? answer.message ?? "Fuse couldn't do that." : null;
}

/**
 * "Add to Fuse" in Steam's own game menu (the ... on a game's page and in the library), placed just
 * above Properties. The entry asks Fuse whether the game is already in its library when the menu
 * opens, then offers Add to Fuse, or Open in Fuse and Remove from Fuse. Every lookup into Steam's
 * internals is guarded: if Steam changes them, the menu simply has no Fuse entry and Steam is
 * untouched; the Quick Access panel still offers the same actions.
 */
const MARK = "fuse-menu";
function entries(appid) {
    const act = async (run, done) => {
        const answer = await run();
        toaster.toast({ title: "Fuse", body: failure(answer) ?? answer.message ?? done });
    };
    return [
        window.SP_REACT.createElement(DFL.MenuItem, { key: MARK, onSelected: async () => {
                const known = await steamGame(appid);
                if (failure(known)) {
                    toaster.toast({ title: "Fuse", body: failure(known) });
                    return;
                }
                if (known.inFuse)
                    await act(() => openGame(appid), "Opened in Fuse");
                else
                    await act(() => addGame(appid), "Added to Fuse");
            } }, "Fuse: Add or Open"),
        window.SP_REACT.createElement(DFL.MenuItem, { key: MARK + "-remove", onSelected: () => act(() => removeGame(appid), "Removed from Fuse") }, "Fuse: Remove from Fuse"),
    ];
}
function splice(children, appid) {
    if (!Array.isArray(children) || children.some((c) => c?.key === MARK))
        return;
    const properties = children.findIndex((item) => DFL.findInReactTree(item, (x) => x?.onSelected && x.onSelected.toString().includes("AppProperties")));
    children.splice(properties >= 0 ? properties : children.length, 0, ...entries(appid));
}
function appidOf(component) {
    const id = component?._owner?.pendingProps?.overview?.appid ?? component?.props?.overview?.appid;
    return typeof id === "number" && id > 0 ? id : null;
}
/** Steam's library context menu component, or null where this Steam build hides it. */
function findLibraryContextMenu() {
    try {
        const module = DFL.findModuleByExport((e) => e?.toString && e.toString().includes("().LibraryContextMenu"));
        if (!module)
            return null;
        const render = Object.values(module).find((sibling) => sibling?.toString().includes("createElement") && sibling?.toString().includes("navigator:"));
        return render ? DFL.fakeRenderComponent(render).type : null;
    }
    catch {
        return null;
    }
}
/** Patches [menu] to carry the Fuse entries; returns how to undo it. */
function patchContextMenu(menu) {
    if (!menu?.prototype)
        return () => { };
    let inner;
    const outer = DFL.afterPatch(menu.prototype, "render", (_, component) => {
        try {
            const appid = appidOf(component);
            if (appid == null)
                return component;
            if (!inner && component?.type?.prototype) {
                inner = DFL.afterPatch(component.type.prototype, "shouldComponentUpdate", ([next], update) => {
                    try {
                        const id = next?.overview?.appid ?? appid;
                        if (update === true)
                            splice(next?.children, id);
                    }
                    catch {
                        // Steam's menu stays as Steam made it.
                    }
                    return update;
                });
            }
            else {
                splice(component?.props?.children, appid);
            }
        }
        catch {
            // Steam's menu stays as Steam made it.
        }
        return component;
    });
    return () => {
        outer?.unpatch();
        inner?.unpatch();
    };
}

/**
 * Fuse's mark, drawn from the same paths as the app's: the rounded frame, the fuse line and its
 * spark. It takes the colour of the text around it, so it fits Steam's light and dark menus.
 */
const FuseMark = ({ size = 20 }) => (window.SP_REACT.createElement("svg", { width: size, height: size, viewBox: "0 0 100 100", fill: "none", "aria-hidden": "true" },
    window.SP_REACT.createElement("path", { d: "M49.8 5h.4c15.68 0 23.52 0 29.51 3.05a28 28 0 0 1 12.24 12.24C95 26.28 95 34.12 95 49.8v.4c0 15.68 0 23.52-3.05 29.51a28 28 0 0 1-12.24 12.24C73.72 95 65.88 95 50.2 95h-.4c-15.68 0-23.52 0-29.51-3.05A28 28 0 0 1 8.05 79.71C5 73.72 5 65.88 5 50.2v-.4c0-15.68 0-23.52 3.05-29.51A28 28 0 0 1 20.29 8.05C26.28 5 34.12 5 49.8 5Z", stroke: "currentColor", strokeWidth: 10 }),
    window.SP_REACT.createElement("path", { d: "M28 70c14 0 16-36 36-36 3.2 0 5.2-1.5 6-4", stroke: "currentColor", strokeWidth: 10, strokeLinecap: "round" }),
    window.SP_REACT.createElement("circle", { cx: 70, cy: 30, r: 9, fill: "#FF6A3D" })));

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
            window.SP_REACT.createElement(DFL.Field, { label: problem ? "Not connected" : `Fuse ${hi?.version ?? ""}`, description: problem ?? (hi?.profile ? `Playing: ${hi.profile}` : "Connected") })),
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
/** Fuse One: who is playing, how saves stand, the household's devices, and what is moving. */
const FuseOne = ({ s }) => {
    const one = s?.fuseOne;
    if (!s || failure(s) || !one?.available)
        return null;
    const t = s.transfers;
    const moving = t ? t.downloading + t.uploading : 0;
    return (window.SP_REACT.createElement(DFL.PanelSection, { title: "Fuse One" },
        window.SP_REACT.createElement(DFL.PanelSectionRow, null,
            window.SP_REACT.createElement(DFL.Field, { label: one.state ?? "Fuse One", description: one.detail })),
        (one.devices ?? []).slice(0, 6).map((d) => (window.SP_REACT.createElement(DFL.PanelSectionRow, { key: d.name },
            window.SP_REACT.createElement(DFL.Field, { label: d.name, description: d.platform })))),
        t && (moving > 0 || t.queued > 0 || t.failed > 0) && (window.SP_REACT.createElement(DFL.PanelSectionRow, null,
            window.SP_REACT.createElement(DFL.Field, { label: moving > 0 ? `${moving} moving` : t.queued > 0 ? `${t.queued} waiting` : "Transfers", description: [t.progress != null ? `${Math.round(t.progress * 100)}%` : null, t.failed > 0 ? `${t.failed} failed` : null].filter(Boolean).join("  ·  ") || undefined })))));
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
            window.SP_REACT.createElement(DFL.Field, { label: updates.length > 0 ? `${updates.length} ${updates.length === 1 ? "update" : "updates"}` : "Up to date", description: updates.slice(0, 3).map((u) => u.name).join(", ") || `${e.installed ?? 0} installed` })),
        window.SP_REACT.createElement(DFL.PanelSectionRow, null,
            window.SP_REACT.createElement(DFL.ButtonItem, { layout: "below", disabled: updates.length === 0, onClick: async () => { say(await updateAllEmulators(), "Updating in Fuse"); refresh(); } }, "Update all")),
        window.SP_REACT.createElement(DFL.PanelSectionRow, null,
            window.SP_REACT.createElement(DFL.ButtonItem, { layout: "below", onClick: () => confirm("Install every emulator?", "Fuse installs each emulator its Store offers for this device. Progress shows in Fuse.", async () => say(await installAllEmulators(), "Installing in Fuse")) }, "Install all")),
        window.SP_REACT.createElement(DFL.PanelSectionRow, null,
            window.SP_REACT.createElement(DFL.ButtonItem, { layout: "below", onClick: () => confirm("Uninstall every emulator Fuse installed?", "Your games and saves stay. Emulators you installed yourself stay too.", async () => say(await uninstallAllEmulators(), "Uninstalling in Fuse")) }, "Uninstall all"))));
};
const Content = () => {
    const [hi] = usePoll(() => hello());
    const [s] = usePoll(() => status());
    const connected = !failure(hi);
    return (window.SP_REACT.createElement(window.SP_REACT.Fragment, null,
        window.SP_REACT.createElement(Connection, { hi: hi }),
        connected && window.SP_REACT.createElement(ThisGame, null),
        connected && window.SP_REACT.createElement(FuseOne, { s: s }),
        connected && window.SP_REACT.createElement(SteamGames, null),
        connected && window.SP_REACT.createElement(Emulators, null)));
};
var index = definePlugin(() => {
    // Steam's game menu gets the Fuse entries where this Steam build allows it.
    const menu = findLibraryContextMenu();
    const unpatch = menu ? patchContextMenu(menu) : () => { };
    return {
        name: "Fuse",
        titleView: (window.SP_REACT.createElement("div", { className: DFL.staticClasses.Title, style: { display: "flex", alignItems: "center", gap: 8 } },
            window.SP_REACT.createElement(FuseMark, { size: 18 }),
            " Fuse")),
        content: window.SP_REACT.createElement(Content, null),
        icon: window.SP_REACT.createElement(FuseMark, null),
        onDismount() {
            unpatch();
        },
    };
});

export { index as default };
//# sourceMappingURL=index.js.map
