package com.datastax.astra.driver.examples.common;

import com.datastax.oss.driver.api.core.AllNodesFailedException;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.DriverTimeoutException;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.connection.ClosedConnectionException;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.servererrors.ReadTimeoutException;
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException;
import com.datastax.oss.driver.api.core.type.codec.ExtraTypeCodecs;
import org.apache.commons.lang3.RandomStringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Random;
import java.util.UUID;

public class Operations {
    private static final boolean USE_NEW_TABLE = false;
    private static final Logger LOG = LoggerFactory.getLogger(Operations.class);

    private static Statement buildCreateTableCql(String tableName) {
        // Idempotent create table
        return SimpleStatement.newInstance(String.format("CREATE TABLE IF NOT EXISTS %s (id uuid PRIMARY KEY, created_at timestamp, string text, number int, weights vector<float, 3>)", tableName));

    }

    private static Statement buildDropTableCql(String tableName) {
        // Idempotent drop table
        return SimpleStatement.newInstance(String.format("DROP TABLE IF EXISTS %s", tableName));
    }

    private static class Entry {
        final UUID id;
        final String string;
        final int number;
        final float[] weights;

        public Entry(String string, int number, float[] weights) {
            this.id = UUID.randomUUID();
            this.string = string;
            this.number = number;
            this.weights = weights;
        }

        @Override
        public String toString() {
            return "Entry{" +
                    "id=" + id +
                    ", string='" + string + '\'' +
                    ", number=" + number +
                    ", weights=" + Arrays.toString(weights) +
                    '}';
        }
    }

    public static void runDemo(CqlSession session, long iterations) {
        LOG.debug("Running demo with {} iterations", iterations);

        // Create new table to hold demo data (exit if it does)
        final String tableName = USE_NEW_TABLE ? String.format("demo_%s", UUID.randomUUID().toString().replaceAll("-", "_")) : "demo_singleton";

        Random r = new Random();

        try {
            // attempt create whether we're using new table or not
            LOG.debug("Creating table '{}'", tableName);
            runWithRetries(session, buildCreateTableCql(tableName));
            runWithRetries(session, SimpleStatement.newInstance(String.format("CREATE CUSTOM INDEX IF NOT EXISTS ann_index_%s ON %s(weights) USING 'StorageAttachedIndex'", tableName, tableName)));
            runWithRetries(session, SimpleStatement.newInstance(String.format("CREATE CUSTOM INDEX IF NOT EXISTS number_%s ON %s(number) USING 'StorageAttachedIndex'", tableName, tableName)));

            PreparedStatement preparedWrite = session.prepare(SimpleStatement.newInstance(String.format("INSERT INTO %s (id, created_at, string, number, weights) VALUES (?, ?, ?, ?, ?)", tableName)));
            PreparedStatement preparedReadEntry = prepare(session, String.format("SELECT created_at, string, number, weights FROM %s WHERE id IN ?", tableName));

            LinkedList<UUID> ids = new LinkedList<>();

            int i=0;
            // intentional !=  check so that setting iterations < 0 will loop forever
            while (i != iterations) {
                // create new entry with random field values using prepared write statement
                Entry entry = new Entry(RandomStringUtils.randomAlphabetic(10), Math.abs(r.nextInt() % 9999), new float[] { r.nextFloat(), r.nextFloat(), r.nextFloat() });
                LOG.debug("Run {}: Inserting new entry {}", i++, entry);

                // bind variables from entry
                BoundStatement boundWrite = preparedWrite.boundStatementBuilder()
                        .set("id", entry.id, UUID.class)
                        .set("created_at", Instant.now(), Instant.class)
                        .set("string", entry.string, String.class)
                        .set("number", entry.number, Integer.class)
                        .set("weights", entry.weights, ExtraTypeCodecs.floatVectorToArray(3))
                        .build();

                runWithRetries(session, boundWrite);

                // accumulate new entry id and remove oldest if neccessary
                ids.add(entry.id);
                if (ids.size() > 10) {
                    ids.removeFirst();
                }

                // read rows fetched using prepared read statement
                BoundStatementBuilder boundStatementBuilder = preparedReadEntry.boundStatementBuilder();
                LOG.debug(String.format("Binding prepared statement with: %s", ids));
                boundStatementBuilder.setList(0, ids, UUID.class);

                runWithRetries(session, boundStatementBuilder.build())
                        .forEach(row -> LOG.debug("Received record ({}, {}, {})", row.getInstant("created_at"), row.getString("string"), row.getInt("number")));

                runWithRetries(session, SimpleStatement.newInstance(String.format("SELECT created_at, string, number, weights FROM %s WHERE number IN (76, 322, 735) ORDER BY weights ANN OF [3.4, 7.8, 9.1] limit 1000 ALLOW FILTERING", tableName)))
                        .forEach(row -> LOG.debug("Received ANN record ({}, {}, {}, {})", row.getInstant("created_at"), row.getString("string"), row.getInt("number"), row.getVector("weights", Float.class)));
            }
        } finally {
            if (USE_NEW_TABLE) {
                // if we are using a new table clean it up
                LOG.debug("Removing table '{}'", tableName);
                try {
                    runWithRetries(session, buildDropTableCql(tableName));
                } catch (Exception e) {
                    LOG.error("failed to clean up table", e);
                }
            }

            LOG.debug("Closing connection");
        }
    }

    private static PreparedStatement prepare(CqlSession session, String query) {
        LOG.debug(String.format("Preparing query: %s", query));
        return session.prepare(SimpleStatement.newInstance(query));
    }

    public static ResultSet runWithRetries(CqlSession session, Statement query) {
        // Queries will be retried indefinitely on timeout, they must be idempotent
        // In a real application there should be a limit to the number of retries
        while (true) {
            try {
                if (query instanceof SimpleStatement) {
                    LOG.debug(String.format("Running query: %s", ((SimpleStatement) query).getQuery()));
                } else if (query instanceof BoundStatement) {
                    BoundStatement bs = (BoundStatement) query;
                    LOG.debug(String.format("Running query: %s", bs.getPreparedStatement().getQuery()));
                }
                ResultSet resultSet = session.execute(query);
                LOG.debug(String.format("Received result: %s", resultSet));
                return resultSet;
            } catch (DriverTimeoutException | WriteTimeoutException | ReadTimeoutException | ClosedConnectionException e) {
                // request timed-out, catch error and retry
                LOG.warn(String.format("Error '%s' executing query '%s', retrying", e.getMessage(), query), e);
            }
        }
    }

    public static CqlSession connect(CqlSessionBuilder sessionBuilder, DriverConfigLoader primaryScbConfig) {
        // Create the database connection session, retry connection failure an unlimited number of times
        // In a real application there should be a limit to the number of retries
        while (true) {
            try {
                return sessionBuilder.withConfigLoader(primaryScbConfig).build();
            } catch (AllNodesFailedException | IllegalStateException e) {
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
