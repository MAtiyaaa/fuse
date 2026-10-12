/**
 * What the plugin reads from Steam's own client, defensively: each lookup returns nothing rather
 * than throwing when Steam changes its internals, so the plugin degrades instead of breaking Steam.
 */

declare const collectionStore: any;
declare const SteamClient: any;

/** Steam's own app ids of the games installed on this device (not non-Steam shortcuts). */
export function installedSteamGames(): number[] {
  try {
    const apps: any[] = collectionStore?.localGamesCollection?.allApps ?? [];
    return apps
      .filter((a) => a && typeof a.appid === "number" && a.app_type === 1)
      .map((a) => a.appid as number);
  } catch {
    return [];
  }
}

/** The non-Steam shortcut that starts Fuse, when one is in this Steam library. */
export function fuseShortcut(): { appid: number; gameid: string } | null {
  try {
    const apps: any[] = collectionStore?.deckDesktopApps?.allApps ?? collectionStore?.allAppsCollection?.allApps ?? [];
    const fuse = apps.find((a) => a && a.app_type === 1073741824 && typeof a.display_name === "string" && a.display_name.trim().toLowerCase() === "fuse");
    return fuse ? { appid: fuse.appid, gameid: String(fuse.m_gameid ?? fuse.gameid ?? "") } : null;
  } catch {
    return null;
  }
}

/** Starts Fuse through its Steam shortcut. False when there is none, or Steam refused. */
export function startFuse(): boolean {
  const fuse = fuseShortcut();
  if (!fuse || !fuse.gameid) return false;
  try {
    SteamClient.Apps.RunGame(fuse.gameid, "", -1, 100);
    return true;
  } catch {
    return false;
  }
}
