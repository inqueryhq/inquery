package ai.inquery.server.domain.api.param;

import lombok.Data;

import java.io.Serializable;

/**
 * Data catalog column save param
 */
@Data
public class DataCatalogColumnSaveParam implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Column name
     */
    private String columnName;

    /**
     * Column description
     */
    private String columnDescription;

    /**
     * Schema information (JSON format)
     */
    private String schemaInfo;

    /**
     * Example values (JSON array)
     */
    private String exampleValues;

    /**
     * Column ordinal position (DDL order)
     */
    private Integer ordinalPosition;

    /**
     * User-designated event column (values are analytics event names)
     */
    private Boolean isEventColumn;

    /**
     * How the column may be aggregated across rows: additive, distinct or
     * non_additive. Null keeps the stored value on save; empty clears it.
     */
    private String aggregation;

    /**
     * When true, {@link #aggregation} is written only if the column has no
     * stored value yet (automatic suggestions must not override a user choice).
     */
    private Boolean aggregationIfUnset;
}














