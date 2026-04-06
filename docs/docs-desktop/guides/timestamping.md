---
sidebar_position: 5
---

# Extending Signatures (Timestamping)

This guide explains how to extend an already-signed PDF to a higher PAdES level using the
OmniSign Desktop timestamp dialog.

## When to use

| Current level    | Target          | Operation type      |
|------------------|-----------------|---------------------|
| B-B              | B-LT            | Signature Timestamp |
| B-B / B-T / B-LT | B-LTA           | Archival Timestamp  |
| B-LTA            | B-LTA (renewed) | Archival Timestamp  |

:::note
**B-T** is not a directly selectable target. It can only be reached as a fallback when
Signature Timestamp (B-LT) is requested, but revocation data cannot be obtained.
:::

## 1. Open a signed PDF

Open a signed PDF using the toolbar folder icon or by dragging it into the window.

## 2. Open the timestamp dialog

Click the **stamp icon** in the center of the toolbar. The button is only enabled when a
document is loaded.

## 3. Choose the timestamp type

Select the operation type from the dropdown:

- **Signature Timestamp** — adds a signature timestamp and embeds revocation data (CRL/OCSP),
  extending the document to **PAdES BASELINE-LT**. If revocation data cannot be obtained, you
  may be offered a fallback to BASELINE-T.
- **Archival Timestamp** — adds an archival document timestamp, extending the document to
  **PAdES BASELINE-LTA**. Also used to renew an existing LTA document.

:::note
Some timestamp types may be disabled depending on the document's current signature level.
For example, if the document already has a document timestamp, the Signature Timestamp
option is greyed out because DSS would reject the level degradation.
:::

## 4. Configure the output path

The **Output file** field is pre-filled with a suggested path based on the input file name.
Edit it if you want to write to a different location.

### Add to renewal job

When the selected target is B-LTA, an **Add to renewal job** checkbox appears. Checking it
will offer to assign the output file to a renewal job after a successful extension,
ensuring automatic digital continuity.

If the output file is already covered by an existing renewal job's glob patterns, the
checkbox is forced on and disabled — the file will be renewed regardless.

## 5. Extend

Click **Extend** to start the operation.

### Revocation warning

If the extension targets B-LT and revocation data cannot be retrieved, OmniSign shows a
warning dialog listing the affected certificates and the issues encountered. You can:

- **Abort** — discard the output and return to the form.
- **Continue** — accept the fallback to B-T (signature timestamp without embedded
  revocation data).

## 6. Review the result

On success, the dialog shows:

- The **output file** path.
- The **new PAdES level** achieved (e.g., BASELINE-LTA).
- Any **warnings** produced during the operation, categorized by severity.

Closing the dialog automatically reloads the extended document in the viewer.

## 7. Renewal job offer

When extending to B-LTA, OmniSign may show a **renewal job offer** dialog. You can:

- **Assign to an existing job** — select a configured renewal job from the dropdown.
- **Create a new job** — define a new renewal job with a name, glob pattern, buffer days,
  and an optional profile.

The offer is also shown if the output file is not yet covered by any existing job's patterns.


