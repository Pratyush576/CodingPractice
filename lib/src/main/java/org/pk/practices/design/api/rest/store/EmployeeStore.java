package org.pk.practices.design.api.rest.store;

import org.pk.practices.design.api.rest.model.Employee;

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
 * <p>In production this layer would be replaced by a database repository (JPA, JDBC,
 * etc.), but the handler and server code would not need to change — only this class
 * swaps out. That is the point of keeping the store behind its own type.
 *
 * <h2>Thread safety</h2>
 * <ul>
 *   <li>{@link ConcurrentHashMap} allows concurrent reads and fine-grained locking on
 *       writes, so multiple Jetty request threads can hit the store simultaneously without
 *       a global lock.</li>
 *   <li>{@link AtomicLong} for ID generation guarantees each call to {@link #nextId()}
 *       returns a unique value without synchronization overhead.</li>
 * </ul>
 *
 * <h2>Why records are safe here</h2>
 * Because {@link Employee} is an immutable record, once a value is retrieved from the map
 * it cannot be mutated by another thread. Updates always replace the entry with a brand-new
 * record rather than modifying an existing object.
 */
public class EmployeeStore {

    private final Map<String, Employee> store = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(1);

    /**
     * Pre-populates the store with three seed employees so that GET requests return
     * data immediately without requiring any prior POST calls.
     */
    public EmployeeStore() {
        save(new Employee(nextId(), "Alice",  "Engineering", 95000));
        save(new Employee(nextId(), "Bob",    "Marketing",   75000));
        save(new Employee(nextId(), "Carol",  "Product",     85000));
    }

    /**
     * Returns all employees, optionally filtered by department.
     *
     * <p>Results are sorted by ID so the list order is stable across calls — a
     * {@link ConcurrentHashMap} does not guarantee iteration order.
     *
     * @param department if non-null and non-blank, only employees in this department
     *                   are returned (case-insensitive); otherwise all employees are returned
     * @return immutable snapshot of matching employees sorted by ID
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
     * @return an {@link Optional} containing the employee if found, or empty if not
     */
    public Optional<Employee> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    /**
     * Inserts or replaces an employee in the store.
     *
     * <p>This method serves both create (new ID) and update (existing ID) operations —
     * both simply overwrite the map entry, which is safe because {@link Employee} is
     * immutable.
     *
     * @param employee the employee to persist
     * @return the same employee, unchanged (returned for handler chaining convenience)
     */
    public Employee save(Employee employee) {
        store.put(employee.id(), employee);
        return employee;
    }

    /**
     * Removes an employee from the store.
     *
     * @param id the employee's unique identifier
     * @return {@code true} if the employee existed and was removed, {@code false} if
     *         no employee with that ID was found
     */
    public boolean delete(String id) {
        return store.remove(id) != null;
    }

    /**
     * Generates the next unique employee ID.
     *
     * <p>{@link AtomicLong#getAndIncrement()} is a single CPU instruction (CAS) with no
     * lock contention, making ID generation thread-safe and very fast.
     *
     * @return a unique string ID (monotonically increasing integer)
     */
    public String nextId() {
        return String.valueOf(idSequence.getAndIncrement());
    }
}
