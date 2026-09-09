# ZKFinger Backend API

Fingerprint capture, enrolment and one-to-many identification over HTTP, driven by a ZKTeco
ZKFinger reader. It is a drop-in counterpart to the Suprema BioMini service: the paths,
request parameters and response keys match, so a client can be pointed at this port instead
with no code change.

Runs on **port 8070** by default, so it can sit alongside the BioMini service on the same host.

---

## Before you start: the native library

The vendor `ZKFingerReader.jar` is a thin JNI wrapper. It calls
`System.loadLibrary("libzkfp")` and expects that library to export the
`Java_com_zkteco_biometric_*` JNI entry points.

**The `ZKFingerSDK_Windows_ZK10_Standard` bundle in this repository does not ship that
library.** The `libzkfp.dll` installed in `System32` and `SysWOW64` exports only the plain C
API (the `ZKFPM_*` symbols) and no JNI symbols. Until the correct library is present:

```
GET /init  ->  503
{"response_msg": "ZKFinger SDK initialisation failed: failed to load the native library (code -1) ..."}
```

This is a missing binary, not an application fault. No amount of `java.library.path`
configuration fixes it, and the vendor Java demo fails the same way for the same reason.

To resolve it, either:

1. **Install the vendor ZKFinger Reader driver/SDK installer** (not just the loose DLLs). It
   installs a `libzkfp` built with the JNI bridge. This is the supported route.
2. **Bind the C API directly** using JNA or the Java 22+ FFM API against the `ZKFPM_*` exports
   that are already present. The device layer sits behind
   [`FingerprintDevice`](src/main/java/com/unionsg/zkfinger/device/FingerprintDevice.java), so
   only one class has to be replaced.

The service starts and stays up either way. It reports itself degraded, serves `/health`, and
reconnects on its own once a reader becomes available.

Also note the reader must be a genuine ZK USB device. A laptop's built-in fingerprint sensor
is not driven by this SDK.

---

## Requirements

| Component | Version |
|---|---|
| JDK | 17 or newer |
| Maven | 3.9 or newer |
| PostgreSQL | any currently supported release |
| Reader | ZKTeco ZKFinger, with the vendor driver installed |

## Build and run

The SDK jar is vendored under `lib/` because it is not published to Maven Central. The build
installs it into the local repository automatically, so an ordinary build works:

```bash
mvn package
java -jar target/zkfinger-backend-api-1.0.0.jar
```

If a fresh clone fails to resolve `com.zkteco:zkfinger-reader`, install it once by hand:

```bash
mvn install:install-file -Dfile=lib/ZKFingerReader.jar \
  -DgroupId=com.zkteco -DartifactId=zkfinger-reader -Dversion=10.0 -Dpackaging=jar
```

Run the tests with `mvn test`. They use an in-memory fake reader, so they need neither
hardware nor the native library.

## Configuration

Nothing sensitive is committed. Every setting is an environment variable with a safe default,
listed in [`application.yml`](src/main/resources/application.yml).

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8070` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/image` | Template database |
| `DB_USERNAME` / `DB_PASSWORD` | `postgres` / empty | Database credentials |
| `EXTERNAL_API_BASE_URL` | `http://10.203.14.169/imaging/` | Customer lookup service |
| `CORS_ALLOWED_ORIGINS` | localhost only | Browser origins allowed to drive the reader |
| `FP_TEMPLATE_FORMAT` | `0` | 0 = ANSI-378, 1 = ISO 19794-2, 2 = ZK |
| `FP_MATCH_THRESHOLD` | `60` | Minimum matcher score treated as a match |
| `FP_CAPTURE_TIMEOUT` | `15s` | How long a capture waits for a finger |
| `FP_DEVICE_INDEX` | `0` | Which reader to open |

Two settings deserve care in production:

- **`FP_TEMPLATE_FORMAT` must match the format of the templates already stored.** A mismatch
  does not raise an error; every comparison simply fails to match.
- **`FP_MATCH_THRESHOLD` is a security control.** The vendor demo treats any positive score as
  a match, which is far too permissive for a one-to-many search. Raise it if false matches
  appear, lower it if genuine fingers are rejected, and tune it against real data.

## Database

The service reads and writes `TB_TBLSIG_DETAILS_TEMP`, shared with the BioMini service.
[`db/schema.sql`](src/main/resources/db/schema.sql) documents the columns; it is a reference,
not a migration, and is never executed at startup.

The upsert used when saving requires a unique constraint on `RELATION_NO`. An older table
without one must be altered before use:

```sql
ALTER TABLE TB_TBLSIG_DETAILS_TEMP
  ADD CONSTRAINT UQ_TBLSIG_DETAILS_TEMP_RELATION_NO UNIQUE (RELATION_NO);
```

## API

Every failure carries `response_code` of `-1` and a `response_msg`, alongside a matching HTTP
status. Success carries `response_code` of `1`.

### Compatible with the BioMini service

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/init` | Opens the reader. Returns `1` when ready. |
| `POST` | `/capture` | Captures one press and stores it. Params: `relation_no`, `thumbprint`. |
| `POST` | `/verify` | Captures a finger and searches every stored template. |
| `GET` | `/health` | Reader and database status. |
| `GET` | `/verification-stats` | Pipeline tuning and runtime detail. |
| `GET` | `/hello` | Smoke test. |

`thumbprint` selects which of the two stored fingers to use: `1` or `2`.

### New in this service

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/enroll` | Merges three presses into one template. Far more reliable for search. |
| `POST` | `/verify-one` | One-to-one check against a known `relation_no`. Much cheaper than `/verify`. |
| `GET` | `/enrolled` | Whether a customer already has a template on file. |
| `POST` | `/shutdown-device` | Releases the reader for another process. |

Prefer `/enroll` over `/capture` when registering someone. A merged template is what the
ZKFinger matcher is designed for and identifies noticeably more reliably.

### Examples

```bash
# Enrol a customer, three presses
curl -X POST "http://localhost:8070/enroll?relation_no=123456&thumbprint=1"

# Identify whoever is at the reader
curl -X POST "http://localhost:8070/verify?thumbprint=1"

# Confirm a specific customer
curl -X POST "http://localhost:8070/verify-one?relation_no=123456&thumbprint=1"
```

A successful `/verify`:

```json
{
  "user_verified": true,
  "total_records_checked": 1841,
  "verification_time_ms": 412,
  "threads_used": 8,
  "matched_relation_no": "123456",
  "match_score": 178,
  "customer_details": [ ... ]
}
```

## How it is put together

```
web/         HTTP contract and error translation
service/     Capture, enrolment and one-to-many search
repository/  Template persistence
device/      The reader, behind an interface
domain/      Records passed between the layers
config/      Settings, CORS, startup and reconnection
```

Three things are worth knowing when changing it:

- **The reader is single-threaded and has one platen.** Captures are serialised, so concurrent
  requests queue rather than interleaving presses from different people.
- **The matcher handle is not thread-safe.** Native calls are serialised in the device layer.
  The worker pool in identification overlaps database paging with matching; it does not run
  the matcher in parallel.
- **Missing hardware is not a startup failure.** A terminal is routinely started before the
  reader is plugged in, and a service that refuses to boot cannot report why. It starts,
  reports degraded, and reconnects when the reader appears.

## Known limitations

- The reader is unusable until the JNI bridge described at the top is installed.
- Quality is reported as template length. The SDK exposes no separate quality score through
  this API, so the value is meaningful for relative comparison only.
- There is no authentication. The service drives physical hardware and should be reachable
  only from the workstation and clients that need it. Restrict `CORS_ALLOWED_ORIGINS` and keep
  the port off untrusted networks.
