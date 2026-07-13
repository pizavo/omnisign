# Third-party notices

OmniSign is distributed under the GNU Affero General Public License v3.0 or later; see
[LICENSE.md](LICENSE.md).

This file records third-party material **copied into this repository**. Libraries resolved at build
time by Gradle and npm are not listed here, because their licences travel inside their own artifacts.

## Tabler Icons

The icons under `composeApp/src/commonMain/composeResources/drawable/` are
[Tabler Icons](https://tabler.io/icons) by Paweł Kuna, used under the MIT License.

Of the 61 icons in that directory:

- 58 are Tabler icons whose artwork is unmodified.
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
