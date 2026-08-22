package org.pk.practices.servicesmarketplace.customer;

import java.util.Optional;

public interface CustomerRepository {
    void insert(Customer customer);
    Optional<Customer> findById(String customerId);
    Optional<Customer> findByEmail(String email);
}
