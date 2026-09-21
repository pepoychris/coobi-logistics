# Event contracts in one minute

All topics use six partitions and replication factor one in the local stack. Keys are strings and timestamps are UTC ISO-8601 instants.

| Topic | Key | Producer | Consumer | Failure path |
| --- | --- | --- | --- | --- |
| `logistics.vehicle.location.v1` | `vehicleId` | event-generator | stream-processor | invalid payload -> DLQ |
| `logistics.vehicle.location.dlq.v1` | original key | stream-processor | operators/tests | retained envelope |
| `logistics.alert.v1` | `vehicleId` | stream-processor | logistics-api | persisted alert read model |

## Vehicle telemetry, version 1

```json
{
  "eventId": "6d5b2b22-3b95-4f76-a4ab-6b820b177c4b",
  "eventType": "VEHICLE_LOCATION_UPDATED",
  "version": 1,
  "vehicleId": "TRUCK-0021",
  "timestamp": "2026-09-21T12:32:01Z",
  "data": { "latitude": 39.4699, "longitude": -0.3763, "speed": 78.4, "heading": 182.0 }
}
```

The processor validates required fields, finite coordinates, supported version/type and the Kafka key. Valid records update the per-vehicle state; invalid records never stop the topology.

## Alerts, version 1

```json
{
  "eventId": "2e54a3d4-c53b-46a2-9e0f-cb5db0d4b4d0",
  "eventType": "SPEEDING_DETECTED",
  "version": 1,
  "vehicleId": "TRUCK-0021",
  "severity": "WARNING",
  "timestamp": "2026-09-21T12:32:02Z",
  "data": { "speed": 137.2, "threshold": 120.0 }
}
```

The other alert is `VEHICLE_STOPPED_DETECTED`; both are emitted once per state transition and stored idempotently by `eventId`.

## Dead-letter envelope

```json
{ "originalEvent": "...original JSON...", "error": "latitude must be finite", "failedAt": "2026-09-21T12:32:02Z", "sourceTopic": "logistics.vehicle.location.v1" }
```

Ordering is guaranteed only within a vehicle key/partition. Consumers must tolerate duplicates and replay; persistence uses unique `event_id` plus idempotent upserts.
