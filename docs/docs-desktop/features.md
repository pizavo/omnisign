---
sidebar_position: 3
---

import AppIcon from '@site/src/components/AppIcon';

# Features

| Feature                                     | Supported |
|---------------------------------------------|:---------:|
| Open and view PDF documents                 |     ✅     |
| Page navigation and zoom (25 %–400 %)       |     ✅     |
| Sign PDF documents (PAdES B / B-LT / B-LTA) |     ✅     |
| Validate signed PDFs (PAdES B–LTA)          |     ✅     |
| EU LOTL emblems (signatures & timestamps)   |     ✅     |
| Off-LOTL alert (global + per-profile)       |     ✅     |
| Timestamp extension (B-LT / B-LTA)          |     ✅     |
| Revocation warning handling                 |     ✅     |
| Export validation report (TXT, JSON, XML)   |     ✅     |
| Selectable / copyable report text           |     ✅     |
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
| Trusted-list load progress bar              |     ✅     |
| Support log export & debug logging          |     ✅     |
| Native vs custom title bar (Linux)          |     ✅     |

## Toolbar actions

All primary actions are accessible from the toolbar at the top of the window:

| Button                                                                                        | Location | Description                                                     |
|-----------------------------------------------------------------------------------------------|----------|-----------------------------------------------------------------|
| <AppIcon name="folder" color="folder" label="Open file" /> Open file                          | Left     | Opens a system file picker for PDF files.                       |
| <AppIcon name="sign" label="Sign" /> Sign                                                     | Center   | Opens the signing dialog (requires a loaded PDF).               |
| <AppIcon name="stamp" label="Timestamp" /> Timestamp                                          | Center   | Opens the timestamp / extension dialog (requires a loaded PDF). |
| <AppIcon name="settings" label="Settings" /> Settings                                         | Right    | Opens the global settings dialog.                               |
| <AppIcon name="moon" label="Dark theme" /> / <AppIcon name="sun" label="Light theme" /> Theme | Right    | Toggles between dark and light theme.                           |

![OmniSign toolbar](/img/desktop/toolbar.avif)

## Sidebar panels

![Right-hand sidebar panel open](/img/desktop/sidebar-panel-open.avif)

| Panel                                                                                  | Side  | Description                                                        |
|----------------------------------------------------------------------------------------|-------|--------------------------------------------------------------------|
| <AppIcon name="signature" label="Signatures panel" /> Signatures                       | Left  | Validate and inspect signatures in the loaded document.            |
| <AppIcon name="profile" label="Profiles panel" /> Profiles                             | Right | Create, edit, activate, and delete configuration profiles.         |
| <AppIcon name="certificate" label="Trusted Certificates panel" /> Trusted Certificates | Right | Read-only overview of trusted CA/TSA certificates.                 |
| <AppIcon name="help" label="Help panel" /> Help                                        | Right | Repo & docs links, plus Support tools (log export, debug logging). |
