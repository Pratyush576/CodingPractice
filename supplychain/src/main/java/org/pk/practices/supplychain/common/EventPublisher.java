package org.pk.practices.supplychain.common;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Takes a connection, not a data source: the caller enlists the outbox write
 * in whatever local transaction is already writing the domain change, so the
 * two either both commit or neither does. See LLD.md §1.3.
 */
public interface EventPublisher {
    void publish(Connection connection, DomainEvent event) throws SQLException;
}
