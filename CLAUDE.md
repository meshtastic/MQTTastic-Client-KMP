# MQTTastic Client KMP - Claude Code Guide

@AGENTS.md

## Claude-Specific Instructions

- **Think First:** Always outline your step-by-step reasoning inside `<thinking>` tags before writing code or shell commands. Claude models perform significantly better on complex KMP and protocol implementation tasks when they reason through the problem first.
- **Plan Mode:** Use plan mode for architectural changes spanning multiple source sets or packet types. Write plans to `.agent_plans/` (git-ignored).
- **Spec Reference:** When implementing packet encoding/decoding, cite the relevant MQTT 5.0 spec section number (e.g., §3.1 CONNECT) in your reasoning to ensure byte-level correctness.
