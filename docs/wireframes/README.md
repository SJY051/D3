# D³ low-fidelity wireframes

Owner: 최정민

Status: WF-01–WF-06 approved and implemented for P0; global rejoin banner and self-verdict/accepted-lock states merged; WF-07/WF-08 review required

Last verified: 2026-08-19 against `2915d0f`, PRs #28/#76/#91/#92/#96 and HTTP/WebSocket v3 contracts

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

## WF-02 P1 — Community feed social interactions (`/feed`)

Status: DRAFT — review required. Not approved; styled work stays blocked until a reviewer approves this revision. Depends on contract #106 / PR #112; consumed by Issue #111.

Activates the P1 elements the P0 WF-02 intentionally hides — post like, post comment thread, and author follow — over the already-recognized persistence and the #106 endpoints. Scope is S-04 (follow / like / comment) only; reposts, quotes, reply threads, and dislikes are out of scope here (Issue #110, spec extension pending).

```text
┌──────────────┬───────────────────────────────┬───────────────┐
│ D³ navigation│ Public feed                   │ Queue shortcut│
│ Feed         │ ┌───────────────────────────┐ │ Recent record │
│ Practice     │ │ Composer: Markdown / code │ │               │
│ Ranked       │ └───────────────────────────┘ │               │
│ Record       │ ┌───────────────────────────┐ │               │
│              │ │ Author        [Follow ▷]  │ │               │
│              │ │ code block / match card   │ │               │
│              │ │ → /results/:matchId       │ │               │
│              │ │ [♥ Like · 3] [Comments · 5]│ │               │
│              │ │ ┌ thread (expanded) ──────┐│ │               │
│              │ │ │ commenter · body        ││ │               │
│              │ │ │             [Delete·own]││ │               │
│              │ │ │ [Load more] (keyset)    ││ │               │
│              │ │ │ [ Add a comment       ] ││ │               │
│              │ │ └─────────────────────────┘│ │               │
│              │ └───────────────────────────┘ │               │
└──────────────┴───────────────────────────────┴───────────────┘
```

Interaction and state:
- Like reflects `viewerLiked`/`likeCount` (GET like-state); toggle is POST/DELETE likes. Non-color cue: filled vs outline glyph plus a `Liked`/`Like` text label, never color alone. Optimistic toggle reconciles against the contract response.
- Comments count comes from the thread; expanding loads the thread (GET comments, `Load more` keyset cursor). The composer posts (POST comment) and prepends. `Delete` appears only on the viewer's own comments (author-only DELETE) and asks for confirmation first.
- Follow sits on the post author, reflecting `viewerFollowing` (GET follow-state) with a `Follow`/`Following ✓` text label; toggle is POST/DELETE follows. Hidden on the viewer's own posts (no self-follow).
- Like and comment controls appear only on PUBLIC posts (the P0 feed is public-only); a non-public or missing post resolves to `POST_NOT_FOUND` and renders no control. `COMMENT_NOT_FOUND` surfaces as an inline, non-destructive error.
- 360 px: controls wrap beneath the post, the thread is full-width, keyboard order is like → comments → composer → follow, focus stays visible, and expand/collapse is a real button (Enter/Space).
- No mocks: every control here is backed by a live #106 endpoint before it renders; nothing appears as a placeholder.

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
│  [Pause polling] stops local polling only; server ticket may │
│  still match or expire.                                      │
│  On PAUSED, show Retry existing queue with reason copy when  │
│  session or active-ticket conflict caused the pause.         │
│  On MATCHED, move to /battles/:matchId.                      │
└──────────────────────────────────────────────────────────────┘
```

## WF-04 — Active battle (`/battles/:matchId`)
Status: Approved by SJY051 for Issue #48; visual polish and self-submission verdict/accepted-lock states merged in PRs #92/#96.


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

The timer, connection state, self-only verdict with attempt number, accepted submit lock, warning window, and attack effect require text or shape in addition to color. The implementation keeps the deterministic garbage effect in a pointer-free display layer above the editor so the controlled source buffer remains unchanged.

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

Handle lookup is active: Identity publishes `user-profile.changed.v1`, Community projects the handle alongside rating/RP/tier, and authenticated keyset prefix search resolves the player record route (PRs #75/#76).

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

## Global shell — ranked queue banner

```text
[ ● Searching for ranked match · 00:18 — View queue ]   (hidden behind active-match rejoin)
[ ● Ranked match found — Enter battle ]                 (hidden on /battles/:matchId)
```

Reviewed by SJY051 in PR #95 review on 2026-08-19 for Issue #86. The queue banner is a shared shell element rendered above every P0 route while a signed-in user has a persisted ranked queue marker. It preserves the original idempotency key across navigation, displays elapsed time while `QUEUED`, switches to an explicit battle action when `MATCHED`, and disappears when the battle snapshot is received, the owner changes, or the marker expires.

## Visual review gate

- Confirm the eight information hierarchies and desktop battle layout before visual components.
- Use one restrained primary accent per surface; reserve opponent-side color for state distinction.
- Prefer typography, spacing, dividers, and data alignment over decorative containers.
- Require a product reason for gradients, glass effects, oversized headings, repeated rounded cards, or non-functional copy.
- Check 360 px community, 1280 px battle, keyboard order, focus visibility, reduced motion, and non-color state cues.
- WF-01, WF-02, WF-03, WF-05, and WF-06 P0 implementations expose only the elements in this revision; removed elements stay hidden behind the P1 feature boundary and must not appear as mocks. WF-04 retains its existing approval and feature-boundary guidance.
