import { afterPatch, fakeRenderComponent, findInReactTree, findModuleByExport, MenuItem, Patch } from "@decky/ui";
import { toaster } from "@decky/api";
import { FC } from "react";
import { addGame, failure, openGame, removeGame, steamGame } from "./bridge";

/**
 * "Add to Fuse" in Steam's own game menu (the ... on a game's page and in the library), placed just
 * above Properties. The entry asks Fuse whether the game is already in its library when the menu
 * opens, then offers Add to Fuse, or Open in Fuse and Remove from Fuse. Every lookup into Steam's
 * internals is guarded: if Steam changes them, the menu simply has no Fuse entry and Steam is
 * untouched; the Quick Access panel still offers the same actions.
 */
const MARK = "fuse-menu";

function entries(appid: number): JSX.Element[] {
  const act = async (run: () => Promise<{ error?: string; message?: string }>, done: string) => {
    const answer = await run();
    toaster.toast({ title: "Fuse", body: failure(answer) ?? answer.message ?? done });
  };
  return [
    <MenuItem
      key={MARK}
      onSelected={async () => {
        const known = await steamGame(appid);
        if (failure(known)) {
          toaster.toast({ title: "Fuse", body: failure(known)! });
          return;
        }
        if (known.inFuse) await act(() => openGame(appid), "Opened in Fuse");
        else await act(() => addGame(appid), "Added to Fuse");
      }}
    >
      Fuse: Add or Open
    </MenuItem>,
    <MenuItem key={MARK + "-remove"} onSelected={() => act(() => removeGame(appid), "Removed from Fuse")}>
      Fuse: Remove from Fuse
    </MenuItem>,
  ];
}

function splice(children: any[], appid: number) {
  if (!Array.isArray(children) || children.some((c) => c?.key === MARK)) return;
  const properties = children.findIndex((item) =>
    findInReactTree(item, (x: any) => x?.onSelected && x.onSelected.toString().includes("AppProperties")),
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
    const module = findModuleByExport((e: any) => e?.toString && e.toString().includes("().LibraryContextMenu"));
    if (!module) return null;
    const render = Object.values(module).find(
      (sibling: any) => sibling?.toString().includes("createElement") && sibling?.toString().includes("navigator:"),
    ) as FC | undefined;
    return render ? fakeRenderComponent(render).type : null;
  } catch {
    return null;
  }
}

/** Patches [menu] to carry the Fuse entries; returns how to undo it. */
export function patchContextMenu(menu: any): () => void {
  if (!menu?.prototype) return () => {};
  let inner: Patch | undefined;
  const outer = afterPatch(menu.prototype, "render", (_: unknown[], component: any) => {
    try {
      const appid = appidOf(component);
      if (appid == null) return component;
      if (!inner && component?.type?.prototype) {
        inner = afterPatch(component.type.prototype, "shouldComponentUpdate", ([next]: any, update: any) => {
          try {
            const id = next?.overview?.appid ?? appid;
            if (update === true) splice(next?.children, id);
          } catch {
            // Steam's menu stays as Steam made it.
          }
          return update;
        });
      } else {
        splice(component?.props?.children, appid);
      }
    } catch {
      // Steam's menu stays as Steam made it.
    }
    return component;
  });
  return () => {
    outer?.unpatch();
    inner?.unpatch();
  };
}
