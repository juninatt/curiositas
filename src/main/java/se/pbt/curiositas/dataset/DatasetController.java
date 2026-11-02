package se.pbt.curiositas.dataset;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import se.pbt.curiositas.api.DatasetApi;
import se.pbt.curiositas.api.model.DatasetSnapshotDto;
import se.pbt.curiositas.person.PersonMapper;

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
