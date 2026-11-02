package se.pbt.curiositas.dataset;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import se.pbt.curiositas.api.DatasetApi;
import se.pbt.curiositas.api.model.ChangeDto;
import se.pbt.curiositas.api.model.ChangeFeedDto;
import se.pbt.curiositas.api.model.ChangeSourceDto;
import se.pbt.curiositas.api.model.ChangeTypeDto;
import se.pbt.curiositas.api.model.DatasetSnapshotDto;
import se.pbt.curiositas.api.model.RecordTypeDto;
import se.pbt.curiositas.person.ChangeType;
import se.pbt.curiositas.person.PersonMapper;
import se.pbt.curiositas.person.PersonRevision;

import java.time.ZoneOffset;

/**
 * Serves the dataset endpoints described in the OpenAPI contract, for clients that keep their own
 * copy of the data. Paths and models come from the generated {@link DatasetApi}.
 */
@RestController
public class DatasetController implements DatasetApi {

    private final DatasetService service;

    /**
     * Creates the controller.
     *
     * @param service reads the whole dataset
     */
    public DatasetController(DatasetService service) {
        this.service = service;
    }

    /**
     * Returns the changes after the given version. A deletion only names the person, since there
     * is nothing left to show.
     */
    @Override
    public ResponseEntity<ChangeFeedDto> listChanges(Long after, Integer limit) {
        DatasetService.Changes changes = service.changesAfter(after, limit);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ChangeFeedDto(changes.changes().stream().map(DatasetController::toDto).toList(),
                        changes.version(), changes.hasMore()));
    }

    /** Converts one change to a person. */
    private static ChangeDto toDto(PersonRevision revision) {
        return new ChangeDto(
                revision.getSequenceNumber(),
                RecordTypeDto.PERSON,
                revision.getPersonId(),
                ChangeTypeDto.valueOf(revision.getChangeType().name()),
                ChangeSourceDto.valueOf(revision.getSource().name()),
                revision.getChangedAt().atOffset(ZoneOffset.UTC))
                .person(revision.getChangeType() == ChangeType.DELETED ? null : PersonMapper.toDto(revision));
    }

    /** Returns every person with the version of the dataset they belong to. */
    @Override
    public ResponseEntity<DatasetSnapshotDto> getSnapshot() {
        DatasetService.Snapshot snapshot = service.snapshot();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(new DatasetSnapshotDto(snapshot.version(),
                        snapshot.persons().stream().map(PersonMapper::toDto).toList()));
    }
}
