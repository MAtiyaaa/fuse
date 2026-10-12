import { afterPatch, fakeRenderComponent, findInReactTree, findModuleByExport, MenuItem, Patch } from "@decky/ui";
import { toaster } from "@decky/api";
import { FC } from "react";
import { addGame, failure, openGame, removeGame, steamGame } from "./bridge";

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

function entries(appid: number): JSX.Element[] {
  const act = async (run: () => Promise<{ error?: string; message?: string }>, done: string) => {
    try {
      const answer = await run();
      toaster.toast({ title: "Fuse", body: failure(answer) ?? answer.message ?? done });
    } catch {
      toaster.toast({ title: "Fuse", body: "Fuse didn't answer. Open Fuse, then try again." });
    }
  };
  return [
    <MenuItem
      key={MARK}
      onSelected={async () => {
        try {
          const known = await steamGame(appid);
          if (failure(known)) {
            toaster.toast({ title: "Fuse", body: failure(known)! });
            return;
          }
          if (known.inFuse) await act(() => openGame(appid), "Opened in Fuse");
          else await act(() => addGame(appid), "Added to Fuse");
        } catch {
          toaster.toast({ title: "Fuse", body: "Fuse didn't answer. Open Fuse, then try again." });
        }
      }}
    >
      Fuse: Add or Open
    </MenuItem>,
    <MenuItem key={MARK + "-remove"} onSelected={() => act(() => removeGame(appid), "Removed from Fuse")}>
      Fuse: Remove from Fuse
    </MenuItem>,
  ];
}

/** Adds the entries once (by key), above Properties, without touching anything else in the list. */
function splice(children: any[], appid: number) {
  if (!Array.isArray(children) || children.some((c) => c?.key === MARK)) return;
  const properties = children.findIndex((item) =>
    findInReactTree(item, (x: any) => typeof x?.onSelected === "function" && x.onSelected.toString().includes("AppProperties")),
  );
  children.splice(properties >= 0 ? properties : children.length, 0, ...entries(appid));
}

function appidOf(component: any): number | null {
  const id = component?._owner?.pendingProps?.overview?.appid ?? component?.props?.overview?.appid;
  return typeof id === "number" && id > 0 ? id : null;
}

/** Steam's library context menu component, or null where this Steam build hides it. */
export function findLibraryContextMenu(): any {
  try {
    const module = findModuleByExport((e: any) => {
      try {
        return typeof e?.toString === "function" && e.toString().includes("().LibraryContextMenu");
      } catch {
        return false;
      }
    });
    if (!module) return null;
    const render = Object.values(module).find((sibling: any) => {
      try {
        const text = sibling?.toString?.() ?? "";
        return text.includes("createElement") && text.includes("navigator:");
      } catch {
        return false;
      }
    }) as FC | undefined;
    return render ? fakeRenderComponent(render).type : null;
  } catch {
    return null;
  }
}

/** Patches [menu] to carry the Fuse entries; returns how to undo it, which never throws. */
export function patchContextMenu(menu: any): () => void {
  if (!menu?.prototype || typeof menu.prototype.render !== "function") return () => {};
  let errors = 0;
  let patch: Patch | undefined;
  const undo = () => {
    try {
      patch?.unpatch();
    } catch {
      // Already gone with Steam's UI: nothing of Fuse's is left to undo.
    }
    patch = undefined;
  };
  try {
    patch = afterPatch(menu.prototype, "render", (_: unknown[], component: any) => {
      if (errors >= MAX_ERRORS) return component;
      try {
        const appid = appidOf(component);
        if (appid != null) splice(component?.props?.children, appid);
      } catch {
        // Steam's menu stays as Steam made it; repeated trouble takes Fuse out of it altogether.
        errors++;
        if (errors >= MAX_ERRORS) setTimeout(undo, 0);
      }
      return component;
    });
  } catch {
    patch = undefined;
  }
  return undo;
}
