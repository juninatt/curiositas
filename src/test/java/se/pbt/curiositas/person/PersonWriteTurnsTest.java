package se.pbt.curiositas.person;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import se.pbt.curiositas.TestcontainersConfiguration;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies that writes take turns, so changes are numbered in the order they are committed and a
 * client reading the changes can never skip one. Two threads write at the same time; latches decide
 * exactly when each one may go on, so the tests behave the same on every run.
 *
 * <p>Runs without the usual test transaction, since each thread needs its own committed transaction.
 */
@DataJpaTest(showSql = false)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, PersonService.class, PersonHistory.class})
class PersonWriteTurnsTest {

    /** Long enough for a write that is not held back to finish many times over. */
    private static final long SHORT_WAIT_MS = 500;

    private static final long LONG_WAIT_S = 10;

    private final ExecutorService threads = Executors.newFixedThreadPool(2);

    @Autowired
    private PersonService service;

    @Autowired
    private PersonRepository persons;

    @Autowired
    private PersonRevisionRepository revisions;

    @Autowired
    private PlatformTransactionManager transactions;

    /** Stops the threads and removes what the tests committed. */
    @AfterEach
    void cleanUp() {
        threads.shutdownNow();
        persons.deleteAll();
        revisions.deleteAll();
    }

    /**
     * Makes a second write wait until the first is committed, so the second always gets the
     * higher number and is visible no earlier than the first.
     */
    @Test
    void numbersChangesInTheOrderTheyAreCommitted() throws Exception {
        CountDownLatch firstHasWritten = new CountDownLatch(1);
        CountDownLatch firstMayCommit = new CountDownLatch(1);
        Future<UUID> first = threads.submit(() -> new TransactionTemplate(transactions).execute(status -> {
            UUID id = service.create(new Person("Edward Teach", Gender.MALE)).getId();
            firstHasWritten.countDown();
            await(firstMayCommit);
            return id;
        }));
        assertThat(firstHasWritten.await(LONG_WAIT_S, TimeUnit.SECONDS)).isTrue();

        Future<UUID> second = threads.submit(() -> service.create(new Person("Anne Bonny", Gender.FEMALE)).getId());

        assertThatExceptionOfType(TimeoutException.class)
                .as("the second write waits while the first is not committed")
                .isThrownBy(() -> second.get(SHORT_WAIT_MS, TimeUnit.MILLISECONDS));
        firstMayCommit.countDown();
        UUID teach = first.get(LONG_WAIT_S, TimeUnit.SECONDS);
        UUID bonny = second.get(LONG_WAIT_S, TimeUnit.SECONDS);
        assertThat(sequenceNumberOf(bonny)).isGreaterThan(sequenceNumberOf(teach));
    }

    /** Lets reads through while a write is open, showing the data as it was before the write. */
    @Test
    void neverMakesReadsWait() throws Exception {
        CountDownLatch writeHasStarted = new CountDownLatch(1);
        CountDownLatch writeMayCommit = new CountDownLatch(1);
        Future<?> write = threads.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.create(new Person("Edward Teach", Gender.MALE));
            writeHasStarted.countDown();
            await(writeMayCommit);
        }));
        assertThat(writeHasStarted.await(LONG_WAIT_S, TimeUnit.SECONDS)).isTrue();

        Future<Long> read = threads.submit(() -> service.list(0, 20).getTotalElements());

        assertThat(read.get(SHORT_WAIT_MS, TimeUnit.MILLISECONDS)).isZero();
        writeMayCommit.countDown();
        write.get(LONG_WAIT_S, TimeUnit.SECONDS);
        assertThat(service.list(0, 20).getTotalElements()).isEqualTo(1);
    }

    /** Returns the sequence number of the person's first revision. */
    private long sequenceNumberOf(UUID personId) {
        return revisions.findByPersonIdOrderByRevision(personId).getFirst().getSequenceNumber();
    }

    /** Waits for the latch, failing instead of hanging if the test has gone wrong. */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(LONG_WAIT_S, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Waited too long for the test to continue");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
