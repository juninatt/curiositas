package se.pbt.curiositas.wikidata;

import se.pbt.curiositas.api.model.PersonInputDto;

import java.util.List;

/**
 * A person read from Wikidata, in the format of the data files, with notes for the review: where
 * sources disagree, where Wikidata gives several values, or where a value could not be used.
 *
 * @param person the person, ready to be written to its file
 * @param notes  what the review should look at, in plain words, empty when nothing stands out
 */
public record FetchedPerson(PersonInputDto person, List<String> notes) {

    /** Copies the notes, so the result cannot change after it was read. */
    public FetchedPerson {
        notes = List.copyOf(notes);
    }
}
