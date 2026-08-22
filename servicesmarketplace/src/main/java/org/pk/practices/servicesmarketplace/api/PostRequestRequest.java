package org.pk.practices.servicesmarketplace.api;

import com.fasterxml.jackson.databind.JsonNode;

public record PostRequestRequest(String categoryId, JsonNode answers, Double lat, Double lng, String desiredTiming) {}
