# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in this project, please report it
responsibly. **Do not open a public issue.**

Instead, please use GitHub's
[private vulnerability reporting](https://github.com/meshtastic/MQTTtastic-Client-KMP/security/advisories/new)
feature on this repository, or email **security@meshtastic.org**.

## Scope

This library handles MQTT protocol encoding/decoding and network transport.
Security-relevant areas include:

- TLS certificate validation in transport layer
- Buffer handling and parsing of untrusted broker data
- Authentication credential management
- Memory safety in native targets

## Response

We will acknowledge receipt within 48 hours and aim to provide a fix or
mitigation plan within 7 days for confirmed vulnerabilities.
