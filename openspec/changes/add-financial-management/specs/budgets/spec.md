# Spec Delta

## Purpose

Let users configure monthly category spending limits and inspect available amounts and nonblocking warnings derived from their actual expenses.

## ADDED Requirements

### Requirement: Manage monthly category budgets
The system SHALL support creating, listing, retrieving, updating and deleting personal budgets with an active owned category, positive `spendingLimit` and `color` in #RRGGBB format. There SHALL be at most one active budget per category and user. A budget SHALL repeat each calendar month until edited or deleted.

#### Scenario: Create and update a budget
- **WHEN** a user creates a `Groceries` budget with limit `300.00` and color `#aabbcc`
- **THEN** the budget is stored for that user, returns normalized color `#AABBCC` and applies each month
- **AND** its category, limit and color can subsequently be changed

#### Scenario: Duplicate active budget
- **WHEN** a user creates another active budget for the same category
- **THEN** the system returns a conflict without creating the duplicate

### Requirement: Calculate monthly spending and available amount
Budget reads SHALL accept `month=YYYY-MM`, defaulting to the current business month. `spent` SHALL sum absolute negative effective transaction amounts in that category and month. `available` SHALL equal `spendingLimit - spent`, `limitReached` SHALL mean `spent >= spendingLimit`, and `exceededAmount` SHALL equal `max(spent - spendingLimit, 0)`. Positive transactions SHALL NOT consume or replenish the budget.

#### Scenario: Below and exactly at the limit
- **WHEN** a `100.00` budget has expenses of `60.00` in its category and month
- **THEN** spent is `60.00`, available is `40.00`, limitReached is false and exceededAmount is zero
- **AND** after a further `40.00` expense, available is zero and limitReached is true with zero exceededAmount

#### Scenario: Ignore another month and income
- **WHEN** a category contains a `-25.00` expense in the requested month, a `-70.00` expense in another month and `50.00` income
- **THEN** the requested month's spent amount is `25.00`

### Requirement: Warn without blocking overspending
An expense SHALL be accepted when it meets transaction validation even if it exceeds a budget. Interactive transaction creation or update and manual recurring payment recording SHALL return structured budget warnings for affected months at or above the limit, including `budgetId`, `categoryId`, `month`, `limitReached` and `exceededAmount`.

#### Scenario: Expense exceeds the limit
- **WHEN** a user adds a `-30.00` expense to a category with limit `100.00` and existing spending `80.00`
- **THEN** the expense succeeds and a warning reports exceededAmount `10.00`
- **AND** the budget subsequently returns available `-10.00` and limitReached true

#### Scenario: Automatic payment exceeds a budget
- **WHEN** an automatic recurring payment increases monthly spending above the limit
- **THEN** the payment is recorded successfully and subsequent budget reads expose the excess
- **AND** no frontend connection or acknowledgement is required

### Requirement: Derive budget state from current effective movements
Budget calculations SHALL reflect transaction edits, deletions and visibility changes of linked pots. Moving an expense between categories or months SHALL remove its old contribution and apply the new one. Deleting a budget SHALL leave transactions and global financial totals unchanged.

#### Scenario: Reclassify and delete an expense
- **WHEN** a user moves a `-20.00` expense to another category and month, then deletes it
- **THEN** spending first moves to the new category and month and then ceases contributing to any budget

#### Scenario: Hide and restore a categorized pot expense
- **WHEN** a pot containing a categorized withdrawal is deleted and restored within its undo period
- **THEN** that expense is excluded from its budget while hidden and counted once after restoration

### Requirement: Keep monthly boundaries and ownership consistent
Month calculations SHALL use the configured business calendar independently of the server's operating-system timezone. Budget operations SHALL enforce the user's ownership and existing session and CSRF protections. Foreign category or budget IDs SHALL behave as missing resources.

#### Scenario: Calendar boundary
- **WHEN** transactions fall on the last date of one month and first date of the next
- **THEN** each contributes only to its own calendar month regardless of the server timezone

#### Scenario: Another user's budget
- **WHEN** a user retrieves or mutates another user's budget
- **THEN** the system returns a not-found response and both users' budgets remain unchanged
