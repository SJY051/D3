# D³ low-fidelity wireframes

Owner: 최정민

Status: Review required

Last verified: 2026-08-13 against D3-UX-001 and the current route scaffold

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
│              [ Sign in ]   [ Continue with GitHub ]           │
│              Create account · Recover access                  │
│                                                              │
│                         service health                         │
└──────────────────────────────────────────────────────────────┘
```

## WF-02 — Community feed (`/feed`)

```text
┌──────────────┬───────────────────────────────┬───────────────┐
│ D³ navigation│ All / Following               │ Queue shortcut│
│ Feed         │ ┌───────────────────────────┐ │ Rating / RP   │
│ Practice     │ │ Composer: Markdown / code │ │ Recent record │
│ Ranked       │ └───────────────────────────┘ │ Active season │
│ Record       │ ┌───────────────────────────┐ │               │
│              │ │ Author + tier · post      │ │               │
│              │ │ code block / match card   │ │               │
│              │ │ like · comment            │ │               │
│              │ └───────────────────────────┘ │               │
└──────────────┴───────────────────────────────┴───────────────┘
```

## WF-03 — Ranked matchmaking (`/ranked`)

```text
┌──────────────────────────────────────────────────────────────┐
│ Ranked queue                                      Cancel      │
│                                                              │
│  Public rating  1420        Gold II · 64 RP                  │
│  Language       [ Python 3 ▼ ]                               │
│  Search range   ± 80 and widening             00:18           │
│                                                              │
│  [ searching pulse with reduced-motion alternative ]         │
│  Estimated rule: same language · rating proximity            │
└──────────────────────────────────────────────────────────────┘
```

## WF-04 — Active battle (`/battles/:matchId`)

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

The timer, connection state, submit lock, warning window, and attack effect require text or shape in addition to color.

## WF-05 — Match result (`/results/:matchId`)

```text
┌──────────────────────────────────────────────────────────────┐
│ Victory / Defeat / Draw / Voided                             │
│ Rating 1420 → 1438       RP 64 → 82       Gold II            │
├───────────────────────┬──────────────────────────────────────┤
│ Outcome comparison    │ Execution evidence                   │
│ speed      42 / 50    │ hidden tests / runtime tiers         │
│ efficiency 27 / 35    │ static confidence: known / unknown   │
│ attempts     9 / 15   │ attack and reconnect timeline        │
├───────────────────────┴──────────────────────────────────────┤
│ [View record] [Share source explicitly] [Return to feed]     │
└──────────────────────────────────────────────────────────────┘
```

## WF-06 — Player record (`/players/:handle`)

```text
┌──────────────────────────────────────────────────────────────┐
│ Avatar  handle  bio                Follow / challenge room   │
│ Grandmaster · 2180 rating · 76 RP · leaderboard #18         │
├──────────────────┬───────────────────────────────────────────┤
│ Wins / losses    │ Rating history                            │
│ win rate         │ language breakdown                        │
│ peak tier        │ recent matches → match details            │
├──────────────────┴───────────────────────────────────────────┤
│ Community posts and ranked result cards                      │
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

## Visual review gate

- Confirm the eight information hierarchies and desktop battle layout before visual components.
- Use one restrained primary accent per surface; reserve opponent-side color for state distinction.
- Prefer typography, spacing, dividers, and data alignment over decorative containers.
- Require a product reason for gradients, glass effects, oversized headings, repeated rounded cards, or non-functional copy.
- Check 360 px community, 1280 px battle, keyboard order, focus visibility, reduced motion, and non-color state cues.
