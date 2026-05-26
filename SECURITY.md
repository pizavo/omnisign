# Security Policy

OmniSign creates and validates PAdES digital signatures on top of the EU DSS
library, so security reports are very welcome. Thank you for helping keep
OmniSign and its users safe.

## Reporting a vulnerability

**Please do not report security vulnerabilities through public issues,
discussions, or pull requests.**

Report them privately using GitHub's
**[Report a vulnerability](https://github.com/pizavo/omnisign/security/advisories/new)**
form (the repository's *Security → Advisories* tab). This keeps the report
confidential until a fix is available.

Where possible, please include:

- the affected component (CLI, desktop, server, or web) and its version,
- the impact and a description of the issue,
- steps to reproduce, or a proof of concept,
- any relevant logs, configuration, or environment details (OS, JDK, PKCS#11
  token, etc.).

You can expect an acknowledgement within a few days. Confirmed issues will be
fixed and released, and disclosed through a GitHub Security Advisory once users
have had a reasonable chance to update.

## Supported versions

Security fixes target the **latest released version of each component** (CLI,
desktop, server, web). Please update to the newest release before reporting.

## Scope

OmniSign delegates cryptographic operations to the EU DSS library and the
underlying JDK / PKCS#11 stack. Vulnerabilities in those upstream projects are
best reported to their respective maintainers; this policy covers OmniSign's own
code and configuration.
