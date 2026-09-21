# Single-command local deployment (MVP-10)

This document is the deployment contract of the repository: what `docker compose up --build`
starts, what each image contains, which health check gates which dependency, and how to verify
a running stack. The milestone it describes is MVP-10 of the roadmap.

## The command

```powershell
Copy-Item .env.example .env
# open .env and set POSTGRES_PASSWORD to a local password
docker compose up --build
```

```bash
cp .env.example .env
# open .env and set POSTGRES_PASSWORD to a local password
docker compose up --build
```

That is the whole setup. Nothing but Docker is installed on the machine: no JDK, no Maven, no
Node, no Kafka and no PostgreSQL (Global Rule 18). Every compilation happens inside a build
stage of an image, and every service is started by Compose.

| Item | Value |
| --- | --- |
| Prerequisite | Docker with the Compose plugin, `v2.24` or newer |
| Start | `docker compose up --build` (foreground) or `docker compose up --build -d` |
| Status | `docker compose ps` - a service is `running` and then `healthy` |
| Logs | `docker compose logs -f <service>` |
| Stop | `docker compose down` (add `-v` to delete the named volumes) |
| Dashboard | <http://localhost:5173> |
| API | <http://localhost:8082>/api/v1/vehicles |
| Prometheus | <http://localhost:9090> |

Compose `v2.24` is the first version that accepts the `env_file` entry used below in its long
form (`path` and `required`), which is what lets the stack start with or without a `.env` file.

## What the stack starts

Seven services, of which the two of MVP-0.2 stay exactly as they were:

| Service | Built from | Published on | Health check | Waits for |
| --- | --- | --- | --- | --- |
| `kafka` | `apache/kafka:4.3.1` | `127.0.0.1:9092` | `/opt/kafka/bin/kafka-broker-api-versions.sh` against the broker | - |
| `postgres` | `postgres:18.6` | `127.0.0.1:5432` | `pg_isready` over TCP | - |
| `event-generator` | `./services/event-generator` | `127.0.0.1:8080` | `GET /actuator/health` of the service | `kafka` healthy |
| `stream-processor` | `./services/stream-processor` | `127.0.0.1:8081` | `GET /actuator/health` of the service | `kafka` and `postgres` healthy |
| `logistics-api` | `./services/logistics-api` | `127.0.0.1:8082` | `GET /actuator/health` of the service | `postgres` and `stream-processor` healthy |
| `frontend` | `./frontend` | `127.0.0.1:5173` | `GET /` - the served page | `logistics-api` healthy |
| `prometheus` | `prom/prometheus:v3.14.0-busybox` | `127.0.0.1:9090` | `GET /-/healthy` | - |

Every published port is bound to the loopback interface, so the stack is reachable from this
machine only (Global Rule 8). The same services are reachable from each other by name
(`kafka:29092`, `postgres:5432`, `stream-processor:8081`) on the network Compose creates.

The dashboard is published on the port the development server of the frontend uses, so the URL
a reader opens is the same with and without Node installed. Running `npm run dev` next to the
container needs the container stopped first (`docker compose stop frontend`), because both want
that port.

## The images (MVP-10.1)

Each application ships a Dockerfile next to its own sources, and its build context is its own
directory. The three Maven services are self-contained Maven projects with their own wrapper,
so a context never sees another service, and a change to one service rebuilds one image.

| Image | Build stage | Runtime stage | Runs as |
| --- | --- | --- | --- |
| `event-generator` | `maven:3.9.9-eclipse-temurin-21-alpine` | `eclipse-temurin:21-jre-alpine` | `app`, uid `10001` |
| `stream-processor` | `maven:3.9.9-eclipse-temurin-21-alpine` | `eclipse-temurin:21-jre-alpine` | `app`, uid `10001` |
| `logistics-api` | `maven:3.9.9-eclipse-temurin-21-alpine` | `eclipse-temurin:21-jre-alpine` | `app`, uid `10001` |
| `frontend` | `node:24-alpine` | `nginxinc/nginx-unprivileged:1.30.5-alpine` | `nginx`, uid `101` |

The bases are pinned, and each one is the version the repository already targets:

* `maven:3.9.9` is the distribution the Maven wrapper of every service pins, so the image and a
  local `./mvnw` build with the same Maven;
* `eclipse-temurin:21-jre` is the `java.version` of every service and is a JRE, so the runtime
  image has no compiler and no Maven;
* `node:24-alpine` is the Node line the lockfile of the dashboard is maintained with, and it
  satisfies the `engines` field of its `package.json`;
* `nginxinc/nginx-unprivileged` is the nginx image that is built to run without root, which is
  why the dashboard container does not have to bind a privileged port.

What the stages do:

* a Maven image resolves the dependencies of `pom.xml` before the sources are copied, builds
  the jar with `-Dmaven.test.skip=true`, and copies the single jar the package phase produced
  to `application.jar` - the copy fails the build if the phase produced no jar or more than
  one, instead of shipping an unexpected artifact;
* the runtime stage copies that jar and nothing else: no Maven, no wrapper, no sources, no
  build output;
* the Node stage installs from `package-lock.json` (`npm ci`), then type-checks the project and
  builds the bundle (`vue-tsc --noEmit && vite build`), and the runtime stage copies `dist/`.

Both kinds of image add a `HEALTHCHECK` and run as a non-root user. The Java images also declare
`SERVER_PORT`, so the port of the health check and the port the container listens on are the
same number as the `server.port` of the service. Extra JVM options are added with the standard
`JDK_JAVA_OPTIONS`, for example
`JDK_JAVA_OPTIONS=-XX:MaxRAMPercentage=50.0`; because the entry point is an `exec` form, the
JVM stays PID 1 and the `SIGTERM` of `docker stop` reaches its shutdown hook directly.

### The dashboard container

The image serves the bundle and proxies `/api` to the API, which is the same-origin setup the
Vite dev and preview servers provide (see [frontend.md](frontend.md)): no CORS configuration is
needed on the API, and no absolute URL has to be baked into the bundle. The proxy target is
`API_PROXY_TARGET`, `http://logistics-api:8082` by default, and it is substituted into the nginx
configuration when the container starts, so the same image serves any API of the stack.

Two properties of that proxy are deliberate:

* the target is resolved per request through the DNS server of the Docker network, so
  recreating the API container does not leave the dashboard proxying to the address the old
  container had;
* buffering is off and the read timeout is long, because `/api/v1/stream/events` and
  `/api/v1/stream/statistics` are Server-Sent Events that stay open for as long as a browser is
  connected.

## Health and startup order (MVP-10.3)

The stack does not depend on the order in which containers happen to start. Every dependency is
expressed as a condition on a health check, and each health check asks the thing itself rather
than its port:

| Service | The check is meaningful because |
| --- | --- |
| `kafka` | the Admin API answers only once the broker serves requests |
| `postgres` | `pg_isready` is a TCP connection to the listener, not the local socket |
| `event-generator` | `/actuator/health` is `UP` only once the topics are provisioned and the broker it publishes to answers, and `503` otherwise |
| `stream-processor` | the same endpoint is `UP` only once the context is started - which is after Flyway applied the migrations - and the broker and the database answer |
| `logistics-api` | the endpoint is `UP` only while the database it reads answers |
| `frontend` | the served page is the bundle; it says nothing about `/api` on purpose |
| `prometheus` | `/-/healthy` answers only once the server serves |

```text
kafka ──────┬─> event-generator
            └─> stream-processor ──┐
postgres ───┴──────────────────────┴─> logistics-api ─> frontend

prometheus ── scrapes ──> event-generator, stream-processor   (no startup dependency)
```

Three consequences are worth stating, because they are what "tolerate infrastructure
initialization" means here:

* **A restart of Kafka does not permanently break a service.** Both services keep running while
  the broker is away, their health reports `503` for as long as it is, and both recover when it
  answers again; a container that does exit anyway is restarted by its `restart: unless-stopped`
  policy.
* **A restart of PostgreSQL does not require rebuilding anything.** The processor and the API
  reconnect through their pools, and the schema is already migrated; nothing about the database
  is baked into an image.
* **A restart of one container does not require a restart of another.** The dashboard resolves
  the API per request, so an API container that is recreated is picked up without touching the
  dashboard, and the frontend is deliberately not marked unhealthy by an API that is down: its
  page shows that state instead.

The stack also shuts down the way the services were written to: `server.shutdown: graceful` and
a 20 s shutdown phase are already the configuration of every service, and Compose is given a
`stop_grace_period` of 30 s for each of them, because the 10 s default would cut that phase
short and turn a clean shutdown into a kill.

## Configuration

The stack has one configuration file. `compose.yml` interpolates the values it needs for the
infrastructure, and passes the same `.env` to the three application containers as an
environment file, so:

* a variable `.env.example` documents - `VEHICLE_COUNT`, `SPEED_LIMIT`,
  `COOBI_STREAM_*`, and the rest - reaches the container without being repeated in
  `compose.yml`;
* a variable the file does not define keeps the default the service declares in its own
  `application.yml`;
* `.env` stays optional: the stack resolves from the shell environment alone, it simply has
  fewer overrides then.

The values that differ inside the network are not interpolated from `.env` but set by
`compose.yml`, because a host default such as `localhost` would point a container at itself:

| Variable | Value in the container | Why |
| --- | --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:29092` | the in-network listener of the broker |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://postgres:5432/<POSTGRES_DB>` | the database as a service name |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | the values of `.env` | the same ones the `postgres` container is created with |
| `STREAM_PROCESSOR_METRICS_URL` | `http://stream-processor:8081/actuator/metrics` | the processor as a service name |
| `API_PROXY_TARGET` | `http://logistics-api:8082` | the origin the dashboard proxies `/api` to |

The one variable of the deployment itself is documented in `.env.example` and in
[README.md](../README.md):

| Variable | Default | Scope |
| --- | --- | --- |
| `API_PROXY_TARGET` | `http://logistics-api:8082` | `frontend` container |

The scrape targets are not variables. They are the service names of this stack, written out in
`infrastructure/prometheus/prometheus.yml`, because Prometheus expands `${VAR}` references of
its configuration file in `external_labels` and nowhere else: a variable in a
`static_configs.targets` entry is scraped as its own name. That is what the single
`PROMETHEUS_HOST` of MVP-8 did - it was read by the container, it was never expanded, and both
targets stayed `down`. To scrape a service somebody started from the host instead of the
container, change the one target in that file to `host.docker.internal:8080` or `:8081`;
`compose.yml` maps that name on a Linux engine as well.

One thing a container does not inherit is a host-only override of a service. Setting
`SPRING_DATASOURCE_URL` in `.env` changes where a service run from the host connects, and it is
ignored by the container of the stack, which is always pointed at the `postgres` service of its
own network.

## Verifying the stack

The contract of this document is checkable without starting anything:

```powershell
pwsh -File infrastructure/scripts/verify-deployment.ps1
```

The script asserts that `docker compose config` resolves, that the seven services are declared
with the dependencies, health checks and build contexts this document describes, that each
Dockerfile is multi-stage, non-root and health-checked, and that the addresses the stack sets in
the containers are the ones above. Add `-Stack` to start the stack, wait for it to become
healthy and probe it over HTTP:

```powershell
pwsh -File infrastructure/scripts/verify-deployment.ps1 -Stack
```

The same checks by hand, without Docker installed at all for the first one:

```bash
docker compose config --quiet
docker compose config
docker compose up --build -d
docker compose ps
```

`docker compose ps` reports a health status per service once it has one. The endpoints of a
running stack:

```powershell
(Invoke-RestMethod http://localhost:8080/actuator/health).status
(Invoke-RestMethod http://localhost:8081/actuator/health).status
(Invoke-RestMethod http://localhost:8082/actuator/health).status
(Invoke-WebRequest http://localhost:5173/ -UseBasicParsing).StatusCode
(Invoke-RestMethod 'http://localhost:8082/api/v1/statistics')
(Invoke-RestMethod 'http://localhost:9090/api/v1/targets').data.activeTargets |
    Select-Object scrapeUrl, health, lastError
```

The three `/actuator/health` endpoints answer `UP` while the broker and the database they need
are up. The dashboard is served on `5173`, and the API is reachable both directly and through
the dashboard at `http://localhost:5173/api/v1/vehicles`. In Prometheus, both scrape targets
are `up` within one scrape interval of the stack being healthy.

## Running a service from the host instead

The containerized stack does not remove the workflow the earlier milestones documented. To work
on a service in the IDE, start the infrastructure and leave that one service out:

```bash
docker compose up -d kafka postgres prometheus
mvn -f services/stream-processor/pom.xml spring-boot:run
```

A service started that way reads the same `.env` variables and uses its own defaults, which is
what the `localhost` scope documented in `.env.example` describes. The parameters of the
service ports are the same on both sides (`8080`, `8081`, `8082`), so the published ports of the
stack are what a host-run service replaces when it takes over one of them.

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `docker compose up` fails with `POSTGRES_PASSWORD must be set` | `.env` was not copied from `.env.example`, or the password is still empty. Copy the file and set one |
| A service stays `unhealthy` and the services behind it never start | Its health check is failing on purpose. Read it with `docker compose logs <service>`: an unreachable broker, an unreachable database or a failed migration is what turns `/actuator/health` into `503` |
| The dashboard answers but every panel says disconnected | The API is not reachable from the browser through the container. Check `docker compose logs frontend logistics-api` and that the API answers `http://localhost:8082/actuator/health` |
| The dashboard is on `5173` and `npm run dev` cannot bind | Both want the same port. `docker compose stop frontend` to use the development server |
| A scrape target is `down` in Prometheus | The service it names is not running, or its target was changed in `infrastructure/prometheus/prometheus.yml`. The two shipped targets are the service names of the stack |
| A service cannot authenticate against PostgreSQL after the password in `.env` changed | The `postgres` image applies `POSTGRES_PASSWORD` when it *initializes* the volume and never again, so a volume created with an older password keeps it - the log says `Skipping initialization`. Either put the old password back in `.env`, or start from a fresh database: `docker compose down -v` deletes the local volumes of the stack |
| Port `5432`, `9092`, `9090`, `8080`, `8081`, `8082` or `5173` is already in use | Another stack is running - possibly the MVP-0.2 one - or a service is running from the host. `docker compose down` the other stack, or stop whatever holds the port; `docker compose ps` lists the containers of this one |
| An image build fails while resolving Maven or npm dependencies | The build needs network access to the registries. Re-run it, or start from `docker compose build <service>` to see the failing step alone |
