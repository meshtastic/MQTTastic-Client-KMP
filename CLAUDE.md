# MQTTastic Client KMP - Claude Code Guide

@AGENTS.md

## Claude-Specific Instructions

- **Plan Mode:** Use plan mode for architectural changes spanning multiple source sets or packet types. Write plans to `.agent_plans/` (git-ignored).
- **Spec Reference:** When implementing packet encoding/decoding, check the byte layout against the numbered MQTT 5.0 spec section (e.g., §3.1 CONNECT) and cite it.
