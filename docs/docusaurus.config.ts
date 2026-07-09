import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const config: Config = {
  title: 'OmniSign',
  tagline: 'Multiplatform digital signature verification, signing and re-timestamping',
  favicon: 'img/favicon.ico',

  future: {
    v4: true,
    faster: true,
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
      },
    ],
    [
      '@docusaurus/plugin-content-docs',
      {
        id: 'web',
        path: 'docs-web',
        routeBasePath: 'web',
        sidebarPath: './sidebars-web.ts',
        editUrl: 'https://github.com/pizavo/omnisign/tree/main/docs/',
      },
    ],
    [
      '@docusaurus/plugin-content-docs',
      {
        id: 'server',
        path: 'docs-server',
        routeBasePath: 'server',
        sidebarPath: './sidebars-server.ts',
        editUrl: 'https://github.com/pizavo/omnisign/tree/main/docs/',
      },
    ],
    [
      '@docusaurus/plugin-content-docs',
      {
        id: 'development',
        path: 'docs-development',
        routeBasePath: 'development',
        sidebarPath: './sidebars-development.ts',
        editUrl: 'https://github.com/pizavo/omnisign/tree/main/docs/',
      },
    ],
    [
      require.resolve('@easyops-cn/docusaurus-search-local'),
      {
        hashed: true,
        docsPluginIdForPreferredVersion: 'desktop',
        docsDir: ['docs-cli', 'docs-desktop', 'docs-web', 'docs-server', 'docs-development'],
        docsRouteBasePath: ['cli', 'desktop', 'web', 'server', 'development'],
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
          to: '/desktop/',
          label: 'Desktop',
          position: 'left',
          activeBaseRegex: '/desktop/',
        },
        {
          to: '/web/',
          label: 'Web',
          position: 'left',
          activeBaseRegex: '/web/',
        },
        {
          to: '/server/',
          label: 'Server',
          position: 'left',
          activeBaseRegex: '/server/',
        },
        {
          to: '/cli/',
          label: 'CLI',
          position: 'left',
          activeBaseRegex: '/cli/',
        },
        {
          type: 'dropdown',
          label: 'Development',
          position: 'right',
          items: [
            {
              to: '/development/',
              label: 'Overview',
              activeBaseRegex: '/development/',
            },
            {
              to: '/development/architecture',
              label: 'Architecture',
            },
            {
              to: '/development/contributing',
              label: 'Contributing',
            },
            {
              href: 'https://pizavo.github.io/omnisign/api/',
              label: 'API Reference',
            },
          ],
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
            {label: 'Desktop', to: '/desktop/'},
            {label: 'Web', to: '/web/'},
            {label: 'Server', to: '/server/'},
            {label: 'CLI', to: '/cli/'},
          ],
        },
        {
          title: 'Development',
          items: [
            {label: 'Overview', to: '/development/'},
            {label: 'Architecture', to: '/development/architecture'},
            {label: 'Contributing', to: '/development/contributing'},
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
