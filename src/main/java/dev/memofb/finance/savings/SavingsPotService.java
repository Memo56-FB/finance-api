package dev.memofb.finance.savings;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SavingsPotService {

    private static final BigDecimal MAX_BALANCE = new BigDecimal("99999999999999999.99");
    private static final Duration RESTORE_WINDOW = Duration.ofSeconds(10);

    private final SavingsPotRepository pots;
    private final Clock clock;

    SavingsPotService(SavingsPotRepository pots, Clock clock) {
        this.pots = pots;
        this.clock = clock;
    }

    public SavingsPotResponse create(String username, SavingsPotRequest request) {
        return SavingsPotResponse.from(pots.create(username, request.name(), request.targetAmount(), request.color()));
    }

    public SavingsPotListResponse list(String username) {
        // shortcut: Returns all active pots; add pagination when per-user volume requires it.
        var items = pots.listActive(username).stream().map(SavingsPotResponse::from).toList();
        var total = items.stream().map(SavingsPotResponse::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SavingsPotListResponse(items, total);
    }

    public SavingsPotResponse get(String username, long id) {
        return SavingsPotResponse.from(pots.findActive(username, id).orElseThrow(NotFoundException::new));
    }

    @Transactional
    public SavingsPotResponse update(String username, long id, SavingsPotRequest request) {
        lockActive(username, id);
        return SavingsPotResponse.from(pots.updateDetails(username, id, request.name(), request.targetAmount(), request.color()));
    }

    @Transactional
    public SavingsPotResponse deposit(String username, long id, BigDecimal amount) {
        // shortcut: Each request is a new adjustment; add operation IDs before supporting automatic retries.
        return changeBalance(username, id, amount);
    }

    @Transactional
    public SavingsPotResponse withdraw(String username, long id, BigDecimal amount) {
        return changeBalance(username, id, amount.negate());
    }

    @Transactional
    public SavingsPotDeletionResponse delete(String username, long id) {
        var pot = lockOwned(username, id);
        if (pot.deletedAt() == null) {
            pot = pots.updateDeletedAt(username, id, clock.instant());
        }
        return new SavingsPotDeletionResponse(pot.id(), pot.deletedAt(), pot.deletedAt().plus(RESTORE_WINDOW));
    }

    @Transactional
    public SavingsPotResponse restore(String username, long id) {
        var pot = lockOwned(username, id);
        if (pot.deletedAt() == null) {
            return SavingsPotResponse.from(pot);
        }
        if (!clock.instant().isBefore(pot.deletedAt().plus(RESTORE_WINDOW))) {
            throw new ConflictException("The savings pot restoration period has expired");
        }
        return SavingsPotResponse.from(pots.updateDeletedAt(username, id, null));
    }

    private SavingsPotResponse changeBalance(String username, long id, BigDecimal amount) {
        var pot = lockActive(username, id);
        var balance = pot.balance().add(amount);
        if (balance.signum() < 0) {
            throw new ConflictException("Insufficient savings pot balance");
        }
        if (balance.compareTo(MAX_BALANCE) > 0) {
            throw new ConflictException("The savings pot balance limit would be exceeded");
        }
        return SavingsPotResponse.from(pots.updateBalance(username, id, balance));
    }

    private SavingsPot lockActive(String username, long id) {
        var pot = lockOwned(username, id);
        if (pot.deletedAt() != null) {
            throw new NotFoundException();
        }
        return pot;
    }

    private SavingsPot lockOwned(String username, long id) {
        return pots.lock(username, id).orElseThrow(NotFoundException::new);
    }

    static class NotFoundException extends RuntimeException {
    }

    static class ConflictException extends RuntimeException {
        ConflictException(String detail) {
            super(detail);
        }
    }
}
