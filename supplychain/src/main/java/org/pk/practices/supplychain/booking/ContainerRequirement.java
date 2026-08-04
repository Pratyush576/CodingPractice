package org.pk.practices.supplychain.booking;

/** DESIGN.md §4.1 — required for FCL bookings; a 40HC request is never satisfied by a 20GP slot. */
public record ContainerRequirement(String containerType, int quantity) {}
