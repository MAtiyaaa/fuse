import { callable } from "@decky/api";

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

export interface Emulators extends Failure {
  supported?: boolean;
  installed?: number;
  updates?: { key: string; name: string }[];
  busy?: number;
}

export const hello = callable<[], Hello>("hello");
export const status = callable<[], Status>("status");
export const steamGame = callable<[appId: number], SteamGame>("steam_game");
export const addGame = callable<[appId: number], Result>("add_game");
export const removeGame = callable<[appId: number], Result>("remove_game");
export const openGame = callable<[appId: number], Result>("open_game");
export const importGames = callable<[appIds: number[]], Result>("import_games");
export const emulators = callable<[], Emulators>("emulators");
export const updateAllEmulators = callable<[], Result>("emulators_update_all");
export const installAllEmulators = callable<[], Result>("emulators_install_all");
export const uninstallAllEmulators = callable<[], Result>("emulators_uninstall_all");

/** The words to show for an answer that failed; null when it didn't. */
export function failure(answer: Failure | null | undefined): string | null {
  if (!answer) return "Fuse isn't answering.";
  return answer.error ? answer.message ?? "Fuse couldn't do that." : null;
}
