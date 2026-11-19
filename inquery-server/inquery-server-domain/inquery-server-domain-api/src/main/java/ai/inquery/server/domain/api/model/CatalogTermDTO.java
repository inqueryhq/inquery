package ai.inquery.server.domain.api.model;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Business glossary term: a canonical value as stored in the data, and the
 * aliases (abbreviations, nicknames, other spellings) people use for it.
 */
@Data
public class CatalogTermDTO implements Serializable {
    private Long id;
    private Long dataSourceId;
    private String canonicalValue;
    private List<String> aliases = new ArrayList<>();
    /** Optional schema.table.column holding the value. */
    private String columnRef;
    private String note;
}
