---
sidebar_position: 2
title: Running examples
---

# Running examples
We've tried to make running examples as easy as possible so you can follow along with the documentation.
There is some setup required to run the examples, but once you've done that you should be able to run any of the examples.

## Pre-requisites
* Docker
* Docker Compose

## Setup
1. Clone the repository
2. Run `docker-compose up -d` in the root of the repository which will spin up Jaegar, Cassandra, Postgres and Kafka
3. Run any of the examples in the project. They are modules suffixed with `-example`

For example, if we wanted to run the `core-examples`, we would run the following command:
```sh
sbt shell
project core-examples
run
```

Check out the [Jaegar UI](http://localhost:16686) to see traces that the examples generate
![Jaegar UI](https://github.com/kaizen-solutions/trace4cats-zio-extras/assets/14280155/3ab924f5-1749-4da2-93a7-3c4aa0d2057b)
