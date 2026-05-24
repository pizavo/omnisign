---
sidebar_position: 3
---

# Features

| Feature                                     | Supported |
|---------------------------------------------|:---------:|
| Open and view PDF documents                 |     ✅     |
| Page navigation and zoom (25 %–400 %)       |     ✅     |
| Sign PDF documents (PAdES B / B-LT / B-LTA) |     ✅     |
| Validate signed PDFs (PAdES B–LTA)          |     ✅     |
| Timestamp extension (B-LT / B-LTA)          |     ✅     |
| Revocation warning handling                 |     ✅     |
| Export validation report (TXT, JSON, XML)   |     ✅     |
| Configuration profiles (CRUD)               |     ✅     |
| Settings dialog (IntelliJ-style categories) |     ✅     |
| Trusted certificates overview panel         |     ✅     |
| Custom ETSI Trusted List builder            |     ✅     |
| Archival renewal jobs and scheduling        |     ✅     |
| Headless renewal mode (OS scheduler)        |     ✅     |
| PKCS#11 hardware token support              |     ✅     |
| PKCS#12 file-based certificates             |     ✅     |
| Source-aware certificate selection          |     ✅     |
| Live PKCS#11 token detection (hot-plug)     |     ✅     |
| Manual token rescan with toast feedback     |     ✅     |
| Dark / light theme toggle                   |     ✅     |
| Window position and size persistence        |     ✅     |
| JBR custom title bar (native frame)         |     ✅     |
| Trusted certificate management (CA/TSA)     |     ✅     |
| Configuration export / import (backup)      |     ✅     |
| Manual trusted-list refresh                 |     ✅     |
| Support log export & debug logging          |     ✅     |
| Native vs custom title bar (Linux)          |     ✅     |

## Toolbar actions

All primary actions are accessible from the toolbar at the top of the window:

| Button        | Location | Description                                                     |
|---------------|----------|-----------------------------------------------------------------|
| 📂 Open file  | Left     | Opens a system file picker for PDF files.                       |
| ✒️ Sign       | Centre   | Opens the signing dialog (requires a loaded PDF).               |
| 🕐 Timestamp  | Centre   | Opens the timestamp / extension dialog (requires a loaded PDF). |
| ⚙️ Settings   | Right    | Opens the global settings dialog.                               |
| 🌙 / ☀️ Theme | Right    | Toggles between dark and light theme.                           |

![OmniSign toolbar](/img/desktop/toolbar.avif)

## Sidebar panels

![Right-hand sidebar panel open](/img/desktop/sidebar-panel-open.avif)

| Panel                | Side  | Description                                                |
|----------------------|-------|------------------------------------------------------------|
| Signatures           | Left  | Validate and inspect signatures in the loaded document.    |
| Profiles             | Right | Create, edit, activate, and delete configuration profiles. |
| Trusted Certificates | Right | Read-only overview of trusted CA/TSA certificates.         |
| Help                 | Right | Repo & docs links, plus Support tools (log export, debug logging). |
