# Printer after-sales acceptance report

## This delivery

| Audit item | Change | Verification | Result |
|---|---|---|---|
| P0-01 document body/version loss | Async consumers now load the persisted document, validate `documentId`/version/hash, and index that version without creating another version. Status writes are version fenced. Empty or mismatched persisted content is rejected and requires re-upload. | `DocumentIndexTaskServiceTest`, `PrinterApplicationServiceIntegrationTest`, `mvn test` | Passed for the local MySQL/in-memory path |
| P0-02 official Graph ownership | Official Graph GET/approval paths require ownership recorded in the MySQL checkpoint shadow; rejected approval is persisted. | Compile and existing Alibaba Graph test | Code path covered; Alibaba-enabled HTTP/concurrent run remains unavailable without the optional runtime setup |
| P1 application state/idempotency | V14 adds application version, request fingerprint, and status-event storage. Status transitions reject illegal rollback and update with a version fence; same idempotency key with a different request is a conflict. | Targeted application integration test | Passed on the local database |

## Explicit remaining limits

Real Elasticsearch/Kafka, external LLM, Redis health, multi-node lease races, browser request cancellation/SSE recovery, and a cold-start rebuild of arbitrary uploaded files were not run in this environment. They must not be reported as production-ready until the required services are available.
