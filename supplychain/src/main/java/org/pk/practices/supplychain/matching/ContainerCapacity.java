package org.pk.practices.supplychain.matching;

/** One container-type pool within a CapacityOffering — e.g. "40HC: 3 of 5 still available." */
public record ContainerCapacity(String containerType, int totalQuantity, int availableQuantity) {
    public ContainerCapacity withAvailableQuantity(int newAvailableQuantity) {
        return new ContainerCapacity(containerType, totalQuantity, newAvailableQuantity);
    }
}
