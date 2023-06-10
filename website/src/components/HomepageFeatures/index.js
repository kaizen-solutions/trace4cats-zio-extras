import React from 'react';
import clsx from 'clsx';
import styles from './styles.module.css';

const FeatureList = [
  {
    title: 'Focus on What Matters',
    Svg: require('@site/static/img/magnifying-glass.svg').default,
    description: (
      <>
        Pinpoint issues in production and find bottlenecks
      </>
    ),
  },
  {
    title: 'Distributed tracing made easy',
    Svg: require('@site/static/img/spans-traces.svg').default,
    description: (
      <>
        Trace4Cats ZIO Extras was designed to be used with ZIO and
        makes it easy to add distributed tracing to your ZIO applications.
      </>
    )
  },
  {
    title: 'Powered by Functional Programming',
    Svg: require('@site/static/img/scala.svg').default,
    description: (
      <>
        Trace4Cats ZIO Extras is built on top of <b>ZIO</b> and <b>Cats Effect</b> to empower your applications.
      </>
    ),
  },
];

function Feature({Svg, title, description}) {
  return (
    <div className={clsx('col col--4')}>
      <div className="text--center">
        <Svg className={styles.featureSvg} role="img" />
      </div>
      <div className="text--center padding-horiz--md">
        <h3>{title}</h3>
        <p>{description}</p>
      </div>
    </div>
  );
}

export default function HomepageFeatures() {
  return (
    <section className={styles.features}>
      <div className="container">
        <div className="row">
          {FeatureList.map((props, idx) => (
            <Feature key={idx} {...props} />
          ))}
        </div>
      </div>
    </section>
  );
}
