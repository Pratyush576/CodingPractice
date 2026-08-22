package org.pk.practices.servicesmarketplace.auth;

public record ProProfileRequest(String categoryId, Double lat, Double lng, Double radiusKm, Double startingPrice) {}
