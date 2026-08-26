# DATA-1 Architecture

The production data path is cloud-only and separates immutable bulk data from small control state:

```text
Official public lottery sources
        ↓
GitHub Actions ephemeral worker
        ↓
parser / validator
        ↓
ephemeral canonical build state
        ↓
immutable versioned corpus artifacts
        ↓
GitHub Releases
        ↓
small manifest/control state
        ↓
Android
        ↓
Room
        ↓
Strategy Engine / Strategy Lab locally
```

The repository holds source, schemas, small catalogs, validation code, coverage reports, provenance definitions, and workflows. GitHub Actions supplies ephemeral acquisition/build workers. GitHub Releases is the target for immutable historical corpus and per-game snapshot bundles. A small public manifest may later use GitHub Pages or equivalent free static hosting.

Firestore Spark is reserved for small control-plane state only. It must never become the per-draw public-client API, bulk historical corpus, personal-ticket database, or Strategy computation service. DATA-1 does not write Firestore, use Firebase credentials, or create service-account secrets.

Android downloads complete relevant per-game corpora into Room; Strategy computation remains on the device. DATA-1 establishes contracts only and performs no acquisition or lottery analysis.
