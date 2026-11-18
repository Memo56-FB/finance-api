# Spec Delta

## Purpose

Track monthly subscriptions and installment commitments, record their expenses automatically or manually, and expose payment progress and monthly summaries.

## ADDED Requirements

### Requirement: Manage personal recurring definitions
The system SHALL support creating, listing, retrieving, updating and deleting recurring payments with `name`, owned `categoryId`, positive per-period `amount`, `firstDueDate` and type `SUBSCRIPTION` or `INSTALLMENT`. Names SHALL be nonblank and at most 100 characters. Installments SHALL require a positive `totalInstallments`; subscriptions SHALL have no installment limit.

#### Scenario: Create both payment types
- **WHEN** a user creates a monthly subscription and a twelve-installment commitment of `50.00` per installment
- **THEN** both belong to that user and expose their first due date, category, type and amount
- **AND** the installment commitment exposes totalInstallments `12`

### Requirement: Use a stable monthly schedule
Each definition SHALL have one due occurrence per month starting in its first due month. If its billing day does not exist in a month, the due date SHALL be that month's last date without changing the original billing-day anchor. Installment definitions SHALL end after their total number of occurrences.

#### Scenario: Billing day 31
- **WHEN** a definition anchored on January 31 reaches February and March
- **THEN** its February due date is February's last date and its March due date is March 31

#### Scenario: Final installment
- **WHEN** a twelve-installment definition completes its twelfth occurrence
- **THEN** no thirteenth occurrence is due and the definition reports its completed progress

### Requirement: Record due payments automatically
When the configured business date reaches an occurrence's due date, the system SHALL automatically record it as paid and create one negative transaction for the occurrence's amount and category. Processing SHALL recover missed due occurrences after downtime without requiring a read request. Global negative balance SHALL NOT prevent recording.

#### Scenario: Due date arrives
- **WHEN** a subscription for `35.00` becomes due
- **THEN** it becomes paid and creates one `-35.00` transaction dated on its due date
- **AND** subsequent financial and budget reads include the expense

#### Scenario: Recover after downtime
- **WHEN** the application resumes after two monthly due dates were missed
- **THEN** it eventually records each missed occurrence once with its own due date

#### Scenario: Recover an expense outside the current reporting cut
- **WHEN** a recovered occurrence creates an expense dated before the user's current financial summary period
- **THEN** it remains visible in history, its due-month paid summary and the budget of its transaction month
- **AND** it does not change the current reporting period's income, expenses or currentBalance

### Requirement: Mark a monthly occurrence paid manually without duplication
Users SHALL be able to mark a valid due-month occurrence as paid manually, including before its due date. Repeating the request or racing with automatic processing SHALL return the same paid result without another expense. A payment and its generated transaction SHALL succeed or fail together.

#### Scenario: Early manual payment
- **WHEN** a user marks next month's occurrence paid today
- **THEN** that occurrence becomes paid and one expense is dated today
- **AND** the later due-date process does not charge it again

#### Scenario: Concurrent payment attempts
- **WHEN** manual requests and automatic processors simultaneously record the same occurrence
- **THEN** exactly one active expense exists for it and all successful attempts identify that payment

### Requirement: Expose machine-readable origin and installment progress
Generated payment transactions SHALL expose `sourceType=RECURRING_PAYMENT`, `recurringPaymentId` and `occurrenceId`. Payment reads SHALL return the base name, actual paid installment count and total installment count separately. Localized labels or progress suffixes SHALL NOT be persisted in the name.

#### Scenario: Display installment progress
- **WHEN** three of twelve valid installment occurrences have active recorded expenses
- **THEN** the response exposes paidInstallments `3` and totalInstallments `12`
- **AND** the frontend can render the base name with `3/12` in either supported language

### Requirement: Preserve editable transaction corrections
Users SHALL be able to edit all business fields of a generated payment transaction. Editing a negative amount SHALL update the recorded paid amount without changing future installments. Deleting the expense or changing it to positive SHALL make that occurrence unpaid and suppress automatic re-recording. Recording an expense again SHALL require explicit user action.

#### Scenario: Correct the paid amount
- **WHEN** a generated `-50.00` transaction is edited to `-45.00` with a different name, category and date
- **THEN** history and financial calculations use those new values
- **AND** that occurrence remains paid for `45.00` while future installments retain their configured amount

#### Scenario: Delete a recorded expense
- **WHEN** a user deletes the expense linked to a paid occurrence
- **THEN** its financial effect is removed and its paid count decreases
- **AND** automatic processing does not recreate it, while an explicit manual payment can record a replacement expense

#### Scenario: Change an expense into income
- **WHEN** a generated negative transaction is edited to a positive amount
- **THEN** it contributes as income and no longer counts as a paid occurrence
- **AND** automatic processing does not insert an offsetting expense

### Requirement: Keep one current expense after repeated corrections
Each occurrence SHALL have at most one active negative transaction. An earlier positive transaction retaining that origin SHALL NOT change a replacement expense's paid status. Changing such a positive transaction back to negative SHALL restore payment only if no other active expense exists; otherwise the system SHALL return a conflict.

#### Scenario: Correct the original record after a replacement payment
- **WHEN** an expense was changed to income, the occurrence was manually paid again, and the earlier income's name or amount is edited while remaining positive
- **THEN** the replacement expense remains the occurrence's paid transaction

#### Scenario: Restore the expense sign
- **WHEN** a user changes that earlier positive transaction back to negative
- **THEN** the operation succeeds and marks the occurrence paid if no replacement expense exists
- **AND** if a replacement expense exists, the operation returns a conflict without changing either transaction

### Requirement: Edit or cancel without rewriting payment history
Editing a recurring definition SHALL affect unpaid future scheduling without rewriting recorded occurrences or their transactions. Once an occurrence exists, its first due month SHALL remain fixed and the installment total SHALL NOT become smaller than the highest recorded sequence. Deleting a definition SHALL cancel future processing while retaining existing payment expenses and paid history.

#### Scenario: Change amount and billing day
- **WHEN** a user changes a definition's amount and billing day after one payment
- **THEN** later unrecorded occurrences use the new settings and the recorded payment retains its original values

#### Scenario: Cancel a subscription
- **WHEN** a user deletes a subscription with previous payments
- **THEN** it leaves the active list and no future payments are generated
- **AND** its existing expenses and historical paid summary remain available

### Requirement: Search and order recurring payments independently
The active payment list SHALL support a case-insensitive literal name search, optional `month=YYYY-MM`, and independent sorts `newest`, `oldest`, `name_asc`, `name_desc`, `amount_desc` and `amount_asc`. Date sorting SHALL use the selected month's due date. Paging SHALL default to page 0, size 20, capped at 100, with deterministic ID ties and total metadata.

#### Scenario: Sort with and without a name filter
- **WHEN** a user requests `name_asc` with a search term or without one
- **THEN** matching active definitions having an occurrence in the selected month are ordered by base name ascending
- **AND** the default selected month is the current business month

### Requirement: Return monthly payment summaries without double counting
The system SHALL return count and totalAmount for `paid`, `pending` and `dueSoon` for a selected month, plus `total`. Paid SHALL reflect active recorded expenses for that due month; pending SHALL include unpaid occurrences of active definitions. DueSoon SHALL be the subset of pending due from today through seven days ahead inclusive. Total SHALL equal paid plus pending, not include dueSoon again.

#### Scenario: Pending and due soon overlap
- **WHEN** a month has five paid occurrences totaling `155.00`, three pending totaling `300.00`, and two of the pending totaling `40.00` due within seven days
- **THEN** paid is `5/155.00`, pending is `3/300.00`, dueSoon is `2/40.00` and total is `8/455.00`
- **AND** these values do not depend on list search, sort or page

#### Scenario: Reporting cuts do not change monthly payment summaries
- **WHEN** a user changes their financial summary settings from monthly to semimonthly or chooses another start day
- **THEN** the recurring payment summary for a given month and its scheduled due dates remain unchanged

#### Scenario: Overdue and corrected occurrences
- **WHEN** an unpaid occurrence is overdue or has automatic processing suppressed after a transaction correction
- **THEN** it remains pending while its definition is active
- **AND** it is dueSoon only when its due date is within the stated upcoming window

### Requirement: Protect payment ownership and existing security
Payment definitions, occurrences, linked categories and generated expenses SHALL be isolated by authenticated user. Unknown or foreign IDs SHALL return not-found responses. User mutations SHALL retain session authentication and CSRF protection; automatic processing SHALL use the owner of each stored definition.

#### Scenario: Attempt to pay a foreign definition
- **WHEN** a user tries to mark another user's recurring payment paid
- **THEN** the request returns not found and no occurrence or transaction is created
