package org.pk.practices.design.api.graphql.store;

import org.pk.practices.design.api.graphql.model.Employee;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Thread-safe in-memory repository for {@link Employee} records.
 *
 * <p>The same design as the REST package's store: {@link ConcurrentHashMap} for
 * safe concurrent access from Jetty request threads, {@link AtomicLong} for
 * lock-free ID generation. In production this would be replaced by a database
 * repository; the fetchers that depend on it would not change.
 */
public class EmployeeStore {

    private final Map<String, Employee> store = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(1);

    /** Pre-populates three employees so queries return data out of the box. */
    public EmployeeStore() {
        save(new Employee(nextId(), "Alice", "Engineering", 95000));
        save(new Employee(nextId(), "Bob",   "Marketing",   75000));
        save(new Employee(nextId(), "Carol", "Product",     85000));
    }

    /**
     * Returns all employees, optionally filtered by department.
     *
     * @param department case-insensitive filter; {@code null} returns all employees
     * @return stable-sorted list (by ID) of matching employees
     */
    public List<Employee> findAll(String department) {
        Stream<Employee> stream = store.values().stream();
        if (department != null && !department.isBlank()) {
            stream = stream.filter(e -> e.department().equalsIgnoreCase(department));
        }
        return stream.sorted(Comparator.comparing(Employee::id)).toList();
    }

    /**
     * Looks up a single employee by ID.
     *
     * @param id the employee's unique identifier
     * @return an {@link Optional} with the employee, or empty if not found
     */
    public Optional<Employee> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    /**
     * Inserts or replaces an employee. Serves both create (new ID) and update
     * (existing ID) without branching — safe because {@link Employee} is immutable.
     *
     * @param employee the employee to persist
     * @return the same employee (returned for fetcher chaining convenience)
     */
    public Employee save(Employee employee) {
        store.put(employee.id(), employee);
        return employee;
    }

    /**
     * Removes an employee by ID.
     *
     * @param id the employee's unique identifier
     * @return {@code true} if the employee existed and was removed
     */
    public boolean delete(String id) {
        return store.remove(id) != null;
    }

    /**
     * Returns the next unique ID using a lock-free CAS increment.
     *
     * @return a unique monotonically increasing string identifier
     */
    public String nextId() {
        return String.valueOf(idSequence.getAndIncrement());
    }
}
