import type {ReactNode} from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';

import styles from './index.module.css';

type DocCard = {
  title: string;
  to: string;
  description: string;
  emoji: string;
};

const cards: DocCard[] = [
  {
    title: 'CLI',
    to: '/cli/',
    description:
      'Sign, validate, and re-timestamp PDF documents from the command line on any OS.',
    emoji: '⌨️',
  },
  {
    title: 'Desktop',
    to: '/desktop/',
    description:
      'A graphical application for signing and validating documents on Linux, Windows, and macOS.',
    emoji: '🖥️',
  },
  {
    title: 'Web & Server',
    to: '/server-web/',
    description:
      'Deploy a Ktor server with a Wasm-powered web frontend for organisation-wide signing and archiving.',
    emoji: '🌐',
  },
];

function HomepageHeader() {
  const {siteConfig} = useDocusaurusContext();
  return (
    <header className={clsx('hero hero--primary', styles.heroBanner)}>
      <div className="container">
        <Heading as="h1" className="hero__title">
          {siteConfig.title}
        </Heading>
        <p className="hero__subtitle">{siteConfig.tagline}</p>
      </div>
    </header>
  );
}

function DocCards() {
  return (
    <section className={styles.features}>
      <div className="container">
        <div className="row">
          {cards.map(({title, to, description, emoji}) => (
            <div key={title} className={clsx('col col--4')}>
              <Link to={to} className={styles.card}>
                <div className="text--center padding-horiz--md">
                  <p style={{fontSize: '3rem'}}>{emoji}</p>
                  <Heading as="h3">{title}</Heading>
                  <p>{description}</p>
                </div>
              </Link>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

export default function Home(): ReactNode {
  return (
    <Layout
      title="Home"
      description="OmniSign — multiplatform digital signature verification, signing and re-timestamping.">
      <HomepageHeader />
      <main>
        <DocCards />
      </main>
    </Layout>
  );
}
