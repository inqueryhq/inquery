package ai.inquery.server.web.api.controller.rdb.request;

import lombok.Data;

/**
 * Data catalog column save request
 */
@Data
public class DataCatalogColumnSaveRequest {

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
     * User-designated event column (values are analytics event names)
     */
    private Boolean isEventColumn;

    /**
     * How the column may be aggregated across rows: additive, distinct or
     * non_additive. Null keeps the stored value on save; empty clears it.
     */
    private String aggregation;
}














