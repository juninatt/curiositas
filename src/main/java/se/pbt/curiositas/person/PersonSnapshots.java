package se.pbt.curiositas.person;

import se.pbt.curiositas.api.model.PersonInputDto;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns persons into the JSON snapshots stored with each revision, and back. Snapshots have the
 * same form as the data files, so a snapshot is exactly what is needed to recreate the person.
 */
final class PersonSnapshots {

    /** Leaves unknown values out, as the generated models are annotated to do. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Not instantiable; all methods are static. */
    private PersonSnapshots() {
    }

    /**
     * Writes the person as a snapshot.
     *
     * @param person the person as stored
     * @return the person as JSON, in the same form as the data files
     */
    static String of(Person person) {
        return JSON.writeValueAsString(PersonMapper.toInput(person));
    }

    /**
     * Reads a snapshot back into the form clients send.
     *
     * @param snapshot the person as JSON, as written by {@link #of(Person)}
     * @return the person's data
     */
    static PersonInputDto read(String snapshot) {
        return JSON.readValue(snapshot, PersonInputDto.class);
    }
}
