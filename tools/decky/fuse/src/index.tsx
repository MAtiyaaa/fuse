import { definePlugin, toaster } from "@decky/api";
import {
  ButtonItem,
  ConfirmModal,
  Field,
  PanelSection,
  PanelSectionRow,
  Router,
  showModal,
  staticClasses,
} from "@decky/ui";
import { useEffect, useState, VFC } from "react";
import {
  emulators,
  Emulators,
  failure,
  hello,
  Hello,
  library,
  Library,
  openFuseGame,
  menuOff,
  importGames,
  installAllEmulators,
  may,
  openGame,
  addGame,
  status,
  Status,
  steamGame,
  SteamGame,
  uninstallAllEmulators,
  updateAllEmulators,
} from "./bridge";
import { findLibraryContextMenu, patchContextMenu } from "./contextMenu";
import { FuseMark } from "./FuseMark";
import { installedSteamGames, startFuse } from "./steam";

/** How often the panel asks again while it is open. Closed, it asks nothing at all. */
const REFRESH_MS = 5_000;

function usePoll<T>(load: () => Promise<T>, every: number = REFRESH_MS): [T | null, () => void] {
  const [value, setValue] = useState<T | null>(null);
  const [round, setRound] = useState(0);
  useEffect(() => {
    let alive = true;
    const tick = async () => {
      try {
        const v = await load();
        if (alive) setValue(v);
      } catch {
        if (alive) setValue(null);
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

function say(answer: { error?: string; message?: string } | null, done: string) {
  toaster.toast({ title: "Fuse", body: failure(answer) ?? answer?.message ?? done });
}

/** Fuse itself: running or not, which version, who is playing. */
const Connection: VFC<{ hi: Hello | null }> = ({ hi }) => {
  const problem = failure(hi);
  return (
    <PanelSection title="Fuse">
      <PanelSectionRow>
        <Field label={problem ? "Not connected" : `Fuse ${hi?.version ?? ""}`} description={problem ?? (hi?.profile ? `Playing: ${hi.profile}` : "Connected")} />
      </PanelSectionRow>
      {problem && (
        <PanelSectionRow>
          <ButtonItem layout="below" onClick={() => { if (!startFuse()) toaster.toast({ title: "Fuse", body: "Add Fuse to Steam from Fuse's settings to start it from here." }); }}>
            Open Fuse
          </ButtonItem>
        </PanelSectionRow>
      )}
    </PanelSection>
  );
};

/** The game Steam is running (or was last showing): in Fuse or not, and the one action that fits. */
const ThisGame: VFC = () => {
  const running = (Router as any)?.MainRunningApp;
  const appid: number | null = typeof running?.appid === "number" && running.appid > 0 ? running.appid : null;
  const [known, refresh] = usePoll<SteamGame | null>(async () => (appid ? steamGame(appid) : null));
  if (!appid || !known || failure(known)) return null;
  return (
    <PanelSection title={running?.display_name ?? "This game"}>
      <PanelSectionRow>
        {known.inFuse ? (
          <ButtonItem layout="below" onClick={async () => say(await openGame(appid), "Opened in Fuse")}>Open in Fuse</ButtonItem>
        ) : (
          <ButtonItem layout="below" onClick={async () => { say(await addGame(appid), "Added to Fuse"); refresh(); }}>Add to Fuse</ButtonItem>
        )}
      </PanelSectionRow>
    </PanelSection>
  );
};

/** "3 h 20 min", "45 min", or "" for nothing. */
function duration(seconds: number): string {
  const minutes = Math.round(seconds / 60);
  if (minutes < 1) return "";
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h > 0 ? (m > 0 ? `${h} h ${m} min` : `${h} h`) : `${m} min`;
}

/** "Today", "Yesterday", "3 days ago" for a time in milliseconds. */
function ago(ms: number | undefined): string {
  if (!ms) return "";
  const days = Math.floor((Date.now() - ms) / 86_400_000);
  if (days <= 0) return "Today";
  if (days === 1) return "Yesterday";
  if (days < 30) return `${days} days ago`;
  return new Date(ms).toLocaleDateString();
}

/** Fuse's own library: what is being played, how much there is, and the last games played, each one press from opening in Fuse. */
const FuseLibrary: VFC<{ canOpen: boolean }> = ({ canOpen }) => {
  const [lib] = usePoll<Library>(() => library(), 10_000);
  if (!lib || failure(lib)) return null;
  const recent = lib.recent ?? [];
  const week = duration(lib.weekSeconds ?? 0);
  return (
    <PanelSection title="Library">
      <PanelSectionRow>
        <Field
          label={lib.playing ? `Playing ${lib.playing}` : `${lib.games ?? 0} games`}
          description={[lib.playing ? `${lib.games ?? 0} games` : null, `${lib.systems ?? 0} systems`, week ? `${week} this week` : null].filter(Boolean).join("  ·  ")}
        />
      </PanelSectionRow>
      {recent.length === 0 && (
        <PanelSectionRow>
          <Field label="Nothing played yet" description="Games you play in Fuse show here." />
        </PanelSectionRow>
      )}
      {recent.map((g) => (
        <PanelSectionRow key={g.id}>
          {canOpen ? (
            <ButtonItem
              layout="below"
              label={g.name}
              description={[g.system, ago(g.lastPlayed), duration(g.seconds)].filter(Boolean).join("  ·  ")}
              onClick={async () => say(await openFuseGame(g.id), "Opened in Fuse")}
            >
              Open in Fuse
            </ButtonItem>
          ) : (
            <Field label={g.name} description={[g.system, ago(g.lastPlayed)].filter(Boolean).join("  ·  ")} />
          )}
        </PanelSectionRow>
      ))}
    </PanelSection>
  );
};

/** Fuse One: who is playing, how saves stand, the household's devices, and what is moving. */
const FuseOne: VFC<{ s: Status | null }> = ({ s }) => {
  const one = s?.fuseOne;
  if (!s || failure(s)) return null;
  if (!one?.available) {
    return (
      <PanelSection title="Fuse One">
        <PanelSectionRow>
          <Field label="Not set up" description="Saves, profiles and downloads across your devices. Set it up in Fuse: Settings, Fuse One." />
        </PanelSectionRow>
      </PanelSection>
    );
  }
  const t = s.transfers;
  const moving = t ? t.downloading + t.uploading : 0;
  return (
    <PanelSection title="Fuse One">
      <PanelSectionRow>
        <Field label={one.state ?? "Fuse One"} description={one.detail} />
      </PanelSectionRow>
      {(one.devices ?? []).slice(0, 6).map((d) => (
        <PanelSectionRow key={d.name}>
          <Field label={d.name} description={d.platform} />
        </PanelSectionRow>
      ))}
      {t && (moving > 0 || t.queued > 0 || t.failed > 0) && (
        <PanelSectionRow>
          <Field
            label={moving > 0 ? `${moving} moving` : t.queued > 0 ? `${t.queued} waiting` : "Transfers"}
            description={[t.progress != null ? `${Math.round(t.progress * 100)}%` : null, t.failed > 0 ? `${t.failed} failed` : null].filter(Boolean).join("  ·  ") || undefined}
          />
        </PanelSectionRow>
      )}
    </PanelSection>
  );
};

function confirm(title: string, body: string, run: () => Promise<void>) {
  showModal(<ConfirmModal strTitle={title} strDescription={body} strOKButtonText="Continue" onOK={() => { run(); }} />);
}

/** Bringing Steam's games into Fuse, all at once. One game at a time is in each game's own menu. */
const SteamGames: VFC = () => (
  <PanelSection title="Steam games in Fuse">
    <PanelSectionRow>
      <ButtonItem
        layout="below"
        onClick={() => {
          const ids = installedSteamGames();
          if (ids.length === 0) {
            toaster.toast({ title: "Fuse", body: "No installed Steam games found." });
            return;
          }
          confirm(`Add ${ids.length} Steam games to Fuse?`, "They start through Steam as always. Remove any of them later from its menu, or from Fuse.", async () => {
            say(await importGames(ids), "Added to Fuse");
          });
        }}
      >
        Add all installed games
      </ButtonItem>
    </PanelSectionRow>
  </PanelSection>
);

/** Fuse's emulators: what has an update, and the three whole-library actions. */
const Emulators: VFC = () => {
  const [e, refresh] = usePoll<Emulators>(() => emulators(), 15_000);
  if (!e || failure(e) || !e.supported) return null;
  const updates = e.updates ?? [];
  return (
    <PanelSection title="Emulators">
      <PanelSectionRow>
        <Field label={updates.length > 0 ? `${updates.length} ${updates.length === 1 ? "update" : "updates"}` : "Up to date"} description={updates.slice(0, 3).map((u) => u.name).join(", ") || `${e.installed ?? 0} installed`} />
      </PanelSectionRow>
      <PanelSectionRow>
        <ButtonItem layout="below" disabled={updates.length === 0} onClick={async () => { say(await updateAllEmulators(), "Updating in Fuse"); refresh(); }}>
          Update all
        </ButtonItem>
      </PanelSectionRow>
      <PanelSectionRow>
        <ButtonItem layout="below" onClick={() => confirm("Install every emulator?", "Fuse installs each emulator its Store offers for this device. Progress shows in Fuse.", async () => say(await installAllEmulators(), "Installing in Fuse"))}>
          Install all
        </ButtonItem>
      </PanelSectionRow>
      <PanelSectionRow>
        <ButtonItem layout="below" onClick={() => confirm("Uninstall every emulator Fuse installed?", "Your games and saves stay. Emulators you installed yourself stay too.", async () => say(await uninstallAllEmulators(), "Uninstalling in Fuse"))}>
          Uninstall all
        </ButtonItem>
      </PanelSectionRow>
    </PanelSection>
  );
};

/** A part of the plugin Fuse doesn't let it use, and where the person can allow it. */
const NotAllowed: VFC<{ title: string; what: string }> = ({ title, what }) => (
  <PanelSection title={title}>
    <PanelSectionRow>
      <Field label="Not allowed" description={`Fuse doesn't let this plugin ${what}. Allow it in Fuse: Settings, Library, Decky.`} />
    </PanelSectionRow>
  </PanelSection>
);

const Content: VFC = () => {
  const [hi] = usePoll<Hello>(() => hello());
  const [s] = usePoll<Status>(() => status());
  const connected = !failure(hi);
  return (
    <>
      <Connection hi={hi} />
      {connected && may(hi, "library") && <ThisGame />}
      {connected && may(hi, "library") && <FuseLibrary canOpen={may(hi, "play")} />}
      {connected && may(hi, "status") && <FuseOne s={s} />}
      {connected && may(hi, "change_library") && <SteamGames />}
      {connected && (may(hi, "emulators") ? <Emulators /> : <NotAllowed title="Emulators" what="install and remove emulators" />)}
    </>
  );
};

export default definePlugin(() => {
  // Steam's game menu gets the Fuse entries where this Steam build allows it, unless the person
  // turned them off. Nothing here may throw: a failing plugin must never take Decky down with it.
  let unpatch: () => void = () => {};
  let unloaded = false;
  menuOff()
    .catch(() => true)
    .then((off) => {
      if (off || unloaded) return;
      try {
        const menu = findLibraryContextMenu();
        if (menu) unpatch = patchContextMenu(menu);
      } catch {
        unpatch = () => {};
      }
    });
  return {
    name: "Fuse",
    titleView: (
      <div className={staticClasses.Title} style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <FuseMark size={18} /> Fuse
      </div>
    ),
    content: <Content />,
    icon: <FuseMark />,
    onDismount() {
      unloaded = true;
      try {
        unpatch();
      } catch {
        // Nothing of Fuse's is left in Steam's UI.
      }
    },
  };
});
