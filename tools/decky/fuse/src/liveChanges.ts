import { toaster } from "@decky/api";
import { changeArt, changeDone, failure, played, steamChanges, SteamChange, watched } from "./bridge";

/*
 * Bridge protocol 2: Fuse's changes to Steam's library (its games added, brought up to date or taken
 * out, with their pictures) made through Steam's own running client, so Steam needn't close or
 * restart for them. Fuse queues them; this asks every couple of seconds while Steam runs, makes each
 * with SteamClient.Apps and says how it went. Steam then writes them into its library file itself
 * when it quits.
 *
 * The calls are Steam client's as @decky/ui types them (dist/globals/steam-client/App.d.ts):
 * AddShortcut(appName, executablePath, directory, launchOptions): Promise<number>, SetShortcutName,
 * SetShortcutExe, SetShortcutStartDir, SetShortcutLaunchOptions, RemoveShortcut(appId), and
 * SetCustomArtworkForApp(appId, base64, "jpg" | "png", assetType) with ELibraryAssetType
 * Capsule 0, Hero 1, Logo 2, Header 3, Icon 4. Each is guarded: when Steam changes its internals a
 * change fails and is reported, and Fuse goes the usual way (written when Steam closes).
 *
 * Time in Fuse's Direct entries (which start the emulator with no Fuse to count it): Steam says when
 * an app starts and stops through SteamClient.GameSessions.RegisterForAppLifetimeNotifications
 * (unAppID, bRunning), and for the entries Fuse names ("watch") the backend notes each session and
 * gives it to Fuse when Fuse answers, even if Fuse wasn't running while the game was.
 */

declare const SteamClient: any;

const ASSET: Record<string, number> = { capsule: 0, hero: 1, logo: 2, header: 3, icon: 4 };
const EVERY_MS = 2_000;

/** Makes [c] in Steam; the entry's id in Steam, or throws with why it couldn't. */
async function make(c: SteamChange): Promise<number | undefined> {
  const apps = SteamClient?.Apps;
  if (!apps) throw new Error("Steam's client isn't reachable from this plugin");
  switch (c.op) {
    case "ADD": {
      const appId: number = await apps.AddShortcut(c.name, c.exe, c.startDir, c.launchOptions);
      if (typeof appId !== "number" || appId <= 0) throw new Error("Steam didn't make the entry");
      // Some Steam builds name a new shortcut after its program: the name Fuse gave it, then.
      apps.SetShortcutName(appId, c.name);
      apps.SetShortcutLaunchOptions(appId, c.launchOptions);
      await pictures(c, appId);
      return appId;
    }
    case "UPDATE": {
      if (!c.appId) throw new Error("No entry to change");
      // Its name is the person's: only how it starts changes.
      apps.SetShortcutExe(c.appId, c.exe);
      apps.SetShortcutStartDir(c.appId, c.startDir);
      apps.SetShortcutLaunchOptions(c.appId, c.launchOptions);
      await pictures(c, c.appId);
      return c.appId;
    }
    case "REMOVE": {
      if (!c.appId) throw new Error("No entry to take out");
      apps.RemoveShortcut(c.appId);
      return c.appId;
    }
  }
  throw new Error("Unknown change");
}

/** Gives the entry the pictures Fuse sends with the change; a picture Steam refuses doesn't undo the entry. */
async function pictures(c: SteamChange, appId: number) {
  for (const kind of Object.keys(c.art ?? {})) {
    const type = ASSET[kind];
    if (type === undefined) continue;
    try {
      const art = await changeArt(c.id, kind);
      if (failure(art) || !art.data || (art.format !== "png" && art.format !== "jpg")) continue;
      await SteamClient.Apps.SetCustomArtworkForApp(appId, art.data, art.format, type);
    } catch {
      // The entry stays; its picture can be given again from Fuse.
    }
  }
}

let busy = false;
let watching = new Set<number>();

/** One round: the waiting changes, each made and reported in turn. Never throws. */
async function round() {
  if (busy) return;
  busy = true;
  try {
    const answer = await steamChanges();
    if (!failure(answer) && Array.isArray(answer.watch)) watching = new Set(answer.watch);
    // What Fuse would say on its own screen while a game is in front of it in Game Mode.
    if (!failure(answer)) for (const notice of answer.notices ?? []) toaster.toast({ title: "Fuse", body: notice });
    if (failure(answer) || !answer.changes?.length) return;
    for (const c of answer.changes) {
      try {
        const appId = await make(c);
        await changeDone(c.id, true, appId ?? null, null);
      } catch (e) {
        await changeDone(c.id, false, null, e instanceof Error ? e.message : String(e)).catch(() => undefined);
      }
    }
  } catch {
    // Fuse isn't running or answering: the next round asks again.
  } finally {
    busy = false;
  }
}

/** Notes the starts and stops of Fuse's Direct entries; nothing else Steam runs is noted. */
function watchSessions(): () => void {
  watched()
    .then((w) => {
      if (Array.isArray(w?.watch)) watching = new Set(w.watch);
    })
    .catch(() => undefined);
  try {
    const sub = SteamClient?.GameSessions?.RegisterForAppLifetimeNotifications?.((n: { unAppID: number; bRunning: boolean }) => {
      if (n && watching.has(n.unAppID)) void played(n.unAppID, !!n.bRunning).catch(() => undefined);
    });
    return () => {
      try {
        sub?.unregister?.();
      } catch {
        // Steam already let go of it.
      }
    };
  } catch {
    return () => {};
  }
}

/** Starts asking; the function it returns stops it. */
export function startLiveChanges(): () => void {
  const timer = setInterval(() => void round(), EVERY_MS);
  const stopWatching = watchSessions();
  void round();
  return () => {
    clearInterval(timer);
    stopWatching();
  };
}
