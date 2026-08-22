package org.pk.practices.servicesmarketplace.auth;

public record RegisterProRequest(String businessName, String email, String password, ProProfileRequest profile) {}
