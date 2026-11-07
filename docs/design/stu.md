# Students (STU) Design

> Realizes: UC-STU-deactivate-student
> Depends on: FR-SEC-active-account, FR-SEC-deny-unauthorized, BR-student-lifecycle, BR-deactivated-evaluatee, BR-submission-owed, BR-section-scoped-access, BR-role-based-access
> See: [use cases](../requirements/use-cases.md#student), [SRS security FRs](../requirements/software-requirements-specification.md#security-and-authorization-requirements), [architectural-design.md](architectural-design.md#shared-foundation-components) (shared foundation component view, Crosscutting Concepts)
> Designed against `main` at `59f0267` (2026-11-06).

## Overview

The `student` slice holds a student's account. Before this design, deactivating one was the generic `PUT /students/{studentId}` flipping `enabled` from the edit form's Status switch: no warning, no check that the caller is the student's instructor or course admin, and nothing for the rest of the system to act on. A deactivated student also kept her access for the life of her token (up to 2 hours), and her teammates could still submit peer evaluations of her.

This design adds a dedicated deactivation route with its own authorization manager, makes every authenticated request check that the account is still active, and makes `evaluation` refuse evaluations of a deactivated student and drop her from the course section's report for weeks with nothing to show. She stays on her team (BR-student-lifecycle), so nothing in `team` or `activity` changes.

## Components & classes

```mermaid
classDiagram
    direction LR
    class StudentController {
        <<changed, student>>
        +deactivateStudent(studentId) Result
    }
    class StudentService {
        <<changed, student>>
        +deactivateStudent(studentId) Student
        +updateStudent(studentId, update) Student
    }
    class StudentAlreadyDeactivatedException {
        <<new, student>>
    }
    class StudentSecurityService {
        <<changed, student>>
        +isCurrentUserInstructorOfStudentSection(studentId) boolean
        +isCurrentUserCourseAdminOfStudent(studentId) boolean
    }
    class StudentLifecycleAuthorizationManager {
        <<new, security>>
    }
    class ActiveAccountJwtAuthenticationConverter {
        <<new, security>>
    }
    class SecurityConfiguration {
        <<changed, security>>
    }
    class EvaluationService {
        <<changed, evaluation>>
        +addPeerEvaluation(pe) PeerEvaluation
        +updatePeerEvaluation(id, update) PeerEvaluation
        +generateWeeklyPeerEvaluationReportForSection(sectionId, week)
    }
    class StudentRepository {
        <<changed, student>>
        +deactivateIfActive(studentId) int
    }
    class UserRepository {
        <<reused, user>>
    }
    class PeerEvaluationRepository {
        <<reused, evaluation>>
        +findByWeekAndEvaluateeId(week, studentId)
    }
    StudentController --> StudentService
    StudentService --> StudentRepository : conditional update
    StudentService ..> StudentAlreadyDeactivatedException : throws (6b)
    StudentLifecycleAuthorizationManager --> StudentSecurityService
    SecurityConfiguration --> StudentLifecycleAuthorizationManager : POST /students/{id}/deactivation
    SecurityConfiguration --> ActiveAccountJwtAuthenticationConverter : every bearer request
    ActiveAccountJwtAuthenticationConverter --> UserRepository
    EvaluationService --> PeerEvaluationRepository
```

Files: [StudentController](../../backend/src/main/java/team/projectpulse/student/StudentController.java), [StudentService](../../backend/src/main/java/team/projectpulse/student/StudentService.java), [StudentSecurityService](../../backend/src/main/java/team/projectpulse/student/StudentSecurityService.java), [SecurityConfiguration](../../backend/src/main/java/team/projectpulse/security/SecurityConfiguration.java), [EvaluationService](../../backend/src/main/java/team/projectpulse/evaluation/EvaluationService.java), new `security/authorizationmanagers/StudentLifecycleAuthorizationManager.java` and `security/ActiveAccountJwtAuthenticationConverter.java`.

Frontend: `apis/student` gains `deactivateStudent(id)`. The student's details are [EditStudentForm.vue](../../frontend/src/pages/students/EditStudentForm.vue), the dialog [Students.vue](../../frontend/src/pages/students/Students.vue) opens for a row (UC-STU-view-student's realization in `traceability.md`). That dialog gains a Deactivate button, shown only for an enabled student; for a disabled student it shows no Deactivate button, and its Status switch is the reactivation path (the "choice offered" line). For an enabled student the switch is disabled, so it cannot deactivate. [SubmitTeamsEvaluations.vue](../../frontend/src/pages/evaluations/SubmitTeamsEvaluations.vue) leaves out teammates whose `StudentDto.enabled` is false; the DTO already carries `enabled`.

UI behavior the use case leaves to design:
- **Step 4 warning** (`ElMessageBox.confirm`, title "Deactivate <first name> <last name>?"), one line per consequence: she can no longer log in, and if she is logged in now she is signed out on her next action; she stays on her team; her teammates no longer evaluate her; her weekly activity reports and peer evaluations are kept; she can be reactivated later, unlike deleting her. Buttons "Deactivate" and "Cancel".
- **5a**: Cancel, the close button, and Esc all count as cancel. No request is sent, and the details dialog stays open.
- **Step 6**: success message "<first name> <last name> has been deactivated."; the dialog and the list reload.
- **6b**: the shared Axios instance toasts the server's message on a `409`; the dialog then reloads, so it shows her as disabled and offers reactivation.
- **6a**: the shared Axios instance sends a `403` to the forbidden page. Kept: the details dialog is reachable only through UC-STU-view-student, whose route already admits only her course section's instructors, so in practice 6a comes from a stale page or a direct call, and the forbidden page is the app's existing denial message (FR-SEC-deny-unauthorized).

## Sequence

### UC-STU-deactivate-student: deactivate a student

```mermaid
sequenceDiagram
    actor I as Course admin / instructor
    participant UI as EditStudentForm.vue (her details)
    participant SC as SecurityConfiguration
    participant AM as StudentLifecycleAuthorizationManager
    participant C as StudentController
    participant S as StudentService
    I->>UI: 1-3 open her details from Students.vue; Deactivate (shown only if enabled)
    UI->>I: 4 warning: one line per consequence (see Components); Deactivate / Cancel
    Note over UI: 5a Cancel, close or Esc: no request; her details stay open
    I->>UI: 5 Confirm
    UI->>SC: POST /api/v1/students/{studentId}/deactivation
    SC->>AM: authorize(studentId)
    Note over AM: instructor assigned to her course section, or course admin of her course?<br/>6a no, or unknown id: 403, nothing changes
    AM-->>SC: granted
    SC->>C: deactivateStudent(studentId)
    C->>S: deactivateStudent(studentId)
    S->>S: UPDATE enabled = false WHERE id = ? AND enabled = true
    Note over S: 6b no row updated (already deactivated): 409, nothing changes
    S-->>C: Student (team, activities, evaluations untouched)
    C-->>UI: 200 Result + StudentDto
    UI->>I: 6 "<first> <last> has been deactivated."; reload her details and the list
```

### Her next request (FR-SEC-active-account)

```mermaid
sequenceDiagram
    participant SPA as Her browser
    participant RS as Resource server (JWT)
    participant CV as ActiveAccountJwtAuthenticationConverter
    participant UR as UserRepository
    SPA->>RS: any request, Bearer token issued before deactivation
    RS->>RS: verify signature and expiry
    RS->>CV: convert(jwt)
    CV->>UR: findById(userId claim)
    UR-->>CV: user, enabled = false (or no such user)
    CV-->>RS: DisabledException (or BadCredentialsException)
    RS-->>SPA: 401 (CustomBearerTokenAuthenticationEntryPoint)
    SPA->>SPA: existing 401 handler: log out, go to login
```

## API contract

| Endpoint | Caller (who may) | Request | Success | Errors, by extension |
|---|---|---|---|---|
| `POST /api/v1/students/{studentId}/deactivation` | An instructor assigned to the student's course section, or the course admin who owns its course (BR-student-lifecycle, BR-section-scoped-access, BR-role-based-access): `StudentLifecycleAuthorizationManager`; route rule added before the `denyAll()` catch-all | No body | `200`, `Result` with the `StudentDto` (`enabled: false`) | 6a (anyone else, including the student herself): `403`, `Result` envelope, nothing changes. Unknown `studentId`: `403` (fails closed, as the other managers do). 6b: `409`, `StatusCode.CONFLICT`, message "This student is already deactivated." |
| `PUT /api/v1/students/{studentId}` (changed) | Unchanged | `enabled: false` for an enabled student is ignored; `enabled: true` for a disabled student still reactivates her | `200` | Unchanged |
| `POST /api/v1/evaluations`, `PUT /api/v1/evaluations/{evaluationId}` (changed) | Unchanged | Evaluatee deactivated (BR-deactivated-evaluatee) | — | `400`, `StatusCode.INVALID_ARGUMENT`, message "A deactivated student cannot be evaluated.", a stated condition rather than a permission refusal, as BR-team-assignment-required's refusals are |
| Every bearer-authenticated request (changed) | — | Token of a user whose account is now deactivated, student or instructor, or whose `userId` names no user (deleted since the token was issued) | — | `401` |
| `POST /api/v1/users/login` (unchanged) | — | Credentials of a deactivated account | — | `401`, already, through `MyUserPrincipal.isEnabled()`; covered by a new test |

## Key decisions

**Check the account on every request, in the JWT authentication converter.** FR-SEC-active-account denies access "regardless of otherwise-valid credentials", and the token is one. The converter wraps the existing `jwtAuthenticationConverter()` and looks the user up by the `userId` claim. Cost: one primary-key read per request. Rejected: a shorter token lifetime, which narrows the window but keeps it. Rejected: a token denylist, which is new state to store and expire. This is the trunk: it touches every authenticated route, for instructors as well as students.

**She stays on her team; the refusal keys on `enabled`, not on the team.** Rejected: setting `team` to null on deactivation, which would make `addPeerEvaluation`'s team check refuse evaluations of her for free, but would also drop her history from the course section's report, which lists only students on a team, and contradict BR-student-lifecycle.

**The report keeps a deactivated student only for a week in which she received an evaluation** (BR-deactivated-evaluatee). The filter is `team != null && (enabled || evaluations of her that week exist)`, using `PeerEvaluationRepository.findByWeekAndEvaluateeId`. Rejected: a `deactivated_at` column, which would need a migration and a history for a student deactivated, reactivated and deactivated again.

**A dedicated route with its own authorization manager.** Rejected: reusing `PUT /students/{studentId}` under `UserOwnershipAuthorizationManager`, which admits the student herself and refuses a course admin who owns the course but is not assigned to the course section. That refusal contradicts BR-student-lifecycle; the reminder routes already compose "assigned instructor or owning course admin" the same way.

**The edit form can only reactivate.** `updateStudent` may set `enabled` from false to true, never from true to false. Rejected: removing the field, which leaves no way to reactivate until UC-STU-reactivate-student is built. Rejected: leaving it, which keeps a second deactivation path that skips step 4's warning and 6a/6b.

**6b is a `409`, not a silent `200`, decided by one conditional update.** `deactivateStudent` runs `UPDATE … SET enabled = false WHERE id = ? AND enabled = true` and answers `409` when it updates no row, so two instructors confirming at the same moment get one `200` and one `409`. Rejected: an idempotent success, which would tell the second instructor she had just deactivated the student when someone else had. Rejected: read, check, then save, which lets both requests through, since the entity has no `@Version`.

## Data model

No change. Deactivation uses the existing `enabled` column on the user table (SRS Business Domain Model).

## Reuse & cross-cutting

- Authorization: `StudentSecurityService.isCurrentUserInstructorOfStudentSection` (reused); `isCurrentUserCourseAdminOfStudent` mirrors `SectionSecurityService.isSectionOwner`. Like every check in that class, it fails closed on a missing row.
- Error envelope: `StudentAlreadyDeactivatedException` maps to `409` in `ExceptionHandlerAdvice`; `PeerEvaluationIllegalArgumentException` keeps its existing `400` mapping.
- Frontend: the shared Axios instance already logs the user out on `401`, so the session check needs no frontend code. The warning uses Element Plus `ElMessageBox.confirm`.
- BR-submission-owed: `SubmissionStatusService.findOwedItems` already skips a deactivated student and counts only active teammates. Unchanged.

## Tests

| Use case, flow | Level | Asserts |
|---|---|---|
| T1 main, assigned instructor | integration | `200`; she is disabled; her team, her activity count, and the count of evaluations she gave and received are unchanged |
| T2 main, course admin not assigned to the course section | integration | `200` (BR-student-lifecycle, BR-role-based-access) |
| T3 6a, instructor of another course section | integration | `403` with the `Result` envelope; she is still enabled |
| T4 6a, a student (herself, and a teammate) | integration | `403`; she is still enabled |
| T5 6b, already deactivated | integration | `409` with the already-deactivated message; she is still disabled |
| T6 session in progress (FR-SEC-active-account) | integration | Her token issued before deactivation: the next `GET` returns `401`; an active student's token: `200`; a deactivated instructor's token: `401`; a token whose `userId` names a deleted student: `401` |
| T6b login after deactivation (FR-SEC-active-account) | integration | `POST /users/login` with her correct credentials: `401` |
| T5b 6b, two confirmations at once | unit | The second `deactivateStudent` on a student the first already updated throws `StudentAlreadyDeactivatedException` (the conditional update reports no row) |
| T7 BR-deactivated-evaluatee, submit | integration | `POST /evaluations` of a deactivated teammate: `400`; of an active teammate in the same request setup: `200` |
| T8 BR-deactivated-evaluatee, update | unit | Updating an existing evaluation of a now-deactivated teammate is refused |
| T9 BR-deactivated-evaluatee, report | unit | Deactivated, evaluated in week W: listed for W; not evaluated in week W+1: not listed for W+1; an active student with no evaluations: listed |
| T10 edit form cannot deactivate | unit | `updateStudent` with `enabled: false` on an enabled student leaves her enabled; `enabled: true` on a disabled student enables her |
| T11 unknown student | integration | `POST /students/{unknownId}/deactivation`: `403`; nothing changes |
| T12 step 4, 5a, 6, choice offered | E2E (Cypress, API stubbed) | In her details, the warning shows each consequence line from Components; Cancel and Esc send no `POST` and leave her details open; a deactivated student's details show no Deactivate button and an enabled Status switch; Deactivate sends one `POST` and shows "<first> <last> has been deactivated." |
| T12b 6b in the UI | E2E (Cypress, API stubbed) | A `409` toasts "This student is already deactivated." and her details reload as disabled |
| T12c teammates to evaluate | E2E (Cypress, API stubbed) | A teammate with `enabled: false` is not offered on the peer evaluation page |
| BR-submission-owed | existing | `SubmissionStatusServiceTest` already covers a deactivated student; cited, no new test |

## Open questions / risks

- **The converter's exception path.** Settled: a `DisabledException` thrown from the JWT converter reaches `CustomBearerTokenAuthenticationEntryPoint` as a `401` (T6, `ActiveAccountIntegrationTest`).
- **One more database read per request.** Accepted (see Key decisions). Not measured.
- **Reactivation stays on the edit form** until UC-STU-reactivate-student is built; its warning and extensions are that use case's to design. That form saves through `PUT /students/{studentId}` under `UserOwnershipAuthorizationManager`, so a course admin not assigned to the course section can deactivate a student but not reactivate her, which BR-student-lifecycle allows. Left for UC-STU-reactivate-student, which should reuse `StudentLifecycleAuthorizationManager`.
- **A stale token on an open route.** The resource server authenticates a bearer header wherever it is sent, so a deactivated user whose browser still sends her old token to an open route (login, password reset) gets `401`. Accepted: the frontend's `401` handler clears the token and the retry succeeds or fails on its own merits.
- **The section report route** is guarded by `SectionInstructorAuthorizationManager` alone, so a course admin not assigned to the course section is still denied there (recorded in [not.md](not.md)). This design does not change that route.
