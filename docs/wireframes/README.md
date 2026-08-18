# D³ low-fidelity wireframes

Owner: 최정민

Status: WF-01–WF-06 P0 revision approved by SJY051 on 2026-08-16 for issue #18; WF-07 and WF-08 remain review required

Last verified: 2026-08-16 against origin/main at 6019fcc and contracts/http + contracts/websocket v3

Requirement: D3-UX-001

These wireframes establish information priority and interaction only. They are not styled UI. Each styled route must name its wireframe ID in the implementing issue or component documentation.

## Information architecture

```text
Public:      Sign in → Feed → Player record → Match detail
Ranked:      Language → Queue → Battle → Result → Record projection
Solo:        Problem list → Practice editor → Private solution
Operation:   Problem list → Inspect → Activate / bounded edit
```

## WF-01 — Sign in (`/sign-in`)

```text
┌──────────────────────────────────────────────────────────────┐
│ D³                                              About / Status│
│                                                              │
│              Sign in to enter the arena                      │
│              [ Email                              ]           │
│              [ Password                           ]           │
│              [ Sign in ]                                    │
│              Create account                                 │
│                                                              │
│                         service health                         │
└──────────────────────────────────────────────────────────────┘
```

## WF-02 — Community feed (`/feed`)

```text
┌──────────────┬───────────────────────────────┬───────────────┐
│ D³ navigation│ Public feed                   │ Queue shortcut│
│ Feed         │ ┌───────────────────────────┐ │ Recent record │
│ Practice     │ │ Composer: Markdown / code │ │               │
│ Ranked       │ └───────────────────────────┘ │               │
│ Record       │ ┌───────────────────────────┐ │               │
│              │ │ Author · post            │ │               │
│              │ │ code block / match card  │ │               │
│              │ │ → /results/:matchId      │ │               │
│              │ └───────────────────────────┘ │               │
└──────────────┴───────────────────────────────┴───────────────┘
```

## WF-03 — Ranked matchmaking (`/ranked`)

```text
┌──────────────────────────────────────────────────────────────┐
│ Ranked queue                                                │
│                                                              │
│  Language  [ C / CPP / JAVA / PYTHON3 / JAVASCRIPT /         │
│              TYPESCRIPT ▼ ]                                  │
│  Status    QUEUED → MATCHED                   elapsed 00:18   │
│                                                              │
│  [ searching pulse with reduced-motion alternative ]         │
│  On MATCHED, move to /battles/:matchId.                      │
└──────────────────────────────────────────────────────────────┘
```

## WF-04 — Active battle (`/battles/:matchId`)
Status: Approved by SJY051 on 2026-08-15 for Issue #48.


```text
┌────────────────────────────────────────────────────────────────────────┐
│ Opponent A · rating       02:41 server time       You · energy 36/100 │
├──────────────────┬────────────────────────────────────┬────────────────┤
│ Problem          │ Your editor                        │ Opponent mask  │
│ statement        │                                    │ line structure │
│ constraints      │                                    │ cursor / state │
│ examples         │                                    │ typing activity│
├──────────────────┴────────────────────────────────────┴────────────────┤
│ Tests / diagnostics        [Run] [Submit]       attacks / block / reflect│
└────────────────────────────────────────────────────────────────────────┘
```

The timer, connection state, submit lock, warning window, and attack effect require text or shape in addition to color. The implementation keeps the deterministic garbage effect in a pointer-free display layer above the editor so the controlled source buffer remains unchanged.

## WF-05 — Match result (`/results/:matchId`)

```text
┌──────────────────────────────────────────────────────────────┐
│ Victory / Defeat / Draw / Voided                             │
│ Final battle snapshot                                       │
├──────────────────────────────────────────────────────────────┤
│ Public match record                                          │
│ result · ranked                                              │
│ seat-order player IDs                                        │
│ sourceVersion · projectedAt                                  │
├──────────────────────────────────────────────────────────────┤
│ [View player record] [Return to feed]                        │
└──────────────────────────────────────────────────────────────┘
```

## WF-06 — Player record (`/players/:playerId`)

Handle lookup remains gated until Identity publishes `user-profile.changed.v1`; P0 addresses players by ID.

```text
┌──────────────────────────────────────────────────────────────┐
│ Player ID                                                    │
├──────────────────────────────────────────────────────────────┤
│ ACTIVE match records                                         │
│ result · ranked · opponent seat · projectedAt                │
│ → /results/:matchId                                          │
│                                                              │
│ [Load more] (keyset cursor)                                  │
└──────────────────────────────────────────────────────────────┘
```

## WF-07 — Problem operation (`/admin/problems`)

```text
┌──────────────────────────────────────────────────────────────┐
│ Problems                    Search / difficulty / active      │
├────────┬──────────────────────┬───────────┬───────────────────┤
│ ID     │ Title                │ Version   │ Status / action   │
│ DEMO-1 │ Deterministic demo   │ 1         │ Active · Inspect  │
│ E-001  │ …                    │ 1         │ Draft  · Activate │
└────────┴──────────────────────┴───────────┴───────────────────┘
│ Inspector: metadata and bounded edits; hidden fixtures read-only│
└──────────────────────────────────────────────────────────────┘
```

## WF-08 — Solo practice (`/practice`)

```text
┌──────────────────┬────────────────────────────────────┬───────────────┐
│ Problems         │ Practice editor                    │ Run evidence  │
│ search / filter  │ language [ Python 3 ▼ ]            │ public cases  │
│ Easy / Medium /  │                                    │ diagnostics   │
│ Hard             │                                    │               │
│                  │                                    │ [Run] [Submit]│
├──────────────────┴────────────────────────────────────┴───────────────┤
│ Accepted solutions remain private · ranked rating and RP unaffected  │
└──────────────────────────────────────────────────────────────────────┘
```

## Global shell — active-match rejoin banner

```text
[ ⚔ Match in progress — Return to battle ]   (shell-level banner, hidden on /battles/:matchId)
```

Reviewed by SJY051 on 2026-08-18 for Issue #85. The banner is a shared shell element rendered above every P0 route while the signed-in user owns a live match. It uses text plus an icon (non-color cue), one action, and disappears when the match finishes, the owner changes, or the marker expires.

## Visual review gate

- Confirm the eight information hierarchies and desktop battle layout before visual components.
- Use one restrained primary accent per surface; reserve opponent-side color for state distinction.
- Prefer typography, spacing, dividers, and data alignment over decorative containers.
- Require a product reason for gradients, glass effects, oversized headings, repeated rounded cards, or non-functional copy.
- Check 360 px community, 1280 px battle, keyboard order, focus visibility, reduced motion, and non-color state cues.
- WF-01, WF-02, WF-03, WF-05, and WF-06 P0 implementations expose only the elements in this revision; removed elements stay hidden behind the P1 feature boundary and must not appear as mocks. WF-04 retains its existing approval and feature-boundary guidance.
