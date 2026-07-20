import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';
import apiSidebar from './docs-server/api/sidebar';

const sidebars: SidebarsConfig = {
  serverSidebar: [
    'index',
    'configuration',
    'signing-policy',
    'authentication',
    'security',
    'deployment',
    {
      type: 'category',
      label: 'HTTP API',
      link: {type: 'generated-index', title: 'OmniSign Server HTTP API'},
      items: apiSidebar as never,
    },
  ],
};

export default sidebars;
