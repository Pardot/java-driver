package com.datastax.driver.core;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.cassandra.transport.messages.QueryMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Cassandra trace for a query.
 * <p>
 * Such trace is generated by Cassandra when query tracing is enabled for the
 * query. The trace itself is stored in Cassandra in the {@code sessions} and
 * {@code events} table in the {@code system_traces} keyspace and can be
 * retrieve manually using the trace identifier (the one returned by
 * {@link #getTraceId}).
 * <p>
 * This class provides facilities to fetch the traces from Cassandra. Please
 * note that the writting of the trace is done asynchronously in Cassandra. So
 * accessing the trace too soon after the query may result in the trace being
 * incomplete.
 */
public class QueryTrace {
    private static final Logger logger = LoggerFactory.getLogger(QueryTrace.class);

    private static final String SELECT_SESSIONS_FORMAT = "SELECT * FROM system_traces.sessions WHERE session_id = %s";
    private static final String SELECT_EVENTS_FORMAT = "SELECT * FROM system_traces.events WHERE session_id = %s";

    private final UUID traceId;

    private String requestType;
    // We use the duration to figure out if the trace is complete, because
    // that's the last event that is written (and it is written asynchronously
    // so it's possible that a fetch gets all the trace except the duration).
    private int duration = Integer.MIN_VALUE;
    private InetAddress coordinator;
    private Map<String, String> parameters;
    private long startedAt;
    private List<Event> events;

    private final Session.Manager session;
    private final Lock fetchLock = new ReentrantLock();

    QueryTrace(UUID traceId, Session.Manager session) {
        this.traceId = traceId;
        this.session = session;
    }

    /**
     * The identifier of this trace.
     *
     * @return the identifier of this trace.
     */
    public UUID getTraceId() {
        return traceId;
    }

    /**
     * The type of request.
     *
     * @return the type of request. This method returns {@code null} if the request
     * type is not yet available.
     */
    public String getRequestType() {
        maybeFetchTrace();
        return requestType;
    }

    /**
     * The (server side) duration of the query in microseconds.
     *
     * @return the (server side) duration of the query in microseconds. This
     * method will return {@code Integer.MIN_VALUE} if the duration is not yet
     * available.
     */
    public int getDurationMicros() {
        maybeFetchTrace();
        return duration;
    }

    /**
     * The coordinator host of the query.
     *
     * @return the coordinator host of the query. This method returns {@code null}
     * if the coordinator is not yet available.
     */
    public InetAddress getCoordinator() {
        maybeFetchTrace();
        return coordinator;
    }

    /**
     * The parameters attached to this trace.
     *
     * @return the parameters attached to this trace. This method returns
     * {@code null} if the coordinator is not yet available.
     */
    public Map<String, String> getParameters() {
        maybeFetchTrace();
        return parameters;
    }

    /**
     * The server side timestamp of the start of this query.
     *
     * @return the server side timestamp of the start of this query. This
     * method returns 0 if the start timestamp is not available.
     */
    public long getStartedAt() {
        maybeFetchTrace();
        return startedAt;
    }

    /**
     * The events contained in this trace.
     *
     * @return the events contained in this trace.
     */
    public List<Event> getEvents() {
        maybeFetchTrace();
        return events;
    }

    @Override
    public String toString() {
        maybeFetchTrace();
        return String.format("%s [%s] - %dµs", requestType, traceId, duration);
    }

    private void maybeFetchTrace() {
        if (duration != Integer.MIN_VALUE)
            return;

        fetchLock.lock();
        try {
            // If by the time we grab the lock we've fetch the events, it's
            // fine, move on. Otherwise, fetch them.
            if (duration == Integer.MIN_VALUE) {
                doFetchTrace();
            }
        } finally {
            fetchLock.unlock();
        }
    }

    private void doFetchTrace() {
        try {
            ResultSetFuture sessionsFuture = session.executeQuery(new QueryMessage(String.format(SELECT_SESSIONS_FORMAT, traceId), ConsistencyLevel.DEFAULT_CASSANDRA_CL), Query.DEFAULT);
            ResultSetFuture eventsFuture = session.executeQuery(new QueryMessage(String.format(SELECT_EVENTS_FORMAT, traceId), ConsistencyLevel.DEFAULT_CASSANDRA_CL), Query.DEFAULT);

            CQLRow sessRow = sessionsFuture.get().fetchOne();
            if (sessRow != null) {
                requestType = sessRow.getString("request");
                if (!sessRow.isNull("duration"))
                    duration = sessRow.getInt("duration");
                coordinator = sessRow.getInet("coordinator");
                if (!sessRow.isNull("parameters"))
                    parameters = Collections.unmodifiableMap(sessRow.getMap("parameters", String.class, String.class));
                startedAt = sessRow.getDate("started_at").getTime();
            }

            events = new ArrayList<Event>();
            for (CQLRow evRow : eventsFuture.get()) {
                events.add(new Event(evRow.getString("activity"),
                                     evRow.getUUID("event_id").timestamp(),
                                     evRow.getInet("source"),
                                     evRow.getInt("source_elapsed"),
                                     evRow.getString("thread")));
            }
            events = Collections.unmodifiableList(events);

        } catch (Exception e) {
            logger.error("Unexpected exception while fetching query trace", e);
        }
    }

    /**
     * A trace event.
     * <p>
     * A query trace is composed of a list of trace events.
     */
    public static class Event {
        private final String name;
        private final long timestamp;
        private final InetAddress source;
        private final int sourceElapsed;
        private final String threadName;

        private Event(String name, long timestamp, InetAddress source, int sourceElapsed, String threadName) {
            this.name = name;
            // Convert the UUID timestamp to an epoch timestamp; I stole this seemingly random value from cqlsh, hopefully it's correct.
            this.timestamp = (timestamp - 0x01b21dd213814000L) / 10000;
            this.source = source;
            this.sourceElapsed = sourceElapsed;
            this.threadName = threadName;
        }

        /**
         * The event description, i.e. which activity this event correspond to.
         *
         * @return the event description.
         */
        public String getDescription() {
            return name;
        }

        /**
         * The server side timestamp of the event.
         *
         * @return the server side timestamp of the event.
         */
        public long getTimestamp() {
            return timestamp;
        }

        /**
         * The address of the host having generated this event.
         *
         * @return the address of the host having generated this event.
         */
        public InetAddress getSource() {
            return source;
        }

        /**
         * The number of microseconds elapsed on the source when this event
         * occurred since when the source started handling the query.
         *
         * @return the elapsed time on the source host when that event happened
         * in microseconds.
         */
        public int getSourceElapsedMicros() {
            return sourceElapsed;
        }

        /**
         * The name of the thread on which this event occured.
         *
         * @return the name of the thread on which this event occured.
         */
        public String getThreadName() {
            return threadName;
        }

        @Override
        public String toString() {
            return String.format("%s on %s[%s] at %s", name, source, threadName, new Date(timestamp));
        }
    }
}
