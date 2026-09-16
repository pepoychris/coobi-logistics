# Coobi Logistics

Real-time logistics event processing platform built with Java, Spring Boot, Apache Kafka
and Kafka Streams.

## Purpose

Coobi Logistics simulates a vehicle fleet that continuously produces telemetry events
(position, speed and heading) and runs them through an event-driven pipeline. Events are
published to Kafka, processed in real time, analyzed for relevant conditions such as
speeding or prolonged stops, persisted when they matter and visualized through a live web
dashboard.

The project is a portfolio-grade demonstration of event-driven architecture, stateful
stream processing and sustained high-throughput ingestion.

## Current Status

**MVP-0.1 - Repository structure initialized.**

The repository foundation is in place: the monorepo layout, the shared editor and
environment conventions, and the project documentation entry point.

No service is implemented yet. Kafka, PostgreSQL, the three backend services and the
frontend arrive in the following milestones, which are tracked in the implementation
roadmap as milestones and issues.

## Repository Layout

```text
coobi-logistics/
├── services/
│   ├── event-generator/    # Vehicle telemetry simulator
│   ├── stream-processor/   # Kafka Streams processing service
│   └── logistics-api/      # REST API and real-time event streaming
├── frontend/               # Vue 3 + TypeScript dashboard
├── infrastructure/         # Local infrastructure and observability assets
├── docs/                   # Architecture, event contracts and benchmarks
├── .editorconfig
├── .env.example
├── .gitignore
├── LICENSE
└── README.md
```

The service, frontend, infrastructure and documentation directories are intentionally
empty placeholders at this stage.

## Technology Stack

Java, Spring Boot, Apache Kafka, Kafka Streams, PostgreSQL, Vue 3 with TypeScript,
Server-Sent Events, Docker and Docker Compose, Testcontainers, and Prometheus with
Micrometer.

## Contributing Workflow

Each issue is implemented on its own short-lived branch cut from `develop`, and is
integrated into `develop` through a pull request once it has been reviewed and verified.
The `main` branch only receives fully tested and verified releases.

## License

Released under the MIT License. See [LICENSE](LICENSE).
