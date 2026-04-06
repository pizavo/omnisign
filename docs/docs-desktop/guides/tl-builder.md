---
sidebar_position: 6
---

# Building a Custom Trusted List

OmniSign includes a built-in ETSI TS 119 612 Trusted List compiler that lets you create
your own Trusted List XML documents. This is useful when your organization's CA is not part
of the EU LOTL and you want OmniSign to trust it during validation and signing.

## Opening the builder

The Trusted List builder can be opened from two places:

- **Settings → Validation → Trusted Lists** — click the **Build** button.
- **Profile editor → Trusted Lists** — click the **Build** button within a profile's
  trusted list section.

## 1. Fill in the list metadata

| Field                    | Description                                                          |
|--------------------------|----------------------------------------------------------------------|
| **Name**                 | Unique identifier for the list (also used as the default file stem). |
| **Territory**            | ISO 3166-1 alpha-2 code (e.g. `CZ`, `SK`, `XX` for custom).          |
| **Scheme Operator Name** | Name of the entity publishing this trusted list.                     |
| **Output path**          | File path where the compiled XML will be written.                    |

## 2. Add Trust Service Providers (TSPs)

Click **Add TSP** to create a new provider entry. Each TSP card is expandable and contains:

| Field          | Description                                          |
|----------------|------------------------------------------------------|
| **Name**       | Official name of the TSP.                            |
| **Trade name** | Optional brand or trade name.                        |
| **Info URL**   | URL pointing to the TSP's information page.          |

### Add services to a TSP

Within each TSP card, click **Add Service** to register a trust service. Each service has:

| Field                  | Description                                                                  |
|------------------------|------------------------------------------------------------------------------|
| **Name**               | Human-readable name of the service.                                          |
| **Type identifier**    | ETSI service type URI. A dropdown provides common hints (e.g. CA/QC, TSA).   |
| **Status**             | ETSI service status URI. A dropdown provides common hints (e.g. granted).    |
| **Certificate**        | Path to the PEM or DER certificate file. Use the file picker to browse.      |

:::tip
The type identifier and status dropdowns include the most common ETSI URIs. You can also
type a custom URI directly into the text field.
:::

## 3. Register after compile

The **Register after compile** checkbox (checked by default) controls whether the compiled
Trusted List is automatically added to the settings or profile from which the builder was
opened. When unchecked, the XML is written to disk but not registered.

## 4. Compile

Click **Compile** to generate the Trusted List XML. The compiler validates all required
fields and writes the output file. On success, the dialog shows the output path and — when
registration is enabled — the resulting trusted list entry that was added.

### Errors

If validation fails (e.g., a required field is empty or a certificate file cannot be read),
the error message is displayed inline in the form. Compilation errors show a detailed
message with optional technical details.

