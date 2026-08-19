import { useEffect, useMemo, useRef, useState } from "react";
import { useParams } from "react-router-dom";

import {
  BATTLE_PROTOCOL,
  type BattleCommand,
  type BattleCommandType,
  type BattleSnapshot,
  type ConnectionState,
  battleSocketUrl,
  createBattleCommand,
  isMatchId,
  overlayGlyphs,
  parseBattleSnapshot,
} from "../battle/battleRealtime";
import { currentSessionUserId, requestSessionAccessToken } from "../api/session";
import { clearActiveMatchIfMatch, setActiveMatch } from "../battle/useActiveMatch";
import { clearRankedQueueIfMatch } from "../battle/useRankedQueue";

interface BattleArenaProps {
  connection: ConnectionState;
  matchId: string;
  messageRevision: number;
  onCommand: (command: BattleCommand) => void;
  onReconnect: () => void;
  snapshot: BattleSnapshot;
}

export function LiveBattlePage() {
  const { matchId = "" } = useParams();
  const [connection, setConnection] = useState<ConnectionState>("CONNECTING");
  const [snapshot, setSnapshot] = useState<BattleSnapshot | null>(null);
  const [reconnectAttempt, setReconnectAttempt] = useState(0);
  const [messageRevision, setMessageRevision] = useState(0);
  const socketRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    if (!isMatchId(matchId)) {
      setConnection("PROTOCOL_ERROR");
      return;
    }

    let active = true;
    let socket: WebSocket | null = null;
    setConnection("CONNECTING");

    void requestSessionAccessToken(reconnectAttempt > 0).then((accessToken) => {
      if (!active) {
        return;
      }

      socket = new WebSocket(
        battleSocketUrl(matchId),
        [BATTLE_PROTOCOL, `d3.jwt.${accessToken}`],
      );
      socketRef.current = socket;
      socket.onopen = () => {
        if (active) {
          setConnection("LIVE");
        }
      };
      socket.onmessage = (event) => {
        if (!active || typeof event.data !== "string") {
          return;
        }
        const next = parseBattleSnapshot(event.data);
        if (next === null || next.matchId !== matchId) {
          setConnection("PROTOCOL_ERROR");
          socket?.close(1002, "invalid battle snapshot");
          return;
        }
        setMessageRevision((revision) => revision + 1);
        setSnapshot((current) => current !== null && current.sequence >= next.sequence ? current : next);
      };
      socket.onerror = () => {
        if (active) {
          setConnection("DISCONNECTED");
        }
      };
      socket.onclose = () => {
        if (active) {
          socketRef.current = null;
          setConnection((current) => current === "PROTOCOL_ERROR" ? current : "DISCONNECTED");
        }
      };
    }).catch((error: unknown) => {
      if (!active) {
        return;
      }
      const code = error instanceof Error ? error.message : "SESSION_UNAVAILABLE";
      setConnection(code === "SESSION_REQUIRED" ? "SESSION_REQUIRED" : "DISCONNECTED");
    });

    return () => {
      active = false;
      socketRef.current = null;
      socket?.close(1000, "battle route closed");
    };
  }, [matchId, reconnectAttempt]);

  useEffect(() => {
    if (snapshot === null || snapshot.matchId !== matchId) {
      return;
    }
    clearRankedQueueIfMatch(matchId);
    if (snapshot.match.state === "FINISHED") {
      clearActiveMatchIfMatch(matchId);
    } else {
      setActiveMatch(matchId, currentSessionUserId());
    }
  }, [matchId, snapshot]);

  const sendCommand = (command: BattleCommand) => {
    const socket = socketRef.current;
    if (connection !== "LIVE" || socket === null || socket.readyState !== WebSocket.OPEN) {
      return;
    }
    socket.send(JSON.stringify(command));
  };

  if (!isMatchId(matchId)) {
    return <BattleUnavailable title="Invalid match" detail="Open the battle from a valid ranked match link." />;
  }
  if (snapshot === null || snapshot.matchId !== matchId) {
    const detail = connection === "SESSION_REQUIRED"
      ? "Sign in again before reconnecting to this private match."
      : connection === "PROTOCOL_ERROR"
        ? "The realtime snapshot did not match the Battle v3 contract."
        : connection === "DISCONNECTED"
          ? "The realtime channel closed before the first authoritative snapshot arrived."
          : "Waiting for the authoritative Battle v3 snapshot.";
    return (
      <BattleUnavailable title="Connecting to battle" detail={detail}>
        {connection === "DISCONNECTED" && (
          <button type="button" onClick={() => setReconnectAttempt((attempt) => attempt + 1)}>
            Reconnect
          </button>
        )}
      </BattleUnavailable>
    );
  }

  return (
    <BattleArena
      connection={connection}
      messageRevision={messageRevision}
      matchId={matchId}
      onCommand={sendCommand}
      onReconnect={() => setReconnectAttempt((attempt) => attempt + 1)}
      snapshot={snapshot}
    />
  );
}

export function BattleArena({
  connection,
  matchId,
  messageRevision,
  onCommand,
  onReconnect,
  snapshot,
}: BattleArenaProps) {
  const [pendingCommand, setPendingCommand] = useState<BattleCommandType | null>(null);
  const pendingCommandRef = useRef(false);
  const [source, setSource] = useState("");
  const [now, setNow] = useState(() => Date.now());
  const clockOffset = useMemo(() => Date.parse(snapshot.serverTime) - Date.now(), [snapshot.serverTime]);
  const attack = snapshot.attack.current;
  const isIncomingWarning = attack?.phase === "WARNING" && attack.target === "SELF";
  const isIncomingOverlay = attack?.phase === "ACTIVE" && attack.target === "SELF";
  const attackBusy = attack !== null && attack.phase !== "RESOLVED";

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 100);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    pendingCommandRef.current = false;
    setPendingCommand(null);
  }, [connection, messageRevision]);

  const authoritativeNow = now + clockOffset;
  const matchRemaining = formatCountdown(snapshot.match.matchDeadline, authoritativeNow);
  const warningRemaining = formatCountdown(attack?.warningDeadline ?? null, authoritativeNow);
  const overlayRemaining = formatCountdown(attack?.overlayExpiresAt ?? null, authoritativeNow);
  const glyphs = useMemo(
    () => isIncomingOverlay && attack !== null ? overlayGlyphs(attack.overlaySeed) : [],
    [attack, isIncomingOverlay],
  );

  const command = (
    type: BattleCommandType,
    fields: Pick<BattleCommand, "attackId" | "sourceCode"> = {},
  ) => {
    if (pendingCommandRef.current) {
      return;
    }
    pendingCommandRef.current = true;
    setPendingCommand(type);
    onCommand(createBattleCommand(matchId, type, fields));
  };

  const statusText = isIncomingWarning
    ? `Incoming attack. ${warningRemaining} to block or reflect.`
    : isIncomingOverlay
      ? `Garbage overlay active for ${overlayRemaining}. Your source buffer is unchanged.`
      : attack?.phase === "WARNING"
        ? `Opponent warning window: ${warningRemaining}.`
        : attack?.phase === "ACTIVE"
          ? `Opponent overlay active for ${overlayRemaining}.`
          : attack?.resolution === "BLOCKED"
            ? "Attack blocked."
            : attack?.resolution === "EXPIRED"
              ? "Attack effect expired."
              : "Attack channel ready.";

  return (
    <article className="battle-page" data-requirement="D3-BTL-004" data-wireframe="WF-04">
      <header className="battle-scoreboard">
        <div>
          <span className="battle-kicker">Opponent</span>
          <strong>{snapshot.match.opponent.connectionState.toLowerCase()}</strong>
        </div>
        <div className="battle-clock" aria-label={`Match time remaining ${matchRemaining}`}>
          <span>{snapshot.match.state}</span>
          <strong>{matchRemaining}</strong>
          <small>server time</small>
        </div>
        <div className="battle-self-summary">
          <span className="battle-kicker">You</span>
          <strong>{snapshot.attack.selfEnergy}/{snapshot.attack.maximumEnergy} energy</strong>
        </div>
      </header>

      <div className="battle-workspace">
        <aside className="battle-context" aria-label="Problem and match context">
          <p className="battle-kicker">Problem</p>
          <h1>Ranked challenge</h1>
          <dl>
            <div><dt>Match</dt><dd>{matchId.slice(0, 8)}</dd></div>
            <div><dt>State</dt><dd>{snapshot.match.state}</dd></div>
            <div><dt>Sequence</dt><dd>{snapshot.sequence}</dd></div>
          </dl>
          <p className="battle-context-note">
            The assigned problem statement remains outside the realtime snapshot. Attack state never carries source.
          </p>
        </aside>

        <section className="battle-editor" aria-labelledby="editor-title">
          <div className="battle-panel-heading">
            <div>
              <p className="battle-kicker">Your editor</p>
              <h2 id="editor-title">Solution buffer</h2>
            </div>
            <div className="battle-connection-control">
              <span className={`connection-pill connection-${connection.toLowerCase()}`}>{connection}</span>
              {connection === "DISCONNECTED" && (
                <button type="button" onClick={onReconnect}>Reconnect</button>
              )}
            </div>
          </div>
          <div className={`source-layer${isIncomingWarning ? " source-layer-warning" : ""}`}>
            <textarea
              aria-label="Solution source"
              autoCapitalize="off"
              autoCorrect="off"
              onChange={(event) => setSource(event.target.value)}
              placeholder="Type your solution. The attack overlay never edits this value."
              spellCheck={false}
              value={source}
            />
            {isIncomingOverlay && attack !== null && (
              <div className="garbage-overlay" data-overlay-seed={attack.overlaySeed} aria-hidden="true">
                {glyphs.map((item, index) => (
                  <span key={`${item.x}-${item.y}-${index}`} style={{ left: `${item.x}%`, top: `${item.y}%` }}>
                    {item.glyph}
                  </span>
                ))}
              </div>
            )}
          </div>
        </section>

        <aside className="battle-opponent" aria-label="Opponent activity">
          <p className="battle-kicker">Opponent mask</p>
          <h2>Private by design</h2>
          <div className="opponent-mask" aria-hidden="true">
            <i /><i /><i /><i /><i /><i /><i />
          </div>
          <dl>
            <div><dt>Connection</dt><dd>{snapshot.match.opponent.connectionState}</dd></div>
            <div><dt>Energy</dt><dd>{snapshot.attack.opponentEnergy}/100</dd></div>
          </dl>
          <p>Only server-owned activity state is shown. No identifiers, literals, or source cross this panel.</p>
        </aside>
      </div>

      <footer className="battle-console">
        <div className={`attack-status attack-${attack?.phase.toLowerCase() ?? "idle"}`} role="status" aria-live="polite">
          <span className="status-mark" aria-hidden="true" />
          <strong>{statusText}</strong>
        </div>
        <div className="battle-actions" aria-label="Battle actions" aria-busy={pendingCommand !== null}>
          {snapshot.match.state === "LOBBY" && (
            <button
              type="button"
              disabled={connection !== "LIVE" || snapshot.match.self.ready || pendingCommand !== null}
              onClick={() => command("READY")}
            >
              {snapshot.match.self.ready ? "Ready sent" : "Ready"}
            </button>
          )}
          <button
            type="button"
            disabled={
              connection !== "LIVE"
              || snapshot.match.state !== "RUNNING"
              || source.trim().length === 0
              || pendingCommand !== null
            }
            onClick={() => command("RUN", { sourceCode: source })}
          >
            Run
          </button>
          <button
            type="button"
            disabled={
              connection !== "LIVE"
              || snapshot.match.state !== "RUNNING"
              || source.trim().length === 0
              || pendingCommand !== null
            }
            onClick={() => command("SUBMIT", { sourceCode: source })}
          >
            Submit
          </button>
          <span className="action-divider" aria-hidden="true" />
          <button
            type="button"
            disabled={
              connection !== "LIVE"
              || snapshot.match.state !== "RUNNING"
              || attackBusy
              || snapshot.attack.selfEnergy < snapshot.attack.attackCost
              || pendingCommand !== null
            }
            onClick={() => {
              const attackId = crypto.randomUUID();
              command("ATTACK_LAUNCH", { attackId });
            }}
          >
            Launch attack
          </button>
          <button
            type="button"
            disabled={
              connection !== "LIVE"
              || !isIncomingWarning
              || attack === null
              || snapshot.attack.selfEnergy < snapshot.attack.blockCost
              || pendingCommand !== null
            }
            onClick={() => attack !== null && command("ATTACK_BLOCK", { attackId: attack.attackId })}
          >
            Block
          </button>
          <button
            type="button"
            disabled={
              connection !== "LIVE"
              || !isIncomingWarning
              || attack === null
              || snapshot.attack.selfEnergy < snapshot.attack.reflectCost
              || pendingCommand !== null
            }
            onClick={() => attack !== null && command("ATTACK_REFLECT", { attackId: attack.attackId })}
          >
            Reflect
          </button>
        </div>
      </footer>
    </article>
  );
}

function BattleUnavailable({
  children,
  detail,
  title,
}: {
  children?: React.ReactNode;
  detail: string;
  title: string;
}) {
  return (
    <article className="battle-unavailable" data-requirement="D3-BTL-004" data-wireframe="WF-04">
      <p className="battle-kicker">WF-04 · secure realtime boundary</p>
      <h1>{title}</h1>
      <p>{detail}</p>
      {children}
    </article>
  );
}

function formatCountdown(deadline: string | null, now: number): string {
  if (deadline === null) {
    return "--:--";
  }
  const remaining = Math.max(0, Date.parse(deadline) - now);
  const totalSeconds = Math.ceil(remaining / 1000);
  const minutes = Math.floor(totalSeconds / 60).toString().padStart(2, "0");
  const seconds = (totalSeconds % 60).toString().padStart(2, "0");
  return `${minutes}:${seconds}`;
}
