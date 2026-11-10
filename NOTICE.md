# Notices

## OmniSign

OmniSign – Multiplatform digital signature verification, signing and re-timestamping app  
Copyright (C) 2026 Pizavo <[pizavo@gmail.com](mailto:pizavo@gmail.com)>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program. If not, see <https://www.gnu.org/licenses/>.

The licence's full text is in [LICENSE.md](LICENSE.md).

## Third-party material

The sections below record third-party material **copied into this repository**. Libraries resolved
at build time and shipped inside the installers are listed in [THIRD-PARTY.md](THIRD-PARTY.md),
together with their licences and copyright notices; the full text of every licence involved is in
[`licenses/`](licenses/). Both files are installed alongside the application and are reachable from
the desktop app under Help → Credits.

### EU DSS

OmniSign is built on [EU DSS (Digital Signature Services)](https://github.com/esig/dss), which the
CLI, server and desktop packages include. DSS is used under the **GNU Lesser General Public License,
version 2.1 or later**; its full text is in [`licenses/LGPL-2.1.txt`](licenses/LGPL-2.1.txt).

Copyright © 2015 European Commission, provided under the CEF programme.

DSS is a separate work with its own licence, not part of OmniSign. Its sources are available from the
project above and from the same place this distribution was obtained. OmniSign links DSS without
modifying it, and the desktop and server packages ship it as separate, replaceable jars. The web
build contains no DSS at all — it runs in WebAssembly, where a Java library cannot execute, and
delegates signing and validation to the server.

This notice is given because the DSS artifacts published to Maven Central carry no licence text of
their own — the licence is declared only in their POM metadata, which is not distributed with the
application.

### Tabler Icons

The icons under `composeApp/src/commonMain/composeResources/drawable/` are
[Tabler Icons](https://tabler.io/icons) by Paweł Kuna, used under the MIT License.

Of the 62 icons in that directory:

- 59 are Tabler icons whose artwork is unmodified.
- `icon_window_restore.svg` is derived from Tabler's `copy` icon.
- `icon_eu.svg` and `icon_eu_crossed.svg` are original OmniSign artwork, not Tabler icons.

All of them have their stroke and fill set to `black` rather than Tabler's `currentColor`, so that
Compose can recolour them at render time.

The documentation site regenerates its own copies of these files into `docs/src/icons/` during its
build (`docs/scripts/generate-icons.mjs`), so this notice covers those as well.

```
MIT License

Copyright (c) 2020-2026 Paweł Kuna

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
