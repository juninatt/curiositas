package se.pbt.curiositas.person;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import se.pbt.curiositas.TestcontainersConfiguration;
import se.pbt.curiositas.date.HistoricalDate;
import se.pbt.curiositas.person.InvalidPersonException.Violation;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies replacing persons against the real database, since optimistic locking depends on the
 * version the database actually stores.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, PersonService.class, PersonHistory.class})
class PersonServiceTest {

    @Autowired
    private PersonService service;

    @Autowired
    private TestEntityManager entityManager;

    /** Applies the changes and moves the person to the next version. */
    @Test
    void replacesPersonAtExpectedVersion() {
        Person created = service.create(new Person("Edward Teach", Gender.MALE));

        Person replaced = service.replace(created.getId(), 0,
                person -> person.setBirth(new HistoricalDate(1680, null, null, 5)));

        assertThat(replaced.getVersion()).isEqualTo(1);
        assertThat(reload(created.getId()).getBirth()).isEqualTo(new HistoricalDate(1680, null, null, 5));
    }

    /** Refuses changes based on an outdated version and leaves the stored person as it was. */
    @Test
    void refusesReplaceAtOutdatedVersion() {
        Person created = service.create(new Person("Edward Teach", Gender.MALE));
        service.replace(created.getId(), 0, person -> person.setBirthPlace("Bristol"));

        assertThatExceptionOfType(PersonChangedException.class)
                .isThrownBy(() -> service.replace(created.getId(), 0, person -> person.setName("Edward Thatch")))
                .withMessageContaining("version 0")
                .withMessageContaining("version 1");
        assertThat(reload(created.getId()).getName()).isEqualTo("Edward Teach");
    }

    /** Leaves the person unchanged when the new data is rejected. */
    @Test
    void keepsPersonWhenChangesAreRejected() {
        Person created = service.create(new Person("Edward Teach", Gender.MALE));

        assertThatExceptionOfType(InvalidPersonException.class)
                .isThrownBy(() -> service.replace(created.getId(), 0, person -> {
                    throw new InvalidPersonException(List.of(new Violation("death", "is before birth")));
                }));
        assertThat(reload(created.getId()).getVersion()).isZero();
    }

    /** Answers that the person does not exist rather than creating it. */
    @Test
    void refusesReplaceOfUnknownPerson() {
        assertThatExceptionOfType(PersonNotFoundException.class)
                .isThrownBy(() -> service.replace(UUID.randomUUID(), 0, person -> person.setName("Nobody")));
    }

    /** Rejects a second person with the same Wikidata id, whatever else differs. */
    @Test
    void rejectsSameWikidataId() {
        Person caesar = new Person("Julius Caesar", Gender.MALE);
        caesar.setWikidataId("Q1048");
        Person existing = service.create(caesar);

        Person copy = new Person("Gaius Julius Caesar", Gender.MALE);
        copy.setWikidataId("Q1048");

        assertThatExceptionOfType(DuplicatePersonException.class)
                .isThrownBy(() -> service.create(copy))
                .withMessageContaining(existing.getId().toString())
                .withMessageContaining("Q1048");
    }

    /**
     * Without Wikidata ids, rejects a person with the same name, written differently, and the
     * same birth, death and birth country.
     */
    @Test
    void rejectsSameNameAndLifeWithoutWikidataId() {
        service.create(teach("Edward Teach", 1680));

        assertThatExceptionOfType(DuplicatePersonException.class)
                .isThrownBy(() -> service.create(teach("  edward   TEACH ", 1680)))
                .withMessageContaining("same name, birth, death and birth country");
    }

    /** Treats persons with the same name but a different life as different persons. */
    @Test
    void allowsSameNameWithDifferentLife() {
        service.create(teach("Edward Teach", 1680));

        Person other = service.create(teach("Edward Teach", 1690));

        assertThat(other.getId()).isNotNull();
    }

    /** Treats two persons with different Wikidata ids as different, even with the same name and life. */
    @Test
    void allowsSameNameAndLifeWithDifferentWikidataIds() {
        Person first = teach("Edward Teach", 1680);
        first.setWikidataId("Q1");
        service.create(first);
        Person second = teach("Edward Teach", 1680);
        second.setWikidataId("Q2");

        assertThat(service.create(second).getId()).isNotNull();
    }

    /** Lets a person be replaced without counting itself as its own duplicate. */
    @Test
    void doesNotCountPersonAsItsOwnDuplicate() {
        Person created = service.create(teach("Edward Teach", 1680));

        Person replaced = service.replace(created.getId(), 0, person -> person.setDeathPlace("Ocracoke Island"));

        assertThat(replaced.getDeathPlace()).isEqualTo("Ocracoke Island");
    }

    /** Rejects a replacement that would turn a person into a duplicate of another, and keeps the old data. */
    @Test
    void rejectsReplaceThatCreatesDuplicate() {
        service.create(teach("Edward Teach", 1680));
        Person other = service.create(teach("Edward Teach", 1690));

        assertThatExceptionOfType(DuplicatePersonException.class)
                .isThrownBy(() -> service.replace(other.getId(), 0,
                        person -> person.setBirth(new HistoricalDate(1680, null, null, 5))));
        assertThat(reload(other.getId()).getBirth().year()).isEqualTo(1690);
    }

    /** Creates an Edward Teach born in the given year, give or take five years, and died in 1718. */
    private static Person teach(String name, int birthYear) {
        Person person = new Person(name, Gender.MALE);
        person.setBirth(new HistoricalDate(birthYear, null, null, 5));
        person.setDeath(new HistoricalDate(1718, 11, 22, 0));
        return person;
    }

    /** Reads the person from the database rather than from the persistence context. */
    private Person reload(UUID id) {
        entityManager.clear();
        return service.get(id);
    }
}
