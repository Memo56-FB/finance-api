# Spec Delta

## Purpose

Give each user configurable calendar reporting cuts and consistent period balance, income and expenses derived from effective transactions, including linked activity exactly once.

## ADDED Requirements

### Requirement: Configure personal reporting cuts
The system SHALL let each authenticated user read and update their reporting preference through GET and PUT of `/api/financial-summary/settings`. Settings SHALL contain `frequency`, either `MONTHLY` or `SEMIMONTHLY`, and integer `startDay` from 1 through 31. A user without a saved preference SHALL receive `MONTHLY` and day 1 without requiring a configuration write.

#### Scenario: Default and customized settings
- **WHEN** a user without settings reads them and then saves `SEMIMONTHLY` with startDay `4`
- **THEN** the first response returns `MONTHLY` and day 1 and subsequent reads return that user's saved selection
- **AND** another user's settings do not change

#### Scenario: Invalid settings
- **WHEN** a user submits another frequency, a missing field or a startDay outside 1 through 31
- **THEN** the system returns a validation error and keeps the previous settings

### Requirement: Resolve monthly calendar cuts
A monthly cycle SHALL start on the chosen day in one month and end immediately before that day's next monthly occurrence. If the day does not exist, that month's anchor SHALL be its last date without changing the chosen day for later months. The current cycle SHALL be the one containing today's business date.

#### Scenario: Monthly cut on day four
- **WHEN** a user selects monthly day 4 and today's business date is January 20, 2027
- **THEN** the current period starts January 4 and ends February 3, 2027, inclusive
- **AND** on February 4 the next period starts without a gap or overlap

#### Scenario: Short month and original anchor
- **WHEN** the selected startDay is 31 and the current cycle starts January 31, 2027
- **THEN** it ends February 27, the next cycle starts February 28 and the following starts March 31
- **AND** in a leap year the February anchor is February 29 instead

### Requirement: Resolve semimonthly calendar cuts
A semimonthly cycle SHALL use the same monthly anchors and contain exactly two reporting periods: the first fifteen calendar days and the remaining dates until the next monthly anchor. The second period's length SHALL adjust to the cycle's calendar length. There SHALL be no extra one-day period, gap or overlap.

#### Scenario: First-day semimonthly cuts
- **WHEN** a user selects semimonthly startDay 1
- **THEN** January's periods are January 1–15 and January 16–31
- **AND** February's are February 1–15 and February 16 through its last date

#### Scenario: Offset semimonthly cuts
- **WHEN** the user selects startDay 4 and today's business date is March 20, 2027
- **THEN** the current period is March 19 through April 3, 2027
- **AND** the preceding period was March 4–18

### Requirement: Derive period financial totals
The summary SHALL return inclusive `periodStart` and `periodEnd`, plus `currentBalance`, `income` and `expenses`, from effective transactions whose transactionDate falls in that current period. Income SHALL sum positive amounts; expenses SHALL sum the absolute value of negative amounts; currentBalance SHALL equal income minus expenses without carrying previous periods. Totals SHALL have no direct update or delete operations.

#### Scenario: New user
- **WHEN** a user whose current period has no effective financial movements requests the summary
- **THEN** `currentBalance`, `income` and `expenses` are all zero
- **AND** the response still identifies the current period's dates

#### Scenario: Signed manual movements
- **WHEN** a user has effective manual movements of `100.00`, `25.00` and `-40.00` in the current period
- **THEN** the summary returns income `125.00`, expenses `40.00` and current balance `85.00`

#### Scenario: Boundaries and no carry-forward
- **WHEN** a monthly day-4 period contains `100.00` on January 4 and `-20.00` on February 3, with other movements on January 3 and February 4
- **THEN** that period returns income `100.00`, expenses `20.00` and currentBalance `80.00`
- **AND** the two outside dates do not contribute, and the next period does not carry forward the `80.00`

### Requirement: Recalculate the view without resetting financial records
Updating the preference SHALL recalculate which existing transactions belong to the current period. It SHALL NOT create, edit or delete financial movements, reset pot balances, or change monthly budget calculations and recurring due dates. Changing frequency or startDay SHALL NOT require a background reset at a cut boundary.

#### Scenario: Change the cut
- **WHEN** a user changes monthly day 1 to semimonthly day 4
- **THEN** the next summary uses the new current period and returns its dates and recalculated totals
- **AND** every stored transaction, pot balance, budget rule and recurring schedule remains unchanged

### Requirement: Allow negative global balance
Financial records SHALL be accepted independently of available global balance. A negative `currentBalance` SHALL be valid and SHALL NOT block manual expenses or recurring payment recording. This SHALL NOT change the existing nonnegative balance rule for an individual savings pot.

#### Scenario: Optional income tracking
- **WHEN** a user records an expense of `-60.00` in the current period without recording income in that period
- **THEN** the operation succeeds and the summary returns income zero, expenses `60.00` and current balance `-60.00`

### Requirement: Count linked activity exactly once
The period summary SHALL include each effective transaction dated within that period once, regardless of whether it was manual or generated from a pot or recurring payment. Pot balances and payment amounts SHALL NOT be added separately. A deleted transaction or a transaction linked to a deleted pot SHALL be excluded.

#### Scenario: Pot contribution
- **WHEN** a user deposits `100.00` into a pot and withdraws `20.00` within the current period, with no other activity
- **THEN** income is `100.00`, expenses are `20.00` and current balance is `80.00`
- **AND** the stored pot balance is not added a second time

#### Scenario: Pot deletion removes its contribution
- **WHEN** that pot is deleted
- **THEN** its linked income and expense contributions are excluded and all three totals return to zero
- **AND** no new expense is created

#### Scenario: A pot retains money from a previous cut
- **WHEN** a pot received `100.00` in a previous period and has only a `-20.00` withdrawal in the current period
- **THEN** its stored balance remains `80.00`, while the current summary returns income zero, expenses `20.00` and currentBalance `-20.00`
- **AND** changing cuts does not move money or reset the pot

#### Scenario: Payment recording
- **WHEN** a recurring payment for `30.00` produces an expense dated within the current reporting period
- **THEN** expenses increase by exactly `30.00` and current balance decreases by exactly `30.00`

### Requirement: Reflect corrections consistently and privately
Subsequent summaries SHALL reflect mutations according to each transaction's current date and visibility. One response SHALL use a consistent preference, business date and financial state, with exact decimal arithmetic. Users SHALL see only their own totals. Preference mutations SHALL use the existing session and CSRF protections.

#### Scenario: Delete an expense
- **WHEN** a user deletes an effective transaction of `-10.25` dated inside the current period
- **THEN** their expenses decrease by `10.25` and their current balance increases by `10.25`
- **AND** another user's summary does not change

#### Scenario: Concurrent mutation
- **WHEN** a summary request overlaps a financial mutation or preference update
- **THEN** its dates and totals describe one complete state before or after that mutation, preserving `currentBalance = income - expenses`

#### Scenario: Preference mutation without CSRF
- **WHEN** an authenticated user submits a preference update without a valid CSRF token
- **THEN** the request is forbidden and the saved preference remains unchanged
