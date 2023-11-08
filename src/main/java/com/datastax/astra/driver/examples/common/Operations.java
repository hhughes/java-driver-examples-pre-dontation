package com.datastax.astra.driver.examples.common;

import com.datastax.astra.driver.examples.beans.Connections;
import com.datastax.oss.driver.api.core.AllNodesFailedException;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.DriverTimeoutException;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.connection.ClosedConnectionException;
import com.datastax.oss.driver.api.core.connection.HeartbeatException;
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.servererrors.ReadTimeoutException;
import com.datastax.oss.driver.api.core.servererrors.UnavailableException;
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException;
import org.apache.commons.lang3.RandomStringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class Operations {
    private static final boolean USE_NEW_TABLE = false;
    private static final Logger LOG = LoggerFactory.getLogger(Operations.class);

    private static Statement buildCreateTableCql(String tableName) {
        // Idempotent create table
        return SimpleStatement.newInstance(String.format("CREATE TABLE IF NOT EXISTS %s (id uuid PRIMARY KEY, created_at timestamp, string text, number int)", tableName));
    }

    private static Statement buildDropTableCql(String tableName) {
        // Idempotent drop table
        return SimpleStatement.newInstance(String.format("DROP TABLE IF EXISTS %s", tableName));
    }

    private static class Entry {
        final UUID id;
        final String string;
        final int number;

        public Entry(String string, int number) {
            this.id = UUID.randomUUID();
            this.string = string;
            this.number = number;
        }

        @Override
        public String toString() {
            return "Entry{" +
                    "id=" + id +
                    ", string='" + string + '\'' +
                    ", number=" + number +
                    '}';
        }
    }

    public static void runDemo(CqlSession session, long iterations) {

        // init jmxbeans
        Map<Node, AtomicInteger> requestCounts = Collections.synchronizedMap(new WeakHashMap<>());
        try {
            ObjectName objectName = new ObjectName("com.datastax.astra.driver.examples:type=basic,name=sessions");
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            server.registerMBean(new Connections(session, requestCounts, ""), objectName);
        } catch (MalformedObjectNameException | InstanceAlreadyExistsException | MBeanRegistrationException |
                 NotCompliantMBeanException e) {
            throw new RuntimeException(e);
        }

        LOG.debug("Running demo with {} iterations", iterations);

        // Create new table to hold demo data (exit if it does)
        final String tableName = USE_NEW_TABLE ? String.format("demo_%s", UUID.randomUUID().toString().replaceAll("-", "_")) : "demo_singleton";

        Random r = new Random();

        try {
            // attempt create whether we're using new table or not
            LOG.debug("Creating table '{}'", tableName);
            runWithRetries(session, buildCreateTableCql(tableName), requestCounts);

            PreparedStatement preparedWrite = session.prepare(SimpleStatement.newInstance(String.format("INSERT INTO %s (id, created_at, string, number) VALUES (?, ?, ?, ?)", tableName)));
            PreparedStatement preparedReadEntry = session.prepare(SimpleStatement.newInstance(String.format("SELECT created_at, string, number FROM %s WHERE id IN (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", tableName)));

            LinkedList<UUID> ids = new LinkedList<>();

            int i=0;
            // intentional !=  check so that setting iterations < 0 will loop forever
            while (i != iterations) {
                // create new entry with random field values using prepared write statement
                Entry entry = new Entry(RandomStringUtils.randomAlphabetic(10), Math.abs(r.nextInt() % 9999));
                LOG.debug("Run {}: Inserting new entry {}", i++, entry);
                runWithRetries(session, preparedWrite.bind(entry.id, Instant.now(), entry.string, entry.number), requestCounts);

                // accumulate new entry id and remove oldest if neccessary
                ids.add(entry.id);
                if (ids.size() > 10) {
                    ids.removeFirst();
                } else {
                    // cannot use prepare query until we have 10 items available
                    continue;
                }

                // read rows fetched using prepared read statement
                BoundStatementBuilder boundStatementBuilder = preparedReadEntry.boundStatementBuilder();
                for (int pos=0; pos<ids.size(); pos++) {
                    boundStatementBuilder = boundStatementBuilder.setUuid(pos, ids.get(pos));
                }

                runWithRetries(session, boundStatementBuilder.build(), requestCounts)
                        .forEach(row -> LOG.debug("Received record ({}, {}, {})", row.getInstant("created_at"), row.getString("string"), row.getInt("number")));
            }
        } finally {
            if (USE_NEW_TABLE) {
                // if we are using a new table clean it up
                LOG.debug("Removing table '{}'", tableName);
                try {
                    runWithRetries(session, buildDropTableCql(tableName), requestCounts);
                } catch (Exception e) {
                    LOG.error("failed to clean up table", e);
                }
            }

            LOG.debug("Closing connection");
        }
    }

    public static ResultSet runWithRetries(CqlSession session, Statement query, Map<Node, AtomicInteger> requestCounts) {
        // Queries will be retried indefinitely on timeout, they must be idempotent
        // In a real application there should be a limit to the number of retries
        while (true) {
            try {
                ResultSet resultSet = session.execute(query);
                ExecutionInfo executionInfo = resultSet.getExecutionInfo();
                Node node = executionInfo.getCoordinator();
                if (node != null) {
                    requestCounts.computeIfAbsent(node, n -> new AtomicInteger()).incrementAndGet();
                }
                return resultSet;
            } catch (DriverTimeoutException | WriteTimeoutException | ReadTimeoutException | ClosedConnectionException |
                     HeartbeatException e) {
                // request timed-out, catch error and retry
                LOG.warn(String.format("Error '%s' executing query '%s', retrying", e.getMessage(), query), e);
            } catch (AllNodesFailedException e) {
                LOG.error(String.format("AllNodesFailedException error '%s' executing query '%s', retrying", e.getMessage(), query), e);
            } catch (UnavailableException e) {
                LOG.error(String.format("UnavailableException error '%s' executing query '%s', retrying", e.getMessage(), query), e);
            }
        }
    }

    public static CqlSession connect(CqlSessionBuilder sessionBuilder, DriverConfigLoader primaryScbConfig) {
        // Create the database connection session, retry connection failure an unlimited number of times
        // In a real application there should be a limit to the number of retries
        while (true) {
            try {
                return sessionBuilder.withConfigLoader(primaryScbConfig).build();
            } catch (AllNodesFailedException e) {
                // session creation failed, probably due to time-out, catch error and retry
                LOG.warn("Failed to create session.", e);
            }
        }
    }

    public static CqlSession connect(CqlSessionBuilder sessionBuilder, String primaryScb, String fallbackScb, DriverConfigLoader staticConfig) {
        // Create the database connection session, retry connection failure an unlimited number of times
        // In a real application there should be a limit to the number of retries
        while (true) {
            try {
                return sessionBuilder
                        .withCloudSecureConnectBundle(Paths.get(primaryScb))
                        .withConfigLoader(staticConfig).build();
            } catch (AllNodesFailedException | IllegalStateException e) {
                // session creation failed, probably due to time-out, catch error and retry
                LOG.warn("Failed to create session.", e);
                return sessionBuilder
                        .withCloudSecureConnectBundle(Paths.get(fallbackScb))
                        .withConfigLoader(staticConfig).build();
            }
        }
    }
}
