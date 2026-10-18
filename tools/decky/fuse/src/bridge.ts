import { callable } from "@decky/api";

/** Whether the person turned off Fuse's entries in Steam's menu (the kill switch). Fails closed: off. */
export const menuOff = callable<[], boolean>("menu_off");

/** An answer the backend couldn't get from Fuse, in words for the person. */
export interface Failure {
  error?: string;
  message?: string;
}

export interface Hello extends Failure {
  app?: string;
  version?: string;
  protocol?: number;
  profile?: string | null;
  /** What Fuse lets the plugin do (status, library, play, change_library, emulators); absent from older Fuse. */
  allowed?: string[];
}

/** Whether Fuse lets the plugin do [scope]: an older Fuse that doesn't say lets it do everything it offers. */
export function may(hi: Hello | null | undefined, scope: string): boolean {
  return !hi?.allowed || hi.allowed.includes(scope);
}

export interface Device {
  name: string;
  platform: string;
  lastSeen: number;
}

export interface Status extends Failure {
  fuseOne?: {
    available: boolean;
    state?: string;
    detail?: string;
    ok?: boolean | null;
    profile?: string | null;
    devices?: Device[];
  };
  transfers?: {
    downloading: number;
    uploading: number;
    queued: number;
    failed: number;
    progress?: number;
  };
}

export interface SteamGame extends Failure {
  appId?: number;
  inFuse?: boolean;
}

export interface Result extends Failure {
  added?: number;
  removed?: boolean;
  opened?: boolean;
  started?: boolean;
}

export interface RecentGame {
  id: number;
  name: string;
  system: string;
  lastPlayed?: number;
  seconds: number;
}

export interface Library extends Failure {
  games?: number;
  systems?: number;
  playing?: string;
  weekSeconds?: number;
  recent?: RecentGame[];
}

export interface Emulators extends Failure {
  supported?: boolean;
  installed?: number;
  updates?: { key: string; name: string }[];
  busy?: number;
}

/** A change Fuse asks Steam's running client to make (bridge protocol 2). */
export interface SteamChange {
  id: number;
  op: "ADD" | "UPDATE" | "REMOVE";
  gameId: number;
  appId?: number | null;
  name: string;
  exe: string;
  startDir: string;
  launchOptions: string;
  /** The pictures Fuse has for it: each kind (capsule, hero, logo, header, icon) to its format, fetched with [changeArt]. */
  art?: Record<string, string>;
}

export interface SteamChanges extends Failure {
  changes?: SteamChange[];
  /** Fuse's Direct entries, by their id in Steam, whose starts and stops are noted for their time. */
  watch?: number[];
  /** What Fuse would say on its own screen while a game is in front of it, for Steam to show. */
  notices?: string[];
}

export interface ChangeArt extends Failure {
  format?: "png" | "jpg";
  data?: string;
}

export const hello = callable<[], Hello>("hello");
export const status = callable<[], Status>("status");
export const steamGame = callable<[appId: number], SteamGame>("steam_game");
export const addGame = callable<[appId: number], Result>("add_game");
export const removeGame = callable<[appId: number], Result>("remove_game");
export const openGame = callable<[appId: number], Result>("open_game");
export const importGames = callable<[appIds: number[]], Result>("import_games");
export const library = callable<[], Library>("library");
export const openFuseGame = callable<[gameId: number], Result>("open_fuse_game");
export const emulators = callable<[], Emulators>("emulators");
export const updateAllEmulators = callable<[], Result>("emulators_update_all");
export const installAllEmulators = callable<[], Result>("emulators_install_all");
export const uninstallAllEmulators = callable<[], Result>("emulators_uninstall_all");
export const steamChanges = callable<[], SteamChanges>("steam_changes");
export const changeArt = callable<[changeId: number, kind: string], ChangeArt>("change_art");
export const watched = callable<[], { watch?: number[] }>("watched");
export const played = callable<[appId: number, running: boolean], { noted?: boolean }>("played");
export const changeDone = callable<[changeId: number, ok: boolean, appId: number | null, message: string | null], Result>("change_done");

/** The words to show for an answer that failed; null when it didn't. */
export function failure(answer: Failure | null | undefined): string | null {
  if (!answer) return "Fuse isn't answering.";
  return answer.error ? answer.message ?? "Fuse couldn't do that." : null;
}
