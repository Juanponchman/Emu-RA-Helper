# Security Policy

## Reporting a vulnerability

If you discover a security vulnerability in EmuHelper, please report it responsibly.

**Preferred method:** Use GitHub's built-in private vulnerability reporting via the [Security tab](https://github.com/mayusi/EmuHelper/security) of this repository ("Report a vulnerability"). This keeps the details private until a fix is available.

**Alternative:** If you prefer, open a regular issue with minimal details (enough to indicate a security concern exists) and request a private follow-up channel. Do not include exploit details or sensitive information in a public issue.

Please avoid disclosing the vulnerability publicly before it has been addressed.

---

## Supported versions

Only the **latest release** receives security fixes. This is an alpha-stage, solo-maintained project; backporting to older versions is not feasible at this time.

| Version | Supported |
|---------|-----------|
| Latest release (v0.1.x) | Yes |
| Older releases | No |

---

## Scope

### In scope

The following are considered part of EmuHelper's attack surface:

- **Credential and sensitive-data storage** — use of `EncryptedSharedPreferences` and DataStore; what is stored and how it is protected
- **File-write paths** — where downloaded files are written, staging-file handling, and the `.part` → final-file promotion logic
- **Storage Access Framework (SAF) handling** — permission grants, URI persistence, and directory access
- **Cookie storage** — how session cookies are stored, scoped, and cleared
- **The download engine** — connection handling, range requests, and any behavior that could be exploited during a transfer
- **Local network exposure** — any unintended listening socket or local service

### Out of scope

- **Endpoints configured by the operator.** The developer does not control, operate, or have visibility into endpoints that operators supply in their own `Catalog.kt`. Vulnerabilities in third-party services are outside the scope of this project.
- **Content retrieved by the app.** The app is a generic transfer client; the developer has no control over what operators point it at or what files are served.

---

## Response expectations

This is a solo, best-effort open-source project. There is no dedicated security team or SLA. Reported vulnerabilities will be reviewed and addressed as promptly as possible, with priority given to issues that affect user data or device integrity.
