package org.pk.practices.cabreservation.auth;

public record RegisterDriverRequest(String name, String email, String password, VehicleRequest vehicle) {}
