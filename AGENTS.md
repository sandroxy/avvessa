# Repository conventions

- Tracked files must be suitable for this public repository: maintained implementation, public contracts, consumer documentation, build tools, and relevant tests.
- Keep investigation, plans, progress, private references, and temporary verification records in the ignored `doc/dev/` directory. Never force-add it or link public documentation to it.
- Keep user guides focused on integration and public behavior. Maintain development details in `DEVELOPMENT.md`, publication rules in `RELEASING.md`, and user-visible version changes in `CHANGELOG.md`. Link to the authoritative source instead of duplicating it; move useful information before deleting a document.
- Name code and documents by responsibility. Do not encode stages, priorities, or replacement history in names. Semantic package versions and external protocol versions remain explicit.
- Android/iOS cores depend on the platform and official Jiguang SDKs, not React Native, Expo, or DCloud. Adapters own framework-specific integration.
- Business authentication, account binding, routes, payload interpretation, and sending services belong to consumers.
- Consent, notification permission, SDK state, registration, acknowledgement, and subscription lifetime have distinct semantics. Unsubscribing must not stop the SDK.
- Invalid configuration fails explicitly. Map documented SDK payloads without guessing aliases or silently discarding errors. Preserve the original payload.
- Optional capabilities must not silently add permissions, dependencies, or initialization to the base package.
- Keep credentials, production signing, machine paths, SDK downloads, generated projects, and build outputs out of Git. Obtain dependencies through pinned package metadata.
- Consumer hosts and acceptance orchestration belong to the separate integration repository; follow its instructions before changing it.
- Validate affected implementation locally. Distinguish compilation, isolated logic checks, device behavior, and actual push delivery. Never invent evidence.
- Do not initiate real push, device automation, paid CI resources, or private-repository workflows without user authorization. Publishing is separate from building and verification.
