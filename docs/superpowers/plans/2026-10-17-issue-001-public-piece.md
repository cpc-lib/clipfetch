# ISSUE-001 Public Piece Download Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Determine and, if locally reproducible, fix the public Worker Piece-download failure reported as `REQUEST` followed by peer EOF, then verify the smallest meaningful public acceptance boundary.

**Architecture:** Keep the existing single-process Spring Boot downloader and BEP3 peer implementation. First validate current direct-session and retry behavior with deterministic tests and a bounded public probe. Apply only a minimal protocol/retry change backed by a failing regression test; do not redesign the downloader or start a full ISO transfer unless the evidence requires it.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Maven, existing BEP3 peer session/coordinator, Windows PowerShell.

---

- [x] Establish the current baseline with focused peer/session/coordinator tests and inspect the public probe result.
- [x] Confirm the reproducible local defect: the checked-in Ubuntu 26.04 live-server torrent was official, but three opt-in acceptance tests still expected an unrelated older InfoHash.
- [x] Implement the smallest change by synchronizing those three live-server fixture expectations to `e1fc140a6391357fa1cf08ddb70274f9c05eb88b`; production transfer and retry code were not changed.
- [x] Re-run focused tests, then run bounded public Piece and SQLite Worker acceptance probes. Both reached real verified data and exited 0.
- [x] Update `HANDOFF.md` with the actual changes, verification, remaining ISSUE-001 boundary, and next step.
