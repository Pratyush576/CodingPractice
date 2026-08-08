package org.pk.practices.cabreservation.auth;

public record VehicleRequest(String plate, String make, String model, String productType, String carIcon) {}
