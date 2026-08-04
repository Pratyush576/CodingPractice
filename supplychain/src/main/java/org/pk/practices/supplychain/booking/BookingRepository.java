package org.pk.practices.supplychain.booking;

import org.pk.practices.supplychain.common.DomainEvent;

import java.util.List;
import java.util.Optional;

/** LLD.md §2 Booking Service — "Depends on: BookingRepository, EventPublisher." */
public interface BookingRepository {

    /** No CAS needed — this is the first row for the aggregate. No event published either; submit() is what fires BookingSubmitted. */
    void insertDraft(Booking booking);

    /** Not tenant-scoped — bookingId (UUID) is already globally unique; an Operator looks up any booking regardless of tenant. */
    Optional<Booking> find(String bookingId);

    /**
     * @param statusFilter nullable — null means every status.
     * @param shipperIdFilter nullable — null means every shipper across every tenant (an
     *                        Operator's view, tenant-agnostic by design); non-null scopes to
     *                        one Shipper's own bookings. Newest first.
     */
    List<Booking> findAll(BookingStatus statusFilter, String shipperIdFilter);

    /**
     * Persists {@code updated} in place of {@code previous}, guarded by
     * {@code previous.version()} equality (optimistic concurrency), and
     * publishes {@code event} in the same transaction if non-null.
     *
     * @return false if the CAS lost the race — the caller turns this into a {@link org.pk.practices.supplychain.common.ConflictException}.
     */
    boolean save(Booking previous, Booking updated, DomainEvent event);
}
