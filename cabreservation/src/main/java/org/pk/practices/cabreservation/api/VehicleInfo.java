package org.pk.practices.cabreservation.api;

import org.pk.practices.cabreservation.driver.Vehicle;

/** The cab details a rider needs to spot their ride — plate/make/model/product type, nothing about the underlying vehicle_id. */
public record VehicleInfo(String plate, String make, String model, String productType) {
    public static VehicleInfo of(Vehicle vehicle) {
        return new VehicleInfo(vehicle.plate(), vehicle.make(), vehicle.model(), vehicle.productType());
    }
}
