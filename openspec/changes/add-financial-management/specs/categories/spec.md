# Spec Delta

## Purpose

Provide each user with a private category catalog for classifying transactions, budgets and recurring payments without losing historical references.

## ADDED Requirements

### Requirement: Manage personal categories
The system SHALL support creating, listing, retrieving, renaming and deleting categories owned by the authenticated user. Each category SHALL expose an immutable ID and a nonblank name of at most 100 characters. There SHALL be no business limit on the number of categories.

#### Scenario: Independent catalogs
- **WHEN** a user creates a category named `Groceries`
- **THEN** the category receives an ID and appears only in that user's catalog
- **AND** another user can independently create a category with the same name

#### Scenario: Invalid category name
- **WHEN** a category is submitted with a blank name or a name longer than 100 characters
- **THEN** the system returns a validation error without changing the catalog

### Requirement: Preserve historical category references
Deleting a category SHALL remove it from active selection without removing or reclassifying existing transactions. Deleting a category referenced by an active budget or recurring payment SHALL return a conflict. Previously assigned inactive categories SHALL remain readable in transaction history.

#### Scenario: Delete a category used only in history
- **WHEN** a user deletes a category referenced by past transactions but no active budget or recurring payment
- **THEN** the category disappears from the active catalog
- **AND** those transactions retain their category ID, readable name and financial effect

#### Scenario: Category still has an active dependent record
- **WHEN** a user deletes a category assigned to an active budget or recurring payment
- **THEN** the system returns a conflict and preserves the category

#### Scenario: Reuse an inactive category
- **WHEN** a user creates a record with an inactive category or assigns it as a different category
- **THEN** the system rejects the assignment
- **AND** editing another field of a historical transaction can retain its existing inactive category

### Requirement: Isolate category ownership and protect mutations
Category operations SHALL use the authenticated user's identity rather than a caller-supplied owner. Another user's category SHALL behave as a missing resource. Mutations SHALL require the existing session and CSRF protection.

#### Scenario: Access another user's category
- **WHEN** a user retrieves, edits, deletes or assigns another user's category by ID
- **THEN** the system returns a not-found response without exposing its name or owner

#### Scenario: Mutation without CSRF
- **WHEN** an authenticated user submits a category mutation without a valid CSRF token
- **THEN** the request is forbidden and the catalog remains unchanged
