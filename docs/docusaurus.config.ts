import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const config: Config = {
  title: 'OmniSign',
  tagline: 'Multiplatform digital signature verification, signing and re-timestamping',
  favicon: 'img/favicon.ico',

  future: {
    v4: true,
  },

  url: 'https://pizavo.github.io',
  baseUrl: '/omnisign/',

  organizationName: 'pizavo',
  projectName: 'omnisign',

  onBrokenLinks: 'throw',
  markdown: {
    hooks: {
      onBrokenMarkdownLinks: 'warn',
    },
  },

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      {
        docs: false,
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  plugins: [
    [
      '@docusaurus/plugin-content-docs',
      {
        id: 'cli',
        path: 'docs-cli',
        routeBasePath: 'cli',
        sidebarPath: './sidebars-cli.ts',
        editUrl: 'https://github.com/pizavo/omnisign/tree/main/docs/',
        lastVersion: 'current',
        versions: {
          current: {
            label: 'v1.11.0',
          },
        },
      },
    ],
    [
      '@docusaurus/plugin-content-docs',
      {
        id: 'desktop',
        path: 'docs-desktop',
        routeBasePath: 'desktop',
        sidebarPath: './sidebars-desktop.ts',
        editUrl: 'https://github.com/pizavo/omnisign/tree/main/docs/',
        lastVersion: 'current',
        versions: {
          current: {
            label: 'v1.3.0',
          },
        },
      },
    ],
    [
      '@docusaurus/plugin-content-docs',
      {
        id: 'server-web',
        path: 'docs-server-web',
        routeBasePath: 'server-web',
        sidebarPath: './sidebars-server-web.ts',
        editUrl: 'https://github.com/pizavo/omnisign/tree/main/docs/',
        lastVersion: 'current',
        versions: {
          current: {
            label: 'Latest',
          },
        },
      },
    ],
    [
      require.resolve('@easyops-cn/docusaurus-search-local'),
      {
        hashed: true,
        docsPluginIdForPreferredVersion: 'cli',
        docsDir: ['docs-cli', 'docs-desktop', 'docs-server-web'],
        docsRouteBasePath: ['cli', 'desktop', 'server-web'],
      },
    ],
  ],

  themeConfig: {
    image: 'img/docusaurus-social-card.jpg',
    colorMode: {
      respectPrefersColorScheme: true,
    },
    navbar: {
      title: 'OmniSign',
      logo: {
        alt: 'OmniSign Logo',
        src: 'img/logo.png',
      },
      items: [
        {
          to: '/cli/',
          label: 'CLI',
          position: 'left',
          activeBaseRegex: '/cli/',
        },
        {
          to: '/desktop/',
          label: 'Desktop',
          position: 'left',
          activeBaseRegex: '/desktop/',
        },
        {
          to: '/server-web/',
          label: 'Web & Server',
          position: 'left',
          activeBaseRegex: '/server-web/',
        },
        {
          href: 'https://pizavo.github.io/omnisign/api/',
          label: 'API Reference',
          position: 'left',
        },
        {
          type: 'docsVersionDropdown',
          docsPluginId: 'cli',
          position: 'right',
          dropdownActiveClassDisabled: true,
        },
        {
          type: 'docsVersionDropdown',
          docsPluginId: 'desktop',
          position: 'right',
          dropdownActiveClassDisabled: true,
        },
        {
          href: 'https://github.com/pizavo/omnisign',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Documentation',
          items: [
            {label: 'CLI', to: '/cli/'},
            {label: 'Desktop', to: '/desktop/'},
            {label: 'Web & Server', to: '/server-web/'},
            {label: 'API Reference', href: 'https://pizavo.github.io/omnisign/api/'},
          ],
        },
        {
          title: 'More',
          items: [
            {
              label: 'GitHub',
              href: 'https://github.com/pizavo/omnisign',
            },
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} OmniSign. Built with Docusaurus.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['kotlin', 'bash', 'powershell', 'json', 'yaml'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
