package io.pravega.connectors.flink.sink;

import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.pravega.client.ClientConfig;
import io.pravega.client.EventStreamClientFactory;
import io.pravega.client.stream.EventStreamWriter;
import io.pravega.client.stream.EventWriterConfig;
import io.pravega.client.stream.Serializer;
import io.pravega.client.stream.Stream;
import io.pravega.client.stream.Transaction;
import io.pravega.client.stream.TransactionalEventStreamWriter;
import io.pravega.client.stream.TxnFailedException;
import io.pravega.connectors.flink.FlinkPravegaWriter;
import io.pravega.connectors.flink.PravegaEventRouter;
import io.pravega.connectors.flink.PravegaWriterMode;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.api.connector.sink.SinkWriter;
import org.apache.flink.util.ExceptionUtils;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class FlinkPravegaInternalWriter<T> implements AutoCloseable {

    // The Pravega client config.
    private final ClientConfig clientConfig;

    // Various timeouts
    private final long txnLeaseRenewalPeriod;

    // The destination stream.
    @SuppressFBWarnings("SE_BAD_FIELD")
    private final Stream stream;

    // The sink's mode of operation. This is used to provide different guarantees for the written events.
    private final PravegaWriterMode writerMode;

    // flag to enable/disable watermark
    private final boolean enableWatermark;

    private final SerializationSchema<T> serializationSchema;

    // The router used to partition events within a stream, can be null for random routing
    private final PravegaEventRouter<T> eventRouter;

    private long currentWatermark = Long.MIN_VALUE;

    // Pravega writer instance
    @Nullable
    private transient EventStreamWriter<T> writer = null;

    // Transactional Pravega writer instance
    @Nullable
    private transient TransactionalEventStreamWriter<T> transactionalWriter = null;

    // Transaction
    @Nullable
    private transient PravegaTransactionState<T> transaction = null;

    // Client factory for PravegaWriter instances
    @Nullable
    private transient EventStreamClientFactory clientFactory = null;

    // ----------- Runtime fields ----------------

    // Error which will be detected asynchronously and reported to Flink
    @VisibleForTesting
    volatile AtomicReference<Throwable> writeError = new AtomicReference<>(null);

    // Used to track confirmation from all writes to ensure guaranteed writes.
    @VisibleForTesting
    AtomicLong pendingWritesCount = new AtomicLong();

    private transient ExecutorService executorService;

    // Pravega Writer Id
    private final String writerId;

    public FlinkPravegaInternalWriter(ClientConfig clientConfig,
                                      long txnLeaseRenewalPeriod,
                                      Stream stream,
                                      PravegaWriterMode writerMode,
                                      boolean enableWatermark,
                                      SerializationSchema<T> serializationSchema,
                                      PravegaEventRouter<T> eventRouter,
                                      String writerId) {
        this.clientConfig = clientConfig;
        this.txnLeaseRenewalPeriod = txnLeaseRenewalPeriod;
        this.stream = stream;
        this.writerMode = writerMode;
        this.enableWatermark = enableWatermark;
        this.serializationSchema = serializationSchema;
        this.eventRouter = eventRouter;
        this.writerId = writerId;

        initializeInternalWriter();

        log.info("Initialized Pravega writer {} for stream: {} with controller URI: {}",
                writerId, stream, clientConfig.getControllerURI());
    }

    public FlinkPravegaInternalWriter(ClientConfig clientConfig,
                                      long txnLeaseRenewalPeriod,
                                      Stream stream,
                                      PravegaWriterMode writerMode,
                                      boolean enableWatermark,
                                      SerializationSchema<T> serializationSchema,
                                      PravegaEventRouter<T> eventRouter,
                                      String writerId,
                                      PravegaTransactionState<T> transaction) {
        this(clientConfig, txnLeaseRenewalPeriod, stream, writerMode,
                enableWatermark, serializationSchema, eventRouter, writerId);
        this.transaction = transaction;
        log.info("Initialized Pravega writer with transaction");
    }

    public void initializeInternalWriter() {
        if (this.writerMode == PravegaWriterMode.EXACTLY_ONCE) {
            if (this.transactionalWriter != null) {
                return;
            }
        } else {
            if (this.writer != null) {
                return;
            }
        }

        if (this.writerMode == PravegaWriterMode.EXACTLY_ONCE && !isCheckpointEnabled()) {
            // Pravega transaction writer (exactly-once) implementation can be used only when checkpoint is enabled
            throw new UnsupportedOperationException("Enable checkpointing to use the exactly-once writer mode.");
        }

        this.clientFactory = createClientFactory(stream.getScope(), clientConfig);
        createInternalWriter(this.clientFactory);
    }

    protected void createInternalWriter(EventStreamClientFactory clientFactory) {
        Serializer<T> eventSerializer = new PravegaWriter.FlinkSerializer<>(serializationSchema);
        EventWriterConfig writerConfig = EventWriterConfig.builder()
                .transactionTimeoutTime(txnLeaseRenewalPeriod)
                .build();
        if (this.writerMode == PravegaWriterMode.EXACTLY_ONCE) {
            transactionalWriter = clientFactory.createTransactionalEventWriter(writerId, stream.getStreamName(), eventSerializer, writerConfig);
        } else {
            executorService = Executors.newSingleThreadExecutor();
            writer = clientFactory.createEventWriter(writerId, stream.getStreamName(), eventSerializer, writerConfig);
        }
    }

    protected EventStreamClientFactory createClientFactory(String scopeName, ClientConfig clientConfig) {
        return EventStreamClientFactory.withScope(scopeName, clientConfig);
    }

    private boolean isCheckpointEnabled() {
        return true;
        // return ((StreamingRuntimeContext) getRuntimeContext()).isCheckpointingEnabled();
    }

    public void beginTransaction() {
        initializeInternalWriter();
        switch (writerMode) {
            case EXACTLY_ONCE:
                assert transactionalWriter != null;
                Transaction<T> txn = transactionalWriter.beginTxn();
                this.transaction = new PravegaTransactionState<>(txn, writerId);
            case ATLEAST_ONCE:
            case BEST_EFFORT:
                this.transaction = new PravegaTransactionState<>();
            default:
                throw new UnsupportedOperationException("Not implemented writer mode");
        }
    }

    public void write(T element, SinkWriter.Context context) throws TxnFailedException, IOException {
        checkWriteError();

        switch (writerMode) {
            case EXACTLY_ONCE:
                assert transactionalWriter != null && transaction != null;

                if (eventRouter != null) {
                    transaction.getTransaction().writeEvent(eventRouter.getRoutingKey(element), element);
                } else {
                    transaction.getTransaction().writeEvent(element);
                }

                if (enableWatermark) {
                    transaction.setWatermark(context.currentWatermark());
                }
                break;
            case ATLEAST_ONCE:
            case BEST_EFFORT:
                assert writer != null;
                this.pendingWritesCount.incrementAndGet();
                final CompletableFuture<Void> future;
                if (eventRouter != null) {
                    future = writer.writeEvent(eventRouter.getRoutingKey(element), element);
                } else {
                    future = writer.writeEvent(element);
                }
                if (enableWatermark && shouldEmitWatermark(currentWatermark, context)) {
                    writer.noteTime(context.currentWatermark());
                    currentWatermark = context.currentWatermark();
                }
                future.whenCompleteAsync(
                        (result, e) -> {
                            if (e != null) {
                                log.warn("Detected a write failure", e);

                                // We will record only the first error detected, since this will mostly likely help with
                                // finding the root cause. Storing all errors will not be feasible.
                                writeError.compareAndSet(null, e);
                            }
                            synchronized (this) {
                                pendingWritesCount.decrementAndGet();
                                this.notify();
                            }
                        },
                        executorService
                );
                break;
            default:
                throw new UnsupportedOperationException("Not implemented writer mode");
        }
    }

    public void commitTransaction() {
        switch (writerMode) {
            case EXACTLY_ONCE:
                assert transaction != null;
                // This may come from a job recovery from a non-transactional writer.
                if (transaction.getTransactionId() == null) {
                    break;
                }
                final Transaction<T> txn = transaction.getTransaction();
                try {
                    final Transaction.Status status = txn.checkStatus();
                    if (status == Transaction.Status.OPEN) {
                        if (enableWatermark && transaction.getWatermark() != null) {
                            txn.commit(transaction.getWatermark());
                        } else {
                            txn.commit();
                        }
                    } else {
                        log.warn("{} - Transaction {} has unexpected transaction status {} while committing",
                                writerId, txn.getTxnId(), status);
                    }
                } catch (TxnFailedException e) {
                    log.error("{} - Transaction {} commit failed.", writerId, txn.getTxnId());
                } catch (StatusRuntimeException e) {
                    if (e.getStatus() == Status.NOT_FOUND) {
                        log.error("{} - Transaction {} not found.", writerId, txn.getTxnId());
                    }
                }
                break;
            case ATLEAST_ONCE:
            case BEST_EFFORT:
                break;
            default:
                throw new UnsupportedOperationException("Not implemented writer mode");
        }
    }

    public void abort() {
        assert transaction != null;
        switch (writerMode) {
            case EXACTLY_ONCE:
                // This may come from a job recovery from a non-transactional writer.
                if (transaction.getTransactionId() == null) {
                    break;
                }
                log.info("Aborting the transaction: {}", transaction.getTransactionId());
                final Transaction<T> txn = transaction.getTransaction() != null ? transaction.getTransaction() :
                        transactionalWriter.getTxn(UUID.fromString(transaction.getTransactionId()));
                txn.abort();
                break;
            case ATLEAST_ONCE:
            case BEST_EFFORT:
                break;
            default:
                throw new UnsupportedOperationException("Not implemented writer mode");
        }
    }

    @VisibleForTesting
    public void flushAndVerify() throws IOException, InterruptedException {
        writer.flush();

        // Wait until all errors, if any, have been recorded.
        synchronized (this) {
            while (this.pendingWritesCount.get() > 0) {
                this.wait();
            }
        }

        checkWriteError();
    }

    private void checkWriteError() throws IOException {
        Throwable error = this.writeError.getAndSet(null);
        if (error != null) {
            throw new IOException("Write failure", error);
        }
    }

    @Override
    public void close() throws Exception {
        Exception exception = null;

        // try {
        //     // Current transaction will be aborted with this method
        //     super.close();
        // } catch (Exception e) {
        //     exception = e;
        // }

        if (writer != null) {
            try {
                flushAndVerify();
            } catch (Exception e) {
                exception = ExceptionUtils.firstOrSuppressed(e, exception);
            }

            try {
                writer.close();
            } catch (Exception e) {
                exception = ExceptionUtils.firstOrSuppressed(e, exception);
            }

            try {
                executorService.shutdown();
            } catch (Exception e) {
                exception = ExceptionUtils.firstOrSuppressed(e, exception);
            }
        }

        if (transactionalWriter != null) {
            try {
                transactionalWriter.close();
            } catch (Exception e) {
                exception = ExceptionUtils.firstOrSuppressed(e, exception);
            }
        }

        if (clientFactory != null) {
            try {
                clientFactory.close();
            } catch (Exception e) {
                exception = ExceptionUtils.firstOrSuppressed(e, exception);
            }
        }

        if (exception != null) {
            throw exception;
        }
    }

    boolean shouldEmitWatermark(long watermark, SinkWriter.Context context) {
        return context.currentWatermark() > Long.MIN_VALUE && context.currentWatermark() < Long.MAX_VALUE &&
                watermark < context.currentWatermark() && context.timestamp() >= context.currentWatermark();
    }

    public String getWriterId() {
        return this.writerId;
    }

    public long getCurrentWatermark() {
        return currentWatermark;
    }

    public String getTransactionId() {
        assert this.transaction != null;
        return this.transaction.getTransactionId();
    }
}
