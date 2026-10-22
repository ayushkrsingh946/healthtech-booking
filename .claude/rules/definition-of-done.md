# Definition of done

Work through a task on your own, up to an open PR with green CI. Stop and
ask only at the points listed under "Ask first". Merging is always the
user's call.

## Decide yourself, and say so in the PR

- How to implement the change, how to structure the tests, and small
  refactors inside the code you are already changing.
- Wording of comments, README sections, ADR corrections and OpenAPI
  descriptions that the change makes necessary.
- Fixes for CI failures and review comments on your own PR.

When a choice was not obvious (two reasonable designs, a behavior the issue
didn't specify, e.g. "a slot starting exactly now is accepted"), make it and
name it in the PR Summary so the reviewer can overturn it.

## Ask first

Stop and ask before any of these, even when the task seems to require it:

- **API contract**: a new or removed endpoint, or a changed request or
  response shape, status code or header that the frontend, the scripts or
  other services rely on.
- **Schema and events**: changes to any `schema-postgresql.sql`, or to a
  Kafka topic or event payload.
- **Security**: `SecurityConfig`, JWT issuing or validation, role and
  ownership checks, gateway auth or CORS.
- **Dependencies**: a new library in a `pom.xml` or `package.json`.
- **Business rules**: a plan that contradicts `docs/domain.md`, and any edit
  to that document. See "Business rules in docs/domain.md" below.

Also ask when the task can't be done without breaking a rule in CLAUDE.md
or an ADR.

When the user approves a design note (the `design-review` skill), that
approval covers the "Ask first" items and any change to a CLAUDE.md rule,
an ADR or `docs/domain.md` that the note describes. Anything the note
doesn't describe still needs asking.

Note bugs you find outside the task in the PR description. Don't fix
them in the same PR.

## Design first, when there is no spec

For a task without an approved spec that adds a new endpoint or a new
event (even within one service), or behavior spanning services, write a
design note and get it through `design-reviewer` before implementing.
Bug fixes and other changes contained in one service skip this. The
`design-review` skill has the template, the steps, and what counts as an
approved spec.

## Business rules in docs/domain.md

`docs/domain.md` is the record of the business rules. Section 3.1 lists the
rules the code enforces, 3.2 the confirmed rules it doesn't enforce yet, and
section 5 what is deliberate for now or undecided.

- **Before implementing** a change to behavior (a rule, the appointment
  lifecycle, a use case), read the rules it touches, in 3.1, 3.2 and 5.
- **If your plan contradicts it, stop and ask.** That means the plan changes
  or drops a rule in 3.1, implements a 3.2 rule differently from what it
  says, or settles an open decision in section 5. Name the `BR-` id, what
  the document says, what you would do instead, and why. Don't pick a side
  yourself, and don't implement the contradicting plan while you wait.
- **Don't edit `docs/domain.md` on your own.** Change it only after the
  user approves the exact change, or when an approved spec (as the
  `design-review` skill defines it) describes it. That includes moving a
  rule from 3.2 to 3.1 once it is implemented: if the spec names the rule,
  do it; otherwise propose the wording and ask.
- A change that follows the document and leaves it correct needs no edit.

## Done means all of these hold

1. **Every behavior and path is tested.** Each new or changed behavior
   has unit tests for its happy path and for each failure path:
   - validation errors, including the values on both sides of each limit
   - not found, conflict and invalid state
   - 401, 403 and ownership violations
   - failures of a dependency it calls

   Add tests at every other layer the change reaches; the layer for each
   kind of change is in `.claude/rules/tests.md`. A bug is reproduced by a
   test first; a guard is shown to fail without it. The frontend has no
   test setup: say in the PR how a frontend change was checked by hand.
   Adding a test runner is a new dependency: ask first.
2. **Every affected suite passes**, integration tests included. The cloud
   session hook provides Docker and keys. If a layer still can't run, the
   reason must come from the environment, and the PR says so.
3. **The change has been reviewed by someone other than its author**,
   through the `verify` skill. It sets the review depth, verifies
   findings, and says when review stops and when to hand back.
4. **Endpoint changes are checked on the live stack**
   (`docker compose up -d --build`, then `endpoint-tester` or the scripts),
   not only in tests.
5. **Everything that describes the behavior matches it.** That covers
   OpenAPI annotations, README, ADRs, `scripts/test-flow.sh`,
   `scripts/gateway-security-smoke-test.sh` and the frontend when it calls
   the changed endpoint. For `docs/domain.md`, either it already matches
   or its update was approved as described under "Business rules in
   docs/domain.md"; the PR says which `BR-` ids the change touches.
6. **Duplicated code is changed in every copy** CLAUDE.md lists, and in
   the outbox tests, which are adapted per service.
7. **You have also re-read the full diff yourself.** This is a hygiene
   pass, not the review: no debug output, dead code, unused imports or
   changes the task didn't need.
8. **The PR is open and green.** It has a `type(scope): subject` title and
   a body with `## Summary`, `## Testing`, and `Closes #N` when there is
   an issue. Testing lists what ran, with test counts, and what didn't
   run, and why. CI is green on the latest commit and every review thread
   has a reply or a fix. A CI failure your change didn't cause gets one
   re-run; if it fails again, hand back with the failing check.

Until all eight hold, the task isn't done. Don't report it as done, and
don't hand it back unless an "Ask first" point blocks it or a stop rule in
the `design-review` or `verify` skill applies.
