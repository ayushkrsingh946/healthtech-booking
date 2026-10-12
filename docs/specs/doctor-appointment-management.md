# Design: Doctor appointment management, availability and vacations

Status: draft

## Task
Issue #101 ("Doctor appointment management, availability editing and separate doctor/patient UIs"), and the user's request:

> Doctor should be able to manage appointments, therefore appointments booked by patients need to be set in status pending before doctor confirms them (for seeded doctors they should be confirmed automatically).
> Doctor should be able to confirm appointment, cancel it or change it. In all cases a notification is sent to the patient (notification service). Doctors should also be able to manage their availability and enter vacations.
> Implementation should be on two PRs, one for frontend and one for backend.

Clarified with the user: a doctor's change applies directly (no patient acceptance, status unchanged); changing hours or entering a vacation that covers booked appointments is rejected with 409; the 48-hour notice rule does not apply to the doctor; vacations are date ranges (inclusive, whole days).

## Approach
**Booking status.** `bookAppointment` stores `PENDING`, or `CONFIRMED` when the doctor's read-model row has `autoConfirmBookings = true`. `doctor.registered` gets a field `autoConfirmBookings`: `true` from `DoctorSeeder`, `false` from registration. `appointment.booked` is published as today in both cases. `PENDING` holds the slot (the partial unique index only excludes `CANCELLED`). Patient cancel and change work on `PENDING` and `CONFIRMED` as today; a patient change never changes the status (decision, overturnable).

**Doctor appointment actions (appointment-service).** New `DoctorAppointmentController` under `/api/appointments/doctor`, role `DOCTOR`; the doctor id is the token subject and must equal `appointment.doctorId` (else 403 `AppointmentAccessDeniedException`). All use `findByIdForUpdate` like cancel/update.
- `GET /api/appointments/doctor?from=&to=&status=` (all optional; `from`/`to` are dates, inclusive; `status` repeatable) -> `DoctorAppointmentResponse` (appointment fields + `patientName`, `patientEmail` from the `ValidPatient` read model), ordered by date.
- `PUT /api/appointments/doctor/{id}/confirm`: `PENDING` -> `CONFIRMED`, publishes `appointment.confirmed`. Already `CONFIRMED`: no-op, no event. `CANCELLED`: 409 `Appointment Not Confirmable`.
- `PUT /api/appointments/doctor/{id}/cancel`: same as the patient cancel (idempotent, 200) but no ownership-by-patient check; publishes `appointment.cancelled` with `cancelledBy = DOCTOR`. Any time before or after the start, no notice rule.
- `PATCH /api/appointments/doctor/{id}`: body `AppointmentUpdateRequest` (`type?`, `dateTime?`, `notes?`, as for the patient). `CANCELLED` -> 409 `Appointment Not Changeable`. No notice rule. A new time must pass every `BookingRule`, including the new vacation rule. Status unchanged. Publishes `appointment.changed` with `changedBy = DOCTOR` when type or time differs.
The patient and doctor update paths share one private method in `AppointmentService` (apply fields, run rules, `saveAndFlush`, event); they differ in the ownership check, the notice check and `changedBy`.

**Availability and vacations (appointment-service owns the write).** The conflict check ("reject if booked appointments fall outside the new hours or inside a vacation") needs appointment data, which only appointment-service has; a synchronous call to it from doctor-service would reintroduce the coupling ADR-005 rejects, and an asynchronous check cannot reject. So scheduling data moves to appointment-service as the source of truth: `ValidDoctor.openingHours` (today projected once from `doctor.registered`) becomes editable, and a new table `doctor_vacation (id, doctor_id, start_date, end_date)` is added.
- `GET /api/appointments/doctor/availability` -> `{openingHours, vacations}`.
- `PUT /api/appointments/doctor/availability/opening-hours`: replaces the weekly blocks. Validation as at registration (end after start, no overlapping blocks on a day; the same rules as `ValidOpeningHours` in doctor-service, duplicated). If any non-cancelled appointment starting now or later falls outside the new blocks -> 409 `Availability Conflict` with the conflicting appointment ids/times in the ProblemDetail. Else saves and publishes `doctor.availability.updated` carrying the full new hours.
- `POST /api/appointments/doctor/availability/vacations` (`{startDate, endDate}`, `startDate <= endDate`, `endDate` not in the past) -> 201; 409 `Availability Conflict` if a non-cancelled appointment lies in the range; overlapping or adjacent vacations are allowed (stored as given). `DELETE .../vacations/{id}` -> 204 (own vacations only, else 403/404).
- A new `NotOnVacationRule` (`BookingRule`, `@Order(4)`) rejects a slot on a vacation day with 400 `Slot On Vacation`; `AvailabilityService` returns no slots on vacation days. Vacations are not published (only appointment-service uses them).
- doctor-service keeps its `Doctor.openingHours` (shown on the doctor profile and search) in sync by consuming `doctor.availability.updated` (new consumer; replaces the stored set). The doctor-service's own registration/seeding still sets the initial hours and publishes them in `doctor.registered`; after that the appointment-service copy is the source of truth, and `doctor.registered` for an existing id must not overwrite later edits (the consumer, which today upserts, ignores a row that already exists).

**Notifications (notification-service).** New event `appointment.confirmed` (`AppointmentConfirmed {eventId, appointmentId, patientId, doctorId, dateTime, confirmedAt}`) and `NotificationType.APPOINTMENT_CONFIRMED` ("Appointment confirmed for <dateTime>"). `AppointmentCancelled` and `AppointmentChanged` get an optional `cancelledBy` / `changedBy` (`PATIENT` | `DOCTOR`; absent = `PATIENT`, so old messages stay valid). When it is `DOCTOR` the message ends with " by your doctor" (e.g. "Appointment cancelled for <dateTime> by your doctor"). The existing listener/factory/`record` mechanism is reused.

**Frontend (second PR).** Role-specific shell: `Layout` shows patient links only for a patient session and doctor links only for a doctor session; separate landing routes (`/` search for patients, `/doctor/appointments` for doctors); `RequireAuth` per role. Doctor pages: appointments (filter by date range/status, Confirm / Cancel / Change per row, reusing `ChangeAppointmentForm` and `SlotPicker`), availability (weekly hours editor, vacations list/add/remove; the 409 conflict list is shown). The patient list shows the status `PENDING` as "Awaiting confirmation".

## Alternatives considered
- doctor-service owns availability and calls appointment-service to check conflicts: sync coupling, against ADR-005/ADR-002.
- doctor-service owns it and appointment-service just keeps conflicting appointments after the change: contradicts the user's "reject" choice.
- Auto-cancel conflicting appointments: destructive, rejected by the user.
- Doctor change puts the appointment back to a patient-acceptance state: rejected by the user.
- A separate `appointment.rejected` event for a doctor declining a `PENDING` appointment: it is a cancel; `cancelledBy` carries the difference.
- A new `doctor.availability.updated` carrying vacations too: nothing outside appointment-service needs them.
- Doctor endpoints in the existing `AppointmentController`: its class-level security is patient-only; a separate controller and path prefix keep the role rule a single `requestMatchers` line.

## Fits the codebase
- Role enforcement: `SecurityConfig` in appointment-service gets `requestMatchers("/api/appointments/doctor/**").hasRole("DOCTOR")` before `anyRequest().hasRole("PATIENT")`; ownership checks in the service like BR-09/BR-10. The gateway routes `/api/appointments/**` and allows PUT/PATCH/DELETE already; no gateway change.
- Events through `DomainEventPublisher` inside the `@Transactional` method (outbox, ADR-008); new topics are auto-created.
- Locking/concurrency: `findByIdForUpdate` (3s cap, 503 `Appointment Busy`), `saveAndFlush` + `ux_active_appointment`.
- Read model (ADR-005): `ValidDoctor` stays the local copy; it gains `autoConfirmBookings`; the vacation table is plain appointment-service data.
- Errors: new exceptions + handlers in `GlobalExceptionHandler` (RFC 9457). Time from the injected `Clock`.
- Duplicated copies (CLAUDE.md): outbox, `GlobalExceptionHandler`, `SecurityConfig` etc. are not changed in structure; doctor-service gains a Kafka consumer config (new, no existing copy in doctor-service; modelled on patient/appointment consumer configs).
- Schema: appointment-service `schema-postgresql.sql` gets nothing mandatory (Hibernate `update` creates the new table and the `auto_confirm_bookings` column; existing rows get `false`, which is wrong for already-seeded doctors until the seeder's next `doctor.registered`; handled by the seeder re-publishing on startup for existing seeded doctors, see Failure modes). A unique-ish guard against duplicate vacation rows is not added.
- Docs in the same PRs: README (endpoints, events, rules, Phase notes), ADR-005 (read model is now writable for scheduling data), a new ADR-010 "Scheduling data is owned by appointment-service", ADR-008 publishing paths list, `docs/domain.md` rules (provided to the user after implementation, since the file is not in main yet), OpenAPI annotations, `scripts/test-flow.sh`, `scripts/gateway-security-smoke-test.sh`.

## Hard-to-reverse decisions
- **Ownership of opening hours moves to appointment-service** (justified above). ADR planned: yes, ADR-010, and ADR-005 amended.
- **Event payloads:** `doctor.registered` + `autoConfirmBookings`; new `doctor.availability.updated {eventId, doctorId, openingHours, updatedAt}`; new `appointment.confirmed`; `cancelledBy` / `changedBy` optional additions. All additive; consumers ignore unknown fields (the deserializers ignore type headers; unknown properties are checked in implementation). ADR planned: ADR-010 covers the availability event; the rest follow ADR-008's pattern.
- **API shapes** (paths, `PUT` for state transitions as `PUT /{id}/cancel` today, `PATCH` for edits). ADR planned: no (endpoints, not architecture).
- **Status machine:** `PENDING -> CONFIRMED`, `PENDING|CONFIRMED -> CANCELLED`; nothing else. No expiry of old `PENDING` rows. ADR planned: no; the rules go into `docs/domain.md`.

## Contracts
- appointment-service new endpoints, all `DOCTOR` role (401 without token, 403 for a patient token or another doctor's resource, 503 `Appointment Busy` on lock timeout for the three appointment actions):
  - `GET /api/appointments/doctor` (200; 400 on bad dates/`from > to`)
  - `PUT /api/appointments/doctor/{id}/confirm` (200; 404; 409 `Appointment Not Confirmable`)
  - `PUT /api/appointments/doctor/{id}/cancel` (200; 404)
  - `PATCH /api/appointments/doctor/{id}` (200; 400 validation/unaligned/outside hours/past/`Slot On Vacation`; 404; 409 slot taken or cancelled)
  - `GET /api/appointments/doctor/availability` (200)
  - `PUT /api/appointments/doctor/availability/opening-hours` (200; 400; 409 `Availability Conflict`)
  - `POST /api/appointments/doctor/availability/vacations` (201; 400; 409 `Availability Conflict`); `DELETE .../vacations/{id}` (204; 404)
- Changed: `AppointmentResponse.status` can now be `PENDING` after booking (the field exists; the value is new); `POST /api/appointments` response status for non-auto-confirm doctors; `GET /api/availability` returns no slots on vacation days.
- Events: see Hard-to-reverse. Topics `appointment.confirmed`, `doctor.availability.updated` are new.
- Schema: appointment-service new table `doctor_vacation`, new column `valid_doctor.auto_confirm_bookings` (Hibernate `update`); notification-service none (the type check constraint is already dropped by its startup script).
- Dependencies: none (spring-kafka already in doctor-service).

## Failure modes
- Kafka down/lag: events go through the outbox; a delayed `doctor.availability.updated` only delays doctor-service's profile copy, never the scheduling decision. A delayed `appointment.confirmed` delays the notification only.
- Duplicate delivery: notification `event_id` unique index (ADR-009) covers the new event; `doctor.availability.updated` is an idempotent replace of the hours; out-of-order delivery of two availability events could leave the older hours in doctor-service's profile copy: the event carries `updatedAt` and the consumer ignores an event older than the last applied one (stores `hours_updated_at`).
- Concurrency: doctor confirm/cancel/change race with patient cancel/change on the same row: serialized by `findByIdForUpdate`; confirm after cancel -> 409. A new booking racing a vacation/hours change: the availability write locks the `ValidDoctor` row (`PESSIMISTIC_WRITE`) and `bookAppointment` / `updateAppointment` read it with a shared lock (`PESSIMISTIC_READ`) when running the rules, so a booking either commits before the conflict check sees it or sees the new hours/vacation. Booking and change of a time take the doctor row lock only when a doctor lookup is needed (already the case).
- Doctor missing from the read model: 404 `Doctor Not Found`, as today.
- Existing databases: seeded doctors already exist, so their `auto_confirm_bookings` would be `false`. `DoctorSeeder` re-publishes `doctor.registered` for seeded doctors that already exist, on every start; `DoctorEventConsumer` for an existing row updates only `autoConfirmBookings`, names (not hours).
- Partial failure of the availability write: DB save and outbox row are one transaction; a rejected change publishes nothing.
- Appointments already in the past are ignored by the conflict check (only `dateTime >= now`).
- A time change to the appointment's own current time: unchanged (no event).

## Test plan
- `AppointmentServiceTest`: book -> `PENDING` for a normal doctor and `CONFIRMED` for `autoConfirmBookings`; event still published in both cases.
- Doctor actions (unit, fixed `Clock`): confirm happy, confirm already-confirmed no-op (no event), confirm cancelled 409, not found, other doctor's appointment 403 (all three actions); cancel happy with `cancelledBy = DOCTOR`, cancel idempotent, cancel 1 minute before start allowed (no notice rule); change: type only, time only, both, notes only (no event), cancelled 409, slot taken 409, past 400, unaligned, outside hours, on vacation, same time = unchanged, doctor missing 404; patient change/cancel of a `PENDING` appointment still work and keep the status; `changedBy` is `PATIENT` for patient changes.
- Availability (unit): opening-hours replace happy; end <= start; overlapping blocks; empty set allowed? (decision: allowed, doctor then offers no slots); conflict with a booked future appointment -> 409 listing it; a cancelled or past appointment outside hours does not conflict; boundary: appointment ending exactly at new closing time does not conflict, one slot beyond does. Vacations: add happy; `start > end` 400; end in the past 400; start = end accepted; conflict 409 for appointment on first day, last day, and not for the day before/after; delete own, delete other doctor's 403, unknown 404; `NotOnVacationRule` on first day, last day, day before/after; `AvailabilityService` no slots on a vacation day.
- Slice tests (`@WebMvcTest`, modelled on the existing update/cancel slices): each new endpoint's validation, 401, 403 for patient token, error-response ProblemDetail titles; patient token on a doctor path 403, doctor token on a patient path 403.
- Testcontainers: doctor confirm/cancel/change round-trips and frees/keeps slots; concurrent doctor change vs patient cancel -> final state `CANCELLED`; concurrent booking vs vacation creation: exactly one of "booking exists and vacation 409" or "vacation exists and booking 400"; `ValidDoctor` upgrade path (existing row without the new column gets `false`); `OutboxAtomicityIntegrationTest` cases for confirm, doctor cancel, availability update (row on success, none on 409).
- Kafka: `DoctorEventConsumer` on `doctor.registered` for an existing row keeps edited hours and updates the flag; doctor-service consumer applies `doctor.availability.updated` and ignores an older `updatedAt` (Testcontainers with a broker); notification-service: `AppointmentEventConsumerTest` + `NotificationServiceTest` for confirmed, and the " by your doctor" suffix for cancelled/changed with `DOCTOR` and none for absent/`PATIENT`; `NotificationConsumerIntegrationTest` for `appointment.confirmed`.
- `DoctorSeeder` test: seeded doctors publish `autoConfirmBookings = true`; registration publishes `false`.
- `OpenApiDocsTest`: new endpoints documented with problem bodies. Gateway: no code change; `gateway-security-smoke-test.sh` gets doctor-path cases (patient token 403, no token 401).
- Scripts: `test-flow.sh` gets book (pending/confirmed), confirm, doctor change, doctor cancel, vacation and conflict steps. Frontend has no test runner; checked by hand in the browser (patient and doctor sessions, link visibility, conflict display) and the PR says so.

## Ask first
- API contract: all new endpoints above and the changed behavior of `POST /api/appointments` (status `PENDING`) and `GET /api/availability` (vacation days).
- Schema and events: new table `doctor_vacation`, new column `valid_doctor.auto_confirm_bookings`; new topics `appointment.confirmed`, `doctor.availability.updated`; additive fields on `doctor.registered`, `appointment.cancelled`, `appointment.changed`.
- Security: new `DOCTOR` rule in appointment-service `SecurityConfig`; doctor ownership checks.
- ADR change: new ADR-010 and an amendment of ADR-005 (opening hours stop being a write-once projection; appointment-service becomes the owner of scheduling data). This is a departure from ADR-005's description of the read model and is the main thing to approve.
- Decisions I made that the user may overturn: patient changes never alter the status; `PENDING` appointments never expire; an empty set of opening hours is allowed; the doctor may cancel/change past-start appointments of today.
