package org.pk.practices.cabreservation.api;

import org.pk.practices.cabreservation.driver.Vehicle;

/**
 * The cab details a rider needs to spot their ride — plate/make/model/product
 * type, nothing about the underlying vehicle_id. {@code carIcon} is the
 * driver-chosen color key (e.g. "BLUE") the rider's map uses to render that
 * driver's marker as a car icon instead of a generic pin.
 */
public record VehicleInfo(String plate, String make, String model, String productType, String carIcon) {
    public static VehicleInfo of(Vehicle vehicle) {
        return new VehicleInfo(vehicle.plate(), vehicle.make(), vehicle.model(), vehicle.productType(), vehicle.carIcon().name());
    }
}
