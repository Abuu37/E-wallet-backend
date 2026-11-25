# Agent network growth in the staff console — Design (short)

**Date:** 24 Nov 2026 · **Status:** approved 25 Nov · revised 25 Nov: growth band removed from the Overview · **Parent:** `2026-11-23-flagship-wallet-program-design.md` (Phase 3)

## Why

The staff console should tell an investor one story first: **the agent network is growing** *(your answer, 24 Nov)*. Today it opens on money totals (escrow, master wallet, revenue). Growth has to be the first thing seen, with every number coming from data the backend already holds.

## Goals

- Staff Overview opens with network growth: agents, new this month, active agents, regions covered, customers served by agents, and a growth chart.
- A regional view shows where the network is strong and where it has gaps.
- Staff can open one agent and see their float and the transactions they conducted.

## Non-goals

- Targets, leaderboards or month-over-month goals (approach C, not chosen: needs someone to set targets).
- Editable system settings or second approval for settings.
- Approval on tariff changes (requirements only ask for effective dates, no overlapping bands, quote preview).

## Anti-goals

- No number on these screens that the backend can't compute from stored data. No invented "potential customers" or market sizes.
- No customer PIN or full customer phone number in the regional or growth views.

## Approaches

| | What | Trade-off |
|---|---|---|
| A | Coverage tab on the Agents page only | Cheapest; the story is hidden one click deep |
| **B (picked)** | **Growth band on the Overview + Coverage tab for drill-down + per-agent view** | One new report endpoint; growth is the first thing anyone sees |
| C | B plus leaderboard and targets | Most "pitchy"; needs target settings, which reintroduces a settings feature |

## Design

**Data (all existing):** `agents` (region, district, status, created_at), `regions` (31) / `districts` (170), `transactions` linked to the agent float wallet.

**Definitions** *(my calls)*
- *Agent:* any agent with status ACTIVE or SUSPENDED (deactivated excluded).
- *Active agent:* at least one cash-in or withdrawal in the last 30 days.
- *Region covered:* at least one active agent in it.
- *Customers served by agents:* distinct customers with at least one cash-in or withdrawal in the period.

**Endpoints (new, Phase 3)**
- `GET /api/v1/reports/agent-network?months=12` → totals (agents, new this month, active, regions covered of 31, customers served) and a monthly series (onboarded, active) for the chart.
- `GET /api/v1/reports/agent-network/regions?from=&to=` → per region: agents, active, new in period, transactions, volume, customers served; regions with zero agents included so gaps show.
- `GET /api/v1/agents/{agentId}/transactions` → the same rows as the agent's own `GET /agent/transactions`, for staff (adds one line to the requirements).
- Permission: new `NETWORK_REPORT_VIEW` in the AGENT module, given to ADMIN and Treasury. `AGENT_VIEW` stays agent-only.

**Screens (design canvas)**
- ~~**Staff Overview (S01):** a growth band on top.~~ *Removed 25 Nov at your request; the Overview keeps money and ledger only.* Four cards: Agents (+new this month), Active agents, Regions covered x of 31, Customers served. A line chart of agents onboarded and active per month (12 months). Top 5 regions by volume, and regions with no agents. The money cards and ledger status move below. Reconciliation stays reachable.
- **Agents · Coverage tab (S09f):** reworked to the per-region columns above, with a "No agents yet" filter. Clicking a region filters the agents list.
- **Agent side panel (S09c/S09d):** float, and transactions from the staff endpoint.
- **Menu:** Reconciliation shown again (it is in scope, Phase 3). System settings becomes read-only: limits per tier, SMS provider status, Plaid environment, behind `SYSTEM_CONFIG`.
- **Tariffs:** remove "with approval"; keep effective dates.

## Left to the builder

SQL for the monthly series, caching (the report can be computed nightly with the reconciliation job), chart library, and empty states.
