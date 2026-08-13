# DocuMind — Frontend PRD (Simple / Showcase Version)

**Goal:** A clean, interactive UI that makes the project demo well — not a learning exercise. Optimize for "looks polished in a screen-recording/interview demo" and "fast to build," not for teaching you frontend concepts. One build pass, not phases.

---

## 1. What it needs to do (and nothing more)

1. Upload a PDF, show its status until it's indexed
2. List uploaded documents
3. Ask a question, show the answer
4. Show citations (document + page + snippet) next to the answer
5. Look intentional, not like a bare Bootstrap/default-HTML form

That's the whole scope. No auth UI unless/until your backend actually has auth wired up — build that screen only when Phase 2 lands, not before.

---

## 2. Tech stack (chosen for speed, not learning)

| Layer | Choice | Why |
|---|---|---|
| Framework | **React + Vite** | Minimal setup, fast dev loop. |
| Styling | **Tailwind CSS + shadcn/ui** | Prebuilt, accessible, good-looking components (buttons, cards, dialogs, file upload) out of the box — you style by picking components and tokens, not writing CSS from scratch. This is the single biggest time-saver for a "just make it look good" goal. |
| Icons | **lucide-react** | Comes bundled with shadcn, no extra decision needed. |
| Data fetching | Plain `fetch` + React state, or **TanStack Query** if you want loading/error states handled for free with minimal code | Either is fine — don't overthink this part. |
| Deployment (optional) | Vercel/Netlify free tier | So you have a live link, not just localhost, for the demo. |

You do not need: React Router (single page is enough), auth libraries, a design token system, animation libraries, PDF rendering — all of that was for the learning-focused version and is cut here.

---

## 3. Layout (single screen, no navigation)

```
┌─────────────────────────────────────────────┐
│  DocuMind                                    │
├───────────────┬───────────────────────────────┤
│  Documents     │  Chat                         │
│  (list +       │  question input at bottom     │
│   upload       │  answer + citations above it  │
│   button)      │                               │
└───────────────┴───────────────────────────────┘
```

One page, two panels — sidebar for documents/upload, main area for the chat. This alone reads as an intentional product rather than a default template, and it's a straightforward shadcn layout (`Card` + `ScrollArea` + `Sheet` if you want the doc list collapsible on mobile).

**Citations:** render as small numbered badges `[1]` under/beside the answer, each expandable (shadcn `Accordion` or `Popover`) to show the snippet, document title, and page number. No need for a two-pane "click to open source panel" interaction — a simple expandable badge is enough to visibly demonstrate the grounding/citation feature in a demo.

---

## 4. Minimal visual direction

Pick shadcn's default neutral theme (slate/zinc) and just set **one accent color** (e.g. a green or blue) for primary buttons and citation badges — that alone avoids the "unstyled default" look without requiring a custom design system. Use a normal system/sans font stack (shadcn's default is fine). Don't spend time on custom typography or a bespoke palette — that effort isn't necessary for the goal here.

---

## 5. Build order (single pass, not phases)

1. `npm create vite@latest` (React + TS template) → install Tailwind → install shadcn/ui
2. Build the two-panel layout shell with placeholder content
3. Wire document upload + list (`POST /documents`, `GET /documents`), poll status until `INDEXED`
4. Wire chat (`POST /chat`), render answer + citation badges
5. Pass the accent color + a few spacing tweaks, done

That's realistically a single focused session, not a multi-week plan — which is the point of this version.

---

## 6. Explicitly cut from the learning-focused PRD

- The phased rollout (F1–F7)
- Custom design token system, serif/mono type pairing
- `react-pdf` real page rendering
- Two-pane citation panel with open/close animation
- Streaming responses UI (add later only if the backend actually streams and you want the demo to show it — otherwise a normal loading spinner is fine)
- Accessibility/responsive deep pass — get baseline usable, don't over-invest

If you later *do* want to learn frontend properly, the earlier detailed PRD is still valid — this version is just the fast path to a demo-ready UI on top of the backend work you actually care about.