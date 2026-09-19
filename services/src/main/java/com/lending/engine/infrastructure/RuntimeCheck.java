/**
 * Checks the actual shaded artifact's JDBC service registrations without connecting to or modifying
 * databases.
 */
package com.lending.engine.infrastructure;

import java.sql.DriverManager;

/**
 * Checks the actual shaded artifact's JDBC service registrations without connecting to or modifying
 * databases.
 */
public final class RuntimeCheck {
    /** Prevents instantiation of this utility-only type. */
    private RuntimeCheck() {}
    /**
     * Verifies that the packaged application can load its required JDBC drivers without opening a
     * database.
     */
    public static void main(String[] args)throws Exception {
        if(!DriverManager.getDriver("jdbc:h2:mem:packaging-check").getClass().getName().equals("org.h2.Driver"))throw new IllegalStateException("H2 driver missing from package");
        if(!DriverManager.getDriver("jdbc:postgresql://127.0.0.1/packaging-check").getClass().getName().equals("org.postgresql.Driver"))throw new IllegalStateException("PostgreSQL driver missing from package");
        System.out.println("Packaged runtime verified: H2 and PostgreSQL JDBC providers available.");
    }
}
