package se.pbt.curiositas.person;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.pbt.curiositas.date.HistoricalDate;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Reads and changes persons. Keeps transactions and business rules out of the web layer, and
 * records every change in the {@link PersonHistory}.
 */
@Service
@Transactional(readOnly = true)
public class PersonService {

    /** Orders by name, with the id as tie-breaker so pages stay stable when names are equal. */
    private static final Sort BY_NAME = Sort.by("name").and(Sort.by("id"));

    private final PersonRepository repository;
    private final PersonHistory history;
    private final ApplicationEventPublisher events;

    /**
     * Creates the service.
     *
     * @param repository where persons are stored
     * @param history    records every change, in the same transaction
     * @param events     announces changes, so the data files can follow them
     */
    public PersonService(PersonRepository repository, PersonHistory history, ApplicationEventPublisher events) {
        this.repository = repository;
        this.history = history;
        this.events = events;
    }

    /**
     * Returns one page of persons, ordered by name.
     *
     * @param page the page number, starting at 0
     * @param size the number of persons per page
     * @return the persons on that page
     */
    public Page<Person> list(int page, int size) {
        return repository.findAll(PageRequest.of(page, size, BY_NAME));
    }

    /**
     * Returns every person, for exporting them all at once.
     *
     * @return all persons, ordered by name
     */
    public List<Person> listAll() {
        return repository.findAll(BY_NAME);
    }

    /**
     * Returns the person with the given id.
     *
     * @param id the id of the person
     * @return the person
     * @throws PersonDeletedException  if the person has been deleted
     * @throws PersonNotFoundException if no person with that id has ever existed
     */
    public Person get(UUID id) {
        return repository.findById(id).orElseThrow(() -> history.deletedAt(id)
                .<RuntimeException>map(deletedAt -> new PersonDeletedException(id, deletedAt))
                .orElseGet(() -> new PersonNotFoundException(id)));
    }

    /**
     * Returns every revision of a person, oldest first, also when the person has been deleted.
     *
     * @param id the id of the person
     * @return the revisions, at least one
     * @throws PersonNotFoundException if no person with that id has ever existed
     */
    public List<PersonRevision> revisions(UUID id) {
        List<PersonRevision> revisions = history.of(id);
        if (revisions.isEmpty()) {
            throw new PersonNotFoundException(id);
        }
        return revisions;
    }

    /**
     * Saves a new person. The person is written immediately, so the returned person has its id,
     * version and timestamps.
     *
     * @param person the new person, already validated
     * @return the saved person
     * @throws DuplicatePersonException if the person is already in the database
     */
    @Transactional
    public Person create(Person person) {
        history.takeTurnToWrite();
        rejectDuplicate(person);
        Person created = repository.saveAndFlush(person);
        history.created(created, ChangeSource.API);
        events.publishEvent(new PersonsChangedEvent());
        return created;
    }

    /**
     * Replaces a person's data, but only if nobody has changed the person since the client read
     * it. The version is checked here, and again by the database when writing, which also catches
     * a change made between those two moments. A replacement with the same data changes nothing,
     * so it keeps the version and adds no revision.
     *
     * @param id              the id of the person
     * @param expectedVersion the version the client read before making its change
     * @param changes         applies the new data to the person; may reject it
     * @return the updated person with its new version
     * @throws PersonDeletedException   if the person has been deleted
     * @throws PersonNotFoundException  if no person with that id has ever existed
     * @throws PersonChangedException   if the person is no longer at the expected version
     * @throws DuplicatePersonException if the changes would make the person a duplicate of another
     */
    @Transactional
    public Person replace(UUID id, long expectedVersion, Consumer<Person> changes) {
        history.takeTurnToWrite();
        Person person = get(id);
        if (person.getVersion() != expectedVersion) {
            throw new PersonChangedException(expectedVersion, person.getVersion());
        }
        changes.accept(person);
        rejectDuplicate(person);
        Person replaced = repository.saveAndFlush(person);
        if (replaced.getVersion() != expectedVersion) {
            history.updated(replaced, ChangeSource.API);
            events.publishEvent(new PersonsChangedEvent());
        }
        return replaced;
    }

    /**
     * Deletes a person. Its history is kept, including the person as it was before the deletion.
     *
     * @param id the id of the person
     * @throws PersonDeletedException  if the person has already been deleted
     * @throws PersonNotFoundException if no person with that id has ever existed
     */
    @Transactional
    public void delete(UUID id) {
        history.takeTurnToWrite();
        Person person = get(id);
        history.deleted(person, ChangeSource.API);
        repository.delete(person);
        events.publishEvent(new PersonsChangedEvent());
    }

    /**
     * Rejects a person that another person in the database already represents. A Wikidata id
     * identifies a person on its own. Without one, the same name, birth, death and birth country
     * count as the same person, since nothing else in the data could tell them apart.
     */
    private void rejectDuplicate(Person person) {
        if (person.getWikidataId() != null) {
            repository.findByWikidataId(person.getWikidataId())
                    .filter(other -> !other.getId().equals(person.getId()))
                    .ifPresent(other -> {
                        throw new DuplicatePersonException(other.getId(), "Wikidata id " + person.getWikidataId());
                    });
            return;
        }
        HistoricalDate birth = person.getBirth();
        HistoricalDate death = person.getDeath();
        String name = PersonNames.normalize(person.getName());
        repository.findWithSameLifeDatesAndBirthCountry(
                        birth == null ? null : birth.year(),
                        birth == null ? null : birth.month(),
                        birth == null ? null : birth.day(),
                        birth == null ? null : birth.uncertaintyYears(),
                        death == null ? null : death.year(),
                        death == null ? null : death.month(),
                        death == null ? null : death.day(),
                        death == null ? null : death.uncertaintyYears(),
                        person.getBirthCountry())
                .stream()
                .filter(other -> !other.getId().equals(person.getId()))
                .filter(other -> PersonNames.normalize(other.getName()).equals(name))
                .findFirst()
                .ifPresent(other -> {
                    throw new DuplicatePersonException(other.getId(),
                            "the same name, birth, death and birth country");
                });
    }
}
